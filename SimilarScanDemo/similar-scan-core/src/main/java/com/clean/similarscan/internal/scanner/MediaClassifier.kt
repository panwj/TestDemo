package com.clean.similarscan.internal.scanner

import java.util.Locale

/**
 * 使用 DISPLAY_NAME 做截图和录屏二次分类。
 *
 * MediaStore 只提供 IMAGE/VIDEO 等基础媒体类型，并没有“截图”和“录屏”字段。
 * 分类规则只读取 `_display_name`，不拼接 bucket 或 relative_path，避免目录名造成额外误分类。
 */
object MediaClassifier {
    fun looksLikeScreenshot(displayName: String): Boolean {
        val text = displayName.lowercase(Locale.ROOT)
        return text.contains("screenshot") ||
            text.contains("screen_shot") ||
            text.contains("screen-shot") ||
            text.startsWith("screenshot_") ||
            text.startsWith("screen_")
    }

    /** 录屏关键词集合。 */
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

    fun looksLikeChatPhoto(asset: com.clean.similarscan.internal.model.MediaAsset): Boolean {
        return asset.chatSource != null || chatSource(asset.name, asset.bucket, asset.pathHint) != null
    }

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
