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
    /**
     * 判断 SDK 当前是否至少具备一种媒体读取能力。
     *
     * 返回 true 表示可以启动扫描，但扫描范围可能只有图片、只有视频，或 Android 14+
     * 用户选择的部分资源。宿主应用如果需要判断是否拥有完整图库访问，应使用
     * [hasFullVisualAccess] 或 [accessState]。
     */
    fun hasPermission(context: Context): Boolean {
        return canReadImages(context) || canReadVideos(context)
    }

    /**
     * 判断是否拥有完整图片和视频访问能力。
     *
     * Android 13+ 需要同时拥有 READ_MEDIA_IMAGES 和 READ_MEDIA_VIDEO。
     * Android 12 及以下拥有 READ_EXTERNAL_STORAGE 即视为完整访问。
     *
     * 该方法主要用于决定全量扫描后是否允许清理 MediaStore 未返回的旧资源。
     * Android 14+ 的用户选择媒体授权不属于完整访问。
     */
    fun hasFullVisualAccess(context: Context): Boolean {
        return when {
            Build.VERSION.SDK_INT >= 33 ->
                context.isGranted(Manifest.permission.READ_MEDIA_IMAGES) &&
                    context.isGranted(Manifest.permission.READ_MEDIA_VIDEO)
            else -> context.isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * 返回当前粗粒度授权等级。
     *
     * 适合宿主应用做 UI 文案、按钮状态和简单业务分支。如果需要同时获得 canReadImages、
     * canReadVideos、hasFullVisualAccess 等细粒度字段，应使用 [accessState]。
     */
    fun accessLevel(context: Context): MediaAccessLevel {
        return accessState(context).level
    }

    /**
     * 返回当前媒体权限的完整状态快照。
     *
     * SDK 扫描入口使用该状态判断实际枚举哪些 MediaStore 集合，以及是否允许做旧资源清理。
     * 宿主应用也可以使用该状态区分：
     * - 完整图片 + 视频授权；
     * - 仅图片授权；
     * - 仅视频授权；
     * - Android 14+ 用户选择媒体；
     * - 无权限。
     */
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

    /**
     * 判断当前是否可以读取图片集合。
     *
     * Android 14+ 下，如果只有 READ_MEDIA_VISUAL_USER_SELECTED，也可能返回 true；
     * 此时只能读取用户选择的图片/视频子集，不代表完整图片库访问。
     */
    fun canReadImages(context: Context): Boolean {
        return when {
            Build.VERSION.SDK_INT >= 34 ->
                context.isGranted(Manifest.permission.READ_MEDIA_IMAGES) ||
                    context.isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> context.isGranted(Manifest.permission.READ_MEDIA_IMAGES)
            else -> context.isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * 判断当前是否可以读取视频集合。
     *
     * Android 14+ 下，如果只有 READ_MEDIA_VISUAL_USER_SELECTED，也可能返回 true；
     * 此时只能读取用户选择的图片/视频子集，不代表完整视频库访问。
     */
    fun canReadVideos(context: Context): Boolean {
        return when {
            Build.VERSION.SDK_INT >= 34 ->
                context.isGranted(Manifest.permission.READ_MEDIA_VIDEO) ||
                    context.isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> context.isGranted(Manifest.permission.READ_MEDIA_VIDEO)
            else -> context.isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * 返回宿主应用发起媒体读取权限申请时建议请求的权限集合。
     *
     * SDK 不会直接调用 requestPermissions，也不会持有 Activity/Fragment。宿主应用应在自己的
     * 权限流程中调用该方法，并根据用户授权结果重新读取 [accessState]。
     */
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

    /**
     * 判断单个 Android 运行时权限是否已授权。
     */
    private fun Context.isGranted(permission: String): Boolean {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
