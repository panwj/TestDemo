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

    fun hasVideoAccess(context: Context): Boolean {
        return accessLevel(context) != VideoAccessLevel.NONE
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
