/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.root

import android.annotation.SuppressLint
import android.content.Context
import android.os.Process
import android.util.Log
import eu.chainfire.librootjava.Debugger
import eu.chainfire.librootjava.RootIPC
import eu.chainfire.librootjava.RootIPCReceiver
import eu.chainfire.librootjava.RootJava
import eu.chainfire.libsuperuser.Debug
import eu.chainfire.libsuperuser.Shell
import eu.chainfire.libsuperuser.Shell.Interactive
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.FileSystemProviders
import me.zhanghai.android.files.provider.linux.syscall.Syscalls
import me.zhanghai.android.files.provider.remote.IRemoteFileService
import me.zhanghai.android.files.provider.remote.RemoteFileService
import me.zhanghai.android.files.provider.remote.RemoteFileServiceInterface
import me.zhanghai.android.files.provider.remote.RemoteFileSystemException
import me.zhanghai.android.files.provider.remote.RemoteInterface
import me.zhanghai.android.libselinux.SeLinux
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

val isRunningAsRoot = Process.myUid() == 0

object RootFileService : RemoteFileService(
    RemoteInterface { RootFileService.launchRemoteInterface() }
) {
    private val LOG_TAG = RootFileService::class.java.simpleName

    private const val TIMEOUT_MILLIS = 10 * 1000

    private val lock = Any()

    private var shell: Interactive? = null

    init {
        Debug.setDebug(BuildConfig.DEBUG)
        Debugger.setEnabled(BuildConfig.DEBUG)
    }

    @Throws(RemoteFileSystemException::class)
    private fun launchRemoteInterface(): IRemoteFileService {
        synchronized(lock) {
            val shell = getSuShellLocked()
            RootJava.cleanupCache(application)
            val latch = CountDownLatch(1)
            lateinit var remoteInterface: IRemoteFileService
            val ipcReceiver = object : RootIPCReceiver<IRemoteFileService>(
                application, 0, IRemoteFileService::class.java
            ) {
                private val releaseLock = Any()

                private var isReleasing = false

                override fun onConnect(ipc: IRemoteFileService) {
                    remoteInterface = ipc
                    latch.countDown()
                }

                override fun onDisconnect(ipc: IRemoteFileService) {
                    release()
                }

                override fun release() {
                    synchronized(releaseLock) {
                        if (isReleasing) {
                            return
                        }
                        isReleasing = true
                        super.release()
                        isReleasing = false
                    }
                }
            }
            try {
                val libraryPaths = arrayOf(
                    getLibraryPath(Syscalls.libraryName, application),
                    getLibraryPath(SeLinux.getLibraryName(), application)
                )
                shell.addCommand(
                    RootJava.getLaunchScript(
                        application, javaClass, null, null, libraryPaths,
                        "com.time.cat:root"
                    )
                )
                try {
                    if (!latch.await(TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)) {
                        throw RemoteFileSystemException(
                            TimeoutException("Timeout while connecting to root process")
                        )
                    }
                } catch (e: InterruptedException) {
                    throw RemoteFileSystemException(e)
                }
                return remoteInterface
            } catch (e: Exception) {
                ipcReceiver.release()
                throw e
            }
        }
    }

    @Throws(RemoteFileSystemException::class)
    private fun getSuShellLocked(): Interactive {
        var shell = shell
        if (shell != null) {
            if (shell.isRunning) {
                if (!shell.isIdle) {
                    shell.waitForIdle()
                }
                return shell
            } else {
                shell.close()
                this.shell = null
            }
        }
        shell = launchSuShell()
        this.shell = shell
        return shell
    }

    @Throws(RemoteFileSystemException::class)
    private fun launchSuShell(): Interactive {
        var successful = false
        var exitCode = 0
        val shell = Shell.Builder()
            .useSU()
            .open { success, reason ->
                successful = success
                exitCode = reason
            }
        shell.waitForIdle()
        if (!successful) {
            if (shell.isRunning) {
                shell.closeImmediately()
            }
            throw RemoteFileSystemException("Cannot launch su shell, exit code $exitCode")
        }
        return shell
    }

    @Throws(RemoteFileSystemException::class)
    private fun getLibraryPath(libraryName: String, context: Context): String =
        RootJava.getLibraryPath(context, libraryName)
            ?: throw RemoteFileSystemException("Cannot get path for $libraryName")

    @JvmStatic
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun main(args: Array<String>) {
        Log.i(LOG_TAG, "Loading native libraries")
        args.forEach { System.load(it) }
        RootJava.restoreOriginalLdLibraryPath()
        Log.i(LOG_TAG, "Installing file system providers")
        FileSystemProviders.install()
        FileSystemProviders.overflowWatchEvents = true
        Log.i(LOG_TAG, "Sending Binder")
        val remoteInterface = RemoteFileServiceInterface()
        try {
            RootIPC("com.time.cat", remoteInterface, 0, TIMEOUT_MILLIS, true)
        } catch (e: RootIPC.TimeoutException) {
            e.printStackTrace()
        }
    }
}
