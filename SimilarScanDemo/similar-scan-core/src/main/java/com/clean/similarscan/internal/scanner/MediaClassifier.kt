package com.clean.similarscan.internal.scanner

import java.util.Locale

/**
 * 使用 DISPLAY_NAME 做截图和录屏二次分类。
 *
 * MediaStore 只提供 IMAGE/VIDEO 等基础媒体类型，并没有“截图”和“录屏”字段。
 * 分类规则只读取 `_display_name`，不拼接 bucket 或 relative_path，避免目录名造成额外误分类。
 */
object MediaClassifier {
    /**
     * 判断图片 DISPLAY_NAME 是否像截图。
     *
     * 只依赖文件名，不使用目录名，避免某些目录包含 screen/screenshot 关键词导致误分类。
     */
    fun looksLikeScreenshot(displayName: String): Boolean {
        val text = displayName.lowercase(Locale.ROOT)
        return text.contains("screenshot") ||
            text.contains("screen_shot") ||
            text.contains("screen-shot") ||
            text.startsWith("screenshot_") ||
            text.startsWith("screen_")
    }

    /**
     * 判断视频 DISPLAY_NAME 是否像录屏。
     *
     * 录屏关键词通常来自系统录屏、投屏、镜像或第三方录制工具的文件名。
     */
    fun looksLikeScreenRecording(displayName: String): Boolean {
        val text = displayName.lowercase(Locale.ROOT)
        return text.contains("screen_recording") ||
            text.contains("screen-recording") ||
            text.contains("screenrecord") ||
            text.contains("screen_record") ||
            text.contains("screen-record") ||
            text.startsWith("screen_recording_") ||
            text.startsWith("screenrecord_") ||
            text.startsWith("screen_record_") ||
            text.startsWith("recording_") ||
            text.contains("recording") ||
            text.contains("capture") ||
            text.contains("mirror") ||
            text.contains("cast")
    }

    /**
     * 判断 Other Photos 中的资源是否属于聊天图片。
     *
     * 聊天图片不是基础媒体类型，只是首页展示分类，因此不参与相似/相同判断。
     */
    fun looksLikeChatPhoto(asset: com.clean.similarscan.internal.model.MediaAsset): Boolean {
        return asset.chatSource != null || chatSource(asset.name, asset.bucket, asset.pathHint) != null
    }

    /**
     * 从文件名、相册名、相对路径中识别聊天应用来源。
     */
    fun chatSource(vararg values: String): String? {
        val text = values.joinToString(" ").lowercase(Locale.ROOT)
        return when {
            text.contains("whatsapp") -> "WHATSAPP"
            text.contains("telegram") -> "TELEGRAM"
            text.contains("snapchat") -> "SNAPCHAT"
            text.contains("messenger") || text.contains("facebook") -> "MESSENGER"
            else -> null
        }
    }
}
