package com.example.similarscandemo.compress

import android.Manifest
import android.app.Activity
import android.os.Build

/**
 * Demo 层视频权限申请工具。
 *
 * SDK 只负责检查权限状态，真正触发系统权限弹窗由业务层完成。
 */
object VideoPermissionHelper {
    const val REQUEST_CODE = 4201

    fun request(activity: Activity) {
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        activity.requestPermissions(permissions, REQUEST_CODE)
    }
}
