package com.example.similarscandemo.contacts

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager

object ContactPermissionHelper {
    const val REQUEST_CODE = 5201

    fun hasPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    fun request(activity: Activity) {
        activity.requestPermissions(
            arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
            REQUEST_CODE
        )
    }
}
