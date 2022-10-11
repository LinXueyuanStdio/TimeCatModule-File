package com.timecat.module.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.google.android.material.chip.Chip
import com.timecat.component.commonsdk.utils.override.LogUtil
import com.timecat.component.router.app.NAV
import com.timecat.data.room.record.RoomRecord
import com.timecat.element.alert.ToastUtil
import com.timecat.identity.readonly.RouterHub
import com.timecat.layout.ui.layout.setShakelessClickListener
import com.timecat.middle.block.ext.launch
import com.timecat.middle.block.service.*
import com.timecat.module.files.item.DirCard
import com.timecat.module.files.item.FileCard
import com.timecat.module.files.item.NavigationCard
import com.xiaojinzi.component.anno.ServiceAnno
import java8.nio.file.DirectoryIteratorException
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.isImage
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.filelist.OpenFileAsDialogActivity
import me.zhanghai.android.files.filelist.OpenFileAsDialogFragment
import me.zhanghai.android.files.filelist.name
import me.zhanghai.android.files.navigation.FileItem
import me.zhanghai.android.files.navigation.navigationFileItems
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.provider.document.isDocumentPath
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.util.*
import me.zhanghai.android.files.viewer.image.ImageViewerActivity
import java.io.IOException
import java.net.URI

/**
 * @author 林学渊
 * @email linxy59@mail2.sysu.edu.cn
 * @date 2021/1/21
 * @description null
 * @usage null
 */
private typealias Uuid = String

private const val FileSchema = "world://timecat.local/"
private const val QUERY_path = "path"
private fun Path.toUuid(): Uuid{
    val uri = DNS.buildUri("timecat.local")
        .appendQueryParameter(QUERY_path, "${this.toUri()}")
        .appendQueryParameter(DNS.QUERY_Redirect, RouterHub.GLOBAL_FileContainerService)
        .build()
    return uri.toString()
}
private fun Uuid.toPath(): Path {
    val uri = DNS.parse(this)
    val path = uri.getQueryParameter(QUERY_path)
    return Paths.get(URI.create(path))
}

@ServiceAnno(ContainerService::class, name = [RouterHub.GLOBAL_FileContainerService])
class FileContainerService : ContainerService {

    override fun loadContext(path: com.timecat.layout.ui.business.breadcrumb.Path, context: Context, parentUuid: String, record: RoomRecord?, homeService: HomeService) {
        homeService.loadMenu(EmptyMenuContext())
        homeService.loadHeader(listOf())
        homeService.loadChipType(listOf())
        homeService.loadPanel(EmptyPanelContext())
        homeService.loadChipButtons(listOf(Chip(context).apply {
            text = "文件管理页"
            setShakelessClickListener {
                NAV.go(RouterHub.FILES_FilesActivity)
            }
        }))
        homeService.loadCommand(EmptyCommandContext())
        homeService.loadInputSend(EmptyInputContext())
        homeService.reloadData()
    }

    override fun loadContextRecord(
        path: com.timecat.layout.ui.business.breadcrumb.Path,
        context: Context,
        parentUuid: String,
        homeService: HomeService
    ) {
        homeService.loadContextRecord(null)
    }

    override fun loadMoreForVirtualPath(context: Context, parentUuid: String, offset: Int, homeService: HomeService, callback: ContainerService.LoadMoreCallback) {
        callback.onVirtualLoadSuccess(listOf())
    }


    override fun loadForVirtualPath(context: Context, parentUuid: String, homeService: HomeService, callback: ContainerService.LoadCallback) {
        val uri = DNS.parse(parentUuid)
        val pathStr = uri.getQueryParameter(QUERY_path)
        if (pathStr.isNullOrEmpty()) {
            loadForFileHome(context, parentUuid, homeService, callback)
        } else {
            val path = parentUuid.toPath()
            loadForFileDir(context, path, homeService, callback)
        }
    }

    private fun loadForFileDir(context: Context, parentUuid: Path, homeService: HomeService, callback: ContainerService.LoadCallback) {
        context.launch(Dispatchers.IO) {
            val fileList = try {
                LogUtil.se("start load files")
                parentUuid.newDirectoryStream().use { directoryStream ->
                    LogUtil.se("open files stream")
                    val fileList = mutableListOf<me.zhanghai.android.files.file.FileItem>()
                    for (path in directoryStream) {
                        try {
                            fileList.add(path.loadFileItem())
                        } catch (e: DirectoryIteratorException) {
                            e.printStackTrace()
                        } catch (e: IOException) {
                            e.printStackTrace()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    LogUtil.se("load files success")
                    fileList
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    val stateful = homeService.statefulView()
                    stateful?.showError(e.message) {
                        homeService.reloadData()
                    }
                }
                return@launch
            }
            val fileCards = fileList.sortedWith(compareBy(
                { !it.attributes.isDirectory },
                { it.mimeType.value },
                { it.name },
            )).map {
                if (it.attributes.isDirectory)
                    DirCard(it, context, object : DirCard.Listener {
                        override fun loadFor(fileItem: me.zhanghai.android.files.file.FileItem) {
                            val uuid = fileItem.path.toUuid()
                            homeService.navigateTo(fileItem.name, uuid)
                        }
                    })
                else
                    FileCard(it, context, object : FileCard.Listener {
                        override fun open(fileItem: me.zhanghai.android.files.file.FileItem) {
                            openFileWithIntent(context, fileItem, fileList, true)
                        }
                    })
            }
            callback.onVirtualLoadSuccess(fileCards)
        }
    }


    private fun openFileWithIntent(
        context: Context,
        file: me.zhanghai.android.files.file.FileItem,
        fileItems: List<me.zhanghai.android.files.file.FileItem>,
        withChooser: Boolean
    ) {
        val path = file.path
        val mimeType = file.mimeType
        if (path.isLinuxPath || path.isDocumentPath) {
            val intent = path.fileProviderUri.createViewIntent(mimeType)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .apply {
                    extraPath = path
                    maybeAddImageViewerActivityExtras(this, path, fileItems, mimeType)
                }
                .let {
                    if (withChooser) {
                        it.withChooser(
                            OpenFileAsDialogActivity::class.createIntent()
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                .putArgs(OpenFileAsDialogFragment.Args(path))
                        )
                    } else {
                        it
                    }
                }
            try {
                context.startActivity(intent, null)
            } catch (e: ActivityNotFoundException) {
                ToastUtil.e(R.string.activity_not_found)
            }
        } else {
            FileJobService.open(path, mimeType, withChooser, context)
        }
    }


    private fun maybeAddImageViewerActivityExtras(
        intent: Intent,
        path: Path,
        fileItems: List<me.zhanghai.android.files.file.FileItem>,
        mimeType: MimeType
    ) {
        if (!mimeType.isImage) {
            return
        }
        val paths = mutableListOf<Path>()
        // We need the ordered list from our adapter instead of the list from FileListLiveData.
        for (file in fileItems) {
            val filePath = file.path
            if (file.mimeType.isImage || filePath == path) {
                paths.add(filePath)
            }
        }
        val position = paths.indexOf(path)
        if (position == -1) {
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ImageViewerActivity.putExtras(intent, paths, position)
    }

    private fun loadForFileHome(context: Context, parentUuid: String, homeService: HomeService, callback: ContainerService.LoadCallback) {
        context.launch(Dispatchers.IO) {
            val navigationCards = navigationFileItems.map {
                NavigationCard(it, context, object : NavigationCard.Listener {
                    override fun loadFor(fileItem: FileItem) {
                        val uuid = fileItem.path.toUuid()
                        homeService.navigateTo(fileItem.getTitle(context), uuid)
                    }
                })
            }
            withContext(Dispatchers.Main) {
                callback.onVirtualLoadSuccess(navigationCards)
            }
        }
    }
}

