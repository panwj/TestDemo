package com.clean.videocompress.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 视频压缩权限检查工具。
 *
 * SDK 只做权限状态判断，不主动触发系统权限弹窗。接入方可根据 accessLevel 的结果
 * 自行决定展示说明、调用 requestPermissions 或跳转系统设置。
 */
object VideoCompressPermissionChecker {
    /**
     * 返回当前视频读取授权等级。
     */
    fun accessLevel(context: Context): VideoAccessLevel {
        return when {
            Build.VERSION.SDK_INT >= 34 &&
                hasPermission(context, Manifest.permission.READ_MEDIA_VIDEO) ->
                VideoAccessLevel.FULL_VIDEO

            Build.VERSION.SDK_INT >= 34 &&
                hasPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ->
                VideoAccessLevel.PARTIAL_VIDEO

            Build.VERSION.SDK_INT >= 33 &&
                hasPermission(context, Manifest.permission.READ_MEDIA_VIDEO) ->
                VideoAccessLevel.FULL_VIDEO

            Build.VERSION.SDK_INT < 33 &&
                hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ->
                VideoAccessLevel.LEGACY_FULL

            else -> VideoAccessLevel.NONE
        }
    }

    /**
     * 是否拥有读取视频资源的权限。
     */
    fun hasVideoAccess(context: Context): Boolean {
        return accessLevel(context) != VideoAccessLevel.NONE
    }

    /**
     * 是否拥有保存压缩结果到系统媒体库的权限。
     *
     * Android 10+ 使用分区存储，应用可以通过 MediaStore 写入自己创建的视频；
     * Android 9 及以下写入公共 Movies 目录需要 WRITE_EXTERNAL_STORAGE。
     */
    fun hasSaveAccess(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= 29 ||
            hasPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
