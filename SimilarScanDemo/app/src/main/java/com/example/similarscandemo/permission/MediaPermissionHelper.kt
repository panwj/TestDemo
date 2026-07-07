package com.example.similarscandemo.permission

import android.app.Activity
import android.content.Context
import android.os.Build
import android.Manifest
import com.clean.similarscan.permission.SimilarScanPermissionChecker
import com.example.similarscandemo.util.MmkvStore
import com.clean.similarscan.permission.MediaAccessLevel as SdkMediaAccessLevel

/**
 * Demo 层媒体权限申请工具。
 *
 * SDK 只提供权限状态判断和 requiredPermissions；真正弹系统权限框必须留在宿主 App，
 * 这样后续 SDK 接入其他产品时不会强制绑定 Activity 请求流程。
 */
object MediaPermissionHelper {
    const val REQUEST_CODE = 1001
    private const val PREFS_NAME = "media_permission_state"
    private const val KEY_REQUESTED_MEDIA_PERMISSION = "requested_media_permission"

    fun hasPermission(context: Context): Boolean {
        return SimilarScanPermissionChecker.hasPermission(context)
    }

    fun accessLevel(context: Context): MediaAccessLevel {
        return when (SimilarScanPermissionChecker.accessLevel(context)) {
            SdkMediaAccessLevel.NONE -> MediaAccessLevel.NONE
            SdkMediaAccessLevel.IMAGES_ONLY -> MediaAccessLevel.IMAGES_ONLY
            SdkMediaAccessLevel.VIDEOS_ONLY -> MediaAccessLevel.VIDEOS_ONLY
            SdkMediaAccessLevel.PARTIAL_VISUAL -> MediaAccessLevel.PARTIAL_VISUAL
            SdkMediaAccessLevel.FULL_VISUAL -> MediaAccessLevel.FULL_VISUAL
            SdkMediaAccessLevel.LEGACY_FULL -> MediaAccessLevel.LEGACY_FULL
        }
    }

    fun request(activity: Activity) {
        markRequested(activity)
        activity.requestPermissions(
            SimilarScanPermissionChecker.requiredPermissions(),
            REQUEST_CODE
        )
    }

    fun shouldOpenAppSettings(activity: Activity): Boolean {
        if (hasPermission(activity)) return false
        if (!hasRequested(activity)) return false
        val missingPermissions = blockingPermissions().filterNot { permission ->
            activity.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) return false
        /*
         * shouldShowRequestPermissionRationale=false 有两种情况：
         * 1. 首次请求前；
         * 2. 用户勾选“不再提醒”或连续拒绝后系统不再弹窗。
         *
         * 这里额外要求 hasRequested=true，排除首次请求前的 false，避免第一次点击扫描
         * 就跳设置页。
         */
        return missingPermissions.all { permission ->
            !activity.shouldShowRequestPermissionRationale(permission)
        }
    }

    private fun markRequested(context: Context) {
        MmkvStore.store(context, PREFS_NAME).encode(KEY_REQUESTED_MEDIA_PERMISSION, true)
    }

    private fun hasRequested(context: Context): Boolean {
        return MmkvStore.store(context, PREFS_NAME).decodeBool(KEY_REQUESTED_MEDIA_PERMISSION, false)
    }

    private fun blockingPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
