package com.clean.videocompress.internal.policy

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import com.clean.videocompress.api.model.CompressVideoAsset

/**
 * 压缩前的视频格式探测器。
 *
 * 只读取 MediaFormat 元数据，不做解码，成本较低。当前用于识别 HEVC、HDR、
 * 真实码率、帧率和旋转信息：
 * - HEVC：允许 Media3 转 H.264。
 * - HDR：默认拦截，避免输出偏色、灰蒙或过曝。
 * - bitrate / frameRate：只在真正压缩当前视频时读取，避免拖慢列表页。
 */
internal class VideoFormatInspector(private val context: Context) {
    /**
     * 读取当前视频的格式画像。
     */
    fun inspect(asset: CompressVideoAsset): VideoFormatProfile {
        val extractor = MediaExtractor()
        return try {
            context.contentResolver.openFileDescriptor(asset.uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: return VideoFormatProfile()
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    return VideoFormatProfile(
                        videoMime = mime,
                        isHevc = mime.equals("video/hevc", ignoreCase = true) ||
                            mime.equals("video/h265", ignoreCase = true),
                        isHdr = isHdr(format),
                        bitrate = readInt(format, MediaFormat.KEY_BIT_RATE),
                        frameRate = readInt(format, MediaFormat.KEY_FRAME_RATE),
                        width = readInt(format, MediaFormat.KEY_WIDTH),
                        height = readInt(format, MediaFormat.KEY_HEIGHT),
                        rotationDegrees = readInt(format, ROTATION_DEGREES)
                    )
                }
            }
            VideoFormatProfile()
        } catch (_: Throwable) {
            VideoFormatProfile()
        } finally {
            extractor.release()
        }
    }

    /**
     * 通过颜色传输函数和颜色标准判断是否属于 HDR。
     */
    private fun isHdr(format: MediaFormat): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
            format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
        } else {
            0
        }
        val standard = if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
            format.getInteger(MediaFormat.KEY_COLOR_STANDARD)
        } else {
            0
        }
        return transfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
            transfer == MediaFormat.COLOR_TRANSFER_HLG ||
            standard == MediaFormat.COLOR_STANDARD_BT2020
    }

    /**
     * 安全读取 MediaFormat 中的整数属性。
     */
    private fun readInt(format: MediaFormat, key: String): Int {
        return try {
            if (format.containsKey(key)) format.getInteger(key) else 0
        } catch (_: Throwable) {
            0
        }
    }

    private companion object {
        const val ROTATION_DEGREES = "rotation-degrees"
    }
}

/**
 * 压缩前读取到的视频格式画像。
 */
internal data class VideoFormatProfile(
    val videoMime: String = "",
    val isHevc: Boolean = false,
    val isHdr: Boolean = false,
    val bitrate: Int = 0,
    val frameRate: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0
)
