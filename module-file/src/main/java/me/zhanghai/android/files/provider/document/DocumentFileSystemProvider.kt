/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.document

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import java8.nio.channels.FileChannel
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.*
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.spi.FileSystemProvider
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.provider.common.*
import me.zhanghai.android.files.provider.content.resolver.Resolver.openInputStream
import me.zhanghai.android.files.provider.content.resolver.Resolver.openOutputStream
import me.zhanghai.android.files.provider.content.resolver.Resolver.openParcelFileDescriptor
import me.zhanghai.android.files.provider.content.resolver.ResolverException
import me.zhanghai.android.files.provider.document.resolver.DocumentResolver
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI

object DocumentFileSystemProvider : FileSystemProvider(), PathObservableProvider, Searchable {
    private const val SCHEME = "document"

    private val HIDDEN_FILE_NAME_PREFIX = ".".toByteString()

    private val fileSystems = mutableMapOf<Uri, DocumentFileSystem>()

    private val lock = Any()

    override fun getScheme(): String = SCHEME

    override fun newFileSystem(uri: URI, env: Map<String?, *>): FileSystem {
        uri.requireSameScheme()
        val treeUri = uri.treeUri
        synchronized(lock) {
            if (fileSystems[treeUri] != null) {
                throw FileSystemAlreadyExistsException(treeUri.toString())
            }
            return newFileSystemLocked(treeUri)
        }
    }

    internal fun getOrNewFileSystem(treeUri: Uri): DocumentFileSystem =
        synchronized(lock) { fileSystems[treeUri] ?: newFileSystemLocked(treeUri) }

    private fun newFileSystemLocked(treeUri: Uri): DocumentFileSystem {
        val fileSystem = DocumentFileSystem(this, treeUri)
        fileSystems[treeUri] = fileSystem
        return fileSystem
    }

    override fun getFileSystem(uri: URI): FileSystem {
        uri.requireSameScheme()
        val treeUri = uri.treeUri
        return synchronized(lock) { fileSystems[treeUri] }
            ?: throw FileSystemNotFoundException(treeUri.toString())
    }

    internal fun removeFileSystem(fileSystem: DocumentFileSystem) {
        val treeUri = fileSystem.treeUri
        synchronized(lock) { fileSystems.remove(treeUri) }
    }

    override fun getPath(uri: URI): Path {
        uri.requireSameScheme()
        val treeUri = uri.treeUri
        val fragment = uri.decodedFragmentByteString
            ?: throw IllegalArgumentException("URI must have a fragment")
        return getOrNewFileSystem(treeUri).getPath(fragment)
    }

    private fun URI.requireSameScheme() {
        val scheme = scheme
        require(scheme == SCHEME) { "URI scheme $scheme must be $SCHEME" }
    }

    private val URI.treeUri: Uri
        get() {
            val schemeSpecificPart = decodedSchemeSpecificPartByteString
                ?: throw IllegalArgumentException("URI must have a scheme specific part")
            return schemeSpecificPart.toString().toUri()
        }

    @Throws(IOException::class)
    override fun newInputStream(file: Path, vararg options: OpenOption): InputStream {
        file as? DocumentPath ?: throw ProviderMismatchException(file.toString())
        val optionsSet = mutableSetOf(*options)
        val create = optionsSet.remove(StandardOpenOption.CREATE)
        val createNew = optionsSet.remove(StandardOpenOption.CREATE_NEW)
        val openOptions = optionsSet.toOpenOptions()
        if (openOptions.write) {
            throw UnsupportedOperationException(StandardOpenOption.WRITE.toString())
        }
        if (openOptions.append) {
            throw UnsupportedOperationException(StandardOpenOption.APPEND.toString())
        }
        val mode = openOptions.toDocumentMode()
        if (create || createNew) {
            val exists = DocumentResolver.exists(file)
            if (createNew && exists) {
                throw FileAlreadyExistsException(file.toString())
            }
            if (!exists) {
                val uri = try {
                    // TODO: Allow passing in a mime type?
                    DocumentResolver.create(file, MimeType.GENERIC.value)
                } catch (e: ResolverException) {
                    throw e.toFileSystemException(file.toString())
                }
                return try {
                    openInputStream(uri, mode)
                } catch (e: ResolverException) {
                    throw e.toFileSystemException(uri.toString())
                }
            }
        }
        return try {
            DocumentResolver.openInputStream(file, mode)
        } catch (e: ResolverException) {
            throw e.toFileSystemException(file.toString())
        }
    }

    @Throws(IOException::class)
    override fun newOutputStream(file: Path, vararg options: OpenOption): OutputStream {
        file as? DocumentPath ?: throw ProviderMismatchException(file.toString())
        val optionsSet = mutableSetOf(*options)
        if (optionsSet.isEmpty()) {
            optionsSet += StandardOpenOption.CREATE
            optionsSet += StandardOpenOption.TRUNCATE_EXISTING
        }
        optionsSet += StandardOpenOption.WRITE
        val create = optionsSet.remove(StandardOpenOption.CREATE)
        val createNew = optionsSet.remove(StandardOpenOption.CREATE_NEW)
        val openOptions = optionsSet.toOpenOptions()
        val mode = openOptions.toDocumentMode()
        if (create || createNew) {
            val exists = DocumentResolver.exists(file)
            if (createNew && exists) {
                throw FileAlreadyExistsException(file.toString())
            }
            if (!exists) {
                val uri = try {
                    // TODO: Allow passing in a mime type?
                    DocumentResolver.create(file, MimeType.GENERIC.value)
                } catch (e: ResolverException) {
                    throw e.toFileSystemException(file.toString())
                }
                return try {
                    openOutputStream(uri, mode)
                } catch (e: ResolverException) {
                    throw e.toFileSystemException(uri.toString())
                }
            }
        }
        return try {
            DocumentResolver.openOutputStream(file, mode)
        } catch (e: ResolverException) {
            throw e.toFileSystemException(file.toString())
        }
    }

    @Throws(IOException::class)
    override fun newFileChannel(
        file: Path,
        options: Set<OpenOption>,
        vararg attributes: FileAttribute<*>
    ): FileChannel {
        file as? DocumentPath ?: throw ProviderMismatchException(file.toString())
        val options = options.toMutableSet()
        val hasCreate = options.remove(StandardOpenOption.CREATE)
        val hasCreateNew = options.remove(StandardOpenOption.CREATE_NEW)
        val openOptions = options.toOpenOptions()
        val mode = openOptions.toDocumentMode()
        if (attributes.isNotEmpty()) {
            throw UnsupportedOperationException(attributes.contentToString())
        }
        var pfd: ParcelFileDescriptor? = null
        if (hasCreate || hasCreateNew) {
            val exists = DocumentResolver.exists(file)
            if (hasCreateNew && exists) {
                throw FileAlreadyExistsException(file.toString())
            }
            if (!exists) {
                val uri = try {
                    // TODO: Allow passing in a mime type?
                    DocumentResolver.create(file, MimeType.GENERIC.value)
                } catch (e: ResolverException) {
                    throw e.toFileSystemException(file.toString())
                }
                pfd = try {
                    openParcelFileDescriptor(uri, mode)
                } catch (e: ResolverException) {
                    throw e.toFileSystemException(uri.toString())
                }
            }
        }
        if (pfd == null) {
            pfd = try {
                DocumentResolver.openParcelFileDescriptor(file, mode)
            } catch (e: ResolverException) {
                throw e.toFileSystemException(file.toString())
            }
        }
        return FileChannel::class.open(pfd!!, mode)
    }

    @Throws(IOException::class)
    override fun newByteChannel(
        file: Path,
        options: Set<OpenOption>,
        vararg attributes: FileAttribute<*>
    ): SeekableByteChannel {
        file as? DocumentPath ?: throw ProviderMismatchException(file.toString())
        return newFileChannel(file, options, *attributes)
    }

    @Throws(IOException::class)
    override fun newDirectoryStream(
        directory: Path,
        filter: DirectoryStream.Filter<in Path>
    ): DirectoryStream<Path> {
        directory as? DocumentPath ?: throw ProviderMismatchException(directory.toString())
        val children = try {
            @Suppress("UNCHECKED_CAST")
            DocumentResolver.queryChildren(directory) as List<Path>
        } catch (e: ResolverException) {
            throw e.toFileSystemException(directory.toString())
        }
        // TODO: Handle DocumentsContract.EXTRA_LOADING, EXTRA_INFO and EXTRA_ERROR.
        return PathListDirectoryStream(children, filter)
    }

    @Throws(IOException::class)
    override fun createDirectory(directory: Path, vararg attributes: FileAttribute<*>) {
        directory as? DocumentPath ?: throw ProviderMismatchException(directory.toString())
        if (attributes.isNotEmpty()) {
            throw UnsupportedOperationException(attributes.contentToString())
        }
        try {
            DocumentResolver.create(directory, MimeType.DIRECTORY.value)
        } catch (e: ResolverException) {
            throw e.toFileSystemException(directory.toString())
        }
    }

    override fun createSymbolicLink(link: Path, target: Path, vararg attributes: FileAttribute<*>) {
        link as? DocumentPath ?: throw ProviderMismatchException(link.toString())
        when (target) {
            is DocumentPath, is ByteStringPath -> {}
            else -> throw ProviderMismatchException(target.toString())
        }
        throw UnsupportedOperationException()
    }

    override fun createLink(link: Path, existing: Path) {
        link as? DocumentPath ?: throw ProviderMismatchException(link.toString())
        existing as? DocumentPath ?: throw ProviderMismatchException(existing.toString())
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun delete(path: Path) {
        path as? DocumentPath ?: throw ProviderMismatchException(path.toString())
        try {
            DocumentResolver.remove(path)
        } catch (e: ResolverException) {
            throw e.toFileSystemException(path.toString())
        }
    }

    override fun readSymbolicLink(link: Path): Path {
        link as? DocumentPath ?: throw ProviderMismatchException(link.toString())
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun copy(source: Path, target: Path, vararg options: CopyOption) {
        source as? DocumentPath ?: throw ProviderMismatchException(source.toString())
        target as? DocumentPath ?: throw ProviderMismatchException(target.toString())
        val copyOptions = options.toCopyOptions()
        DocumentCopyMove.copy(source, target, copyOptions)
    }

    @Throws(IOException::class)
    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        source as? DocumentPath ?: throw ProviderMismatchException(source.toString())
        target as? DocumentPath ?: throw ProviderMismatchException(target.toString())
        val copyOptions = options.toCopyOptions()
        DocumentCopyMove.move(source, target, copyOptions)
    }

    override fun isSameFile(path: Path, path2: Path): Boolean {
        path as? DocumentPath ?: throw ProviderMismatchException(path.toString())
        // TODO: DocumentsContract.findDocumentPath()?
        return path == path2
    }

    override fun isHidden(path: Path): Boolean {
        path as? DocumentPath ?: throw ProviderMismatchException(path.toString())
        val fileName = path.fileNameByteString ?: return false
        return fileName.startsWith(HIDDEN_FILE_NAME_PREFIX)
    }

    override fun getFileStore(path: Path): FileStore {
        path as? DocumentPath ?: throw ProviderMismatchException(path.toString())
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        path as? DocumentPath ?: throw ProviderMismatchException(path.toString())
        val accessModes = modes.toAccessModes()
        if (accessModes.execute) {
            throw AccessDeniedException(path.toString())
        }
        if (accessModes.write) {
            try {
                DocumentResolver.openOutputStream(path, "w").use {}
            } catch (e: ResolverException) {
                throw e.toFileSystemException(path.toString())
            }
        }
        if (accessModes.read) {
            try {
                DocumentResolver.openInputStream(path, "r").use {}
            } catch (e: ResolverException) {
                throw e.toFileSystemException(path.toString())
            }
        }
        if (!(accessModes.read || accessModes.write)) {
            try {
                DocumentResolver.checkExistence(path)
            } catch (e: ResolverException) {
                throw e.toFileSystemException(path.toString())
            }
        }
    }

    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption
    ): V? {
        if (!supportsFileAttributeView(type)) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return getFileAttributeView(path) as V
    }

    internal fun supportsFileAttributeView(type: Class<out FileAttributeView>): Boolean =
        type.isAssignableFrom(DocumentFileAttributeView::class.java)

    @Throws(IOException::class)
    override fun <A : BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption
    ): A {
        if (!type.isAssignableFrom(DocumentFileAttributes::class.java)) {
            throw UnsupportedOperationException(type.toString())
        }
        @Suppress("UNCHECKED_CAST")
        return getFileAttributeView(path).readAttributes() as A
    }

    private fun getFileAttributeView(path: Path): DocumentFileAttributeView {
        path as? DocumentPath ?: throw ProviderMismatchException(path.toString())
        return DocumentFileAttributeView(path)
    }

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption
    ): Map<String, Any> {
        path as? DocumentPath ?: throw ProviderMismatchException(path.toString())
        throw UnsupportedOperationException()
    }

    override fun setAttribute(
        path: Path,
        attribute: String,
        value: Any,
        vararg options: LinkOption
    ) {
        path as? DocumentPath ?: throw ProviderMismatchException(path.toString())
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun observe(path: Path, intervalMillis: Long): PathObservable {
        path as? DocumentPath ?: throw ProviderMismatchException(path.toString())
        return DocumentPathObservable(path, intervalMillis)
    }

    @Throws(IOException::class)
    override fun search(
        directory: Path,
        query: String,
        intervalMillis: Long,
        listener: (List<Path>) -> Unit
    ) {
        directory as? DocumentPath ?: throw ProviderMismatchException(directory.toString())
        WalkFileTreeSearchable.search(directory, query, intervalMillis, listener)
    }
}
