package com.example.similarscandemo.permission

import android.app.Activity
import android.content.Context
import com.clean.similarscan.permission.SimilarScanPermissionChecker
import com.clean.similarscan.permission.MediaAccessLevel as SdkMediaAccessLevel

/**
 * Demo 层媒体权限申请工具。
 *
 * SDK 只提供权限状态判断和 requiredPermissions；真正弹系统权限框必须留在宿主 App，
 * 这样后续 SDK 接入其他产品时不会强制绑定 Activity 请求流程。
 */
object MediaPermissionHelper {
    const val REQUEST_CODE = 1001

    fun hasPermission(context: Context): Boolean {
        return SimilarScanPermissionChecker.hasPermission(context)
    }

    fun accessLevel(context: Context): MediaAccessLevel {
        return when (SimilarScanPermissionChecker.accessLevel(context)) {
            SdkMediaAccessLevel.NONE -> MediaAccessLevel.NONE
            SdkMediaAccessLevel.PARTIAL_VISUAL -> MediaAccessLevel.PARTIAL_VISUAL
            SdkMediaAccessLevel.FULL_VISUAL -> MediaAccessLevel.FULL_VISUAL
            SdkMediaAccessLevel.LEGACY_FULL -> MediaAccessLevel.LEGACY_FULL
        }
    }

    fun request(activity: Activity) {
        activity.requestPermissions(
            SimilarScanPermissionChecker.requiredPermissions(),
            REQUEST_CODE
        )
    }
}
