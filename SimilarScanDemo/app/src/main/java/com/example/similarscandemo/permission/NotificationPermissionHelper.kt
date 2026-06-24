package com.example.similarscandemo.permission

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 13+ 通知权限，用于后续接入后台扫描进度通知。
 *
 * 用户拒绝通知权限不会阻止媒体扫描。
 */
object NotificationPermissionHelper {
    const val REQUEST_CODE = 1002

    fun needsRequest(activity: Activity): Boolean {
        return Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    }

    fun request(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE
            )
        }
    }
}
