package com.clean.similarscan.internal.similarity

import android.content.ContentResolver
import android.net.Uri
import java.security.MessageDigest

/**
 * 文件级 SHA-256，用于严格识别内容完全相同的图片。
 */
class ContentDigestCalculator(private val resolver: ContentResolver) {
    /**
     * 以流式方式计算 uri 对应文件的 SHA-256。
     *
     * 读取失败返回 null，由上层继续走视觉指纹判断，不让单个文件异常中断扫描。
     */
    fun sha256(uri: Uri): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            } ?: return null
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }
}
