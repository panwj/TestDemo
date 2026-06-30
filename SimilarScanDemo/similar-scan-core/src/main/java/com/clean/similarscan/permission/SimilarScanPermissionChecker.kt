package com.clean.similarscan.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * SDK 媒体读取权限检查工具。
 *
 * target 36 下需要兼容三段权限模型：
 * - Android 12 及以下：READ_EXTERNAL_STORAGE
 * - Android 13：READ_MEDIA_IMAGES / READ_MEDIA_VIDEO
 * - Android 14+：用户可只授予部分照片访问，因此额外请求 READ_MEDIA_VISUAL_USER_SELECTED
 *
 * SDK 只负责判断权限状态和提供 requiredPermissions，不主动弹系统权限框；
 * 权限申请必须由宿主 App 在自己的 Activity/Fragment 中完成。
 */
object SimilarScanPermissionChecker {
    fun hasPermission(context: Context): Boolean {
        return canReadImages(context) || canReadVideos(context)
    }

    fun hasFullVisualAccess(context: Context): Boolean {
        return when {
            Build.VERSION.SDK_INT >= 33 ->
                context.isGranted(Manifest.permission.READ_MEDIA_IMAGES) &&
                    context.isGranted(Manifest.permission.READ_MEDIA_VIDEO)
            else -> context.isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun accessLevel(context: Context): MediaAccessLevel {
        return accessState(context).level
    }

    fun accessState(context: Context): MediaAccessState {
        return when {
            Build.VERSION.SDK_INT >= 34 -> {
                val hasImages = context.isGranted(Manifest.permission.READ_MEDIA_IMAGES)
                val hasVideo = context.isGranted(Manifest.permission.READ_MEDIA_VIDEO)
                val hasPartial = context.isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                val canReadImages = hasImages || hasPartial
                val canReadVideos = hasVideo || hasPartial
                val level = when {
                    hasImages && hasVideo -> MediaAccessLevel.FULL_VISUAL
                    hasPartial -> MediaAccessLevel.PARTIAL_VISUAL
                    hasImages -> MediaAccessLevel.IMAGES_ONLY
                    hasVideo -> MediaAccessLevel.VIDEOS_ONLY
                    else -> MediaAccessLevel.NONE
                }
                MediaAccessState(
                    level = level,
                    canReadImages = canReadImages,
                    canReadVideos = canReadVideos,
                    hasFullVisualAccess = hasImages && hasVideo,
                    hasPartialVisualAccess = hasPartial && !(hasImages && hasVideo)
                )
            }
            Build.VERSION.SDK_INT >= 33 -> {
                val hasImages = context.isGranted(Manifest.permission.READ_MEDIA_IMAGES)
                val hasVideo = context.isGranted(Manifest.permission.READ_MEDIA_VIDEO)
                val level = when {
                    hasImages && hasVideo -> MediaAccessLevel.FULL_VISUAL
                    hasImages -> MediaAccessLevel.IMAGES_ONLY
                    hasVideo -> MediaAccessLevel.VIDEOS_ONLY
                    else -> MediaAccessLevel.NONE
                }
                MediaAccessState(
                    level = level,
                    canReadImages = hasImages,
                    canReadVideos = hasVideo,
                    hasFullVisualAccess = hasImages && hasVideo,
                    hasPartialVisualAccess = false
                )
            }
            context.isGranted(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccessState(
                level = MediaAccessLevel.LEGACY_FULL,
                canReadImages = true,
                canReadVideos = true,
                hasFullVisualAccess = true,
                hasPartialVisualAccess = false
            )
            else -> MediaAccessState(
                level = MediaAccessLevel.NONE,
                canReadImages = false,
                canReadVideos = false,
                hasFullVisualAccess = false,
                hasPartialVisualAccess = false
            )
        }
    }

    fun canReadImages(context: Context): Boolean {
        return when {
            Build.VERSION.SDK_INT >= 34 ->
                context.isGranted(Manifest.permission.READ_MEDIA_IMAGES) ||
                    context.isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> context.isGranted(Manifest.permission.READ_MEDIA_IMAGES)
            else -> context.isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun canReadVideos(context: Context): Boolean {
        return when {
            Build.VERSION.SDK_INT >= 34 ->
                context.isGranted(Manifest.permission.READ_MEDIA_VIDEO) ||
                    context.isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> context.isGranted(Manifest.permission.READ_MEDIA_VIDEO)
            else -> context.isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun requiredPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun Context.isGranted(permission: String): Boolean {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
