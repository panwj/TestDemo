package com.clean.videocompress.internal.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.clean.videocompress.api.model.CompressVideoAsset

/**
 * 视频媒体库读取器。
 *
 * 只读取列表展示和粗略估算需要的字段，不在列表阶段逐个打开视频文件。
 */
internal class VideoMediaRepository(private val context: Context) {
    /**
     * 从系统 MediaStore 读取视频资源，按添加时间倒序返回。
     */
    fun loadVideos(): List<CompressVideoAsset> {
        val videos = mutableListOf<CompressVideoAsset>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED
        )
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Video.Media.SIZE} > 0",
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val widthIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                videos += CompressVideoAsset(
                    id = id,
                    uri = uri,
                    displayName = cursor.getString(nameIndex).orEmpty().ifBlank { "Video-$id.mp4" },
                    sizeBytes = cursor.getLong(sizeIndex).coerceAtLeast(0L),
                    durationMs = cursor.getLong(durationIndex).coerceAtLeast(0L),
                    width = cursor.getInt(widthIndex).coerceAtLeast(0),
                    height = cursor.getInt(heightIndex).coerceAtLeast(0),
                    dateAddedSeconds = cursor.getLong(addedIndex),
                    dateModifiedSeconds = cursor.getLong(modifiedIndex),
                    /*
                     * 列表页只需要快速展示视频和估算节省空间，不应该逐个打开文件读取码率。
                     * MediaMetadataRetriever 对几十到几百个视频会明显拖慢 Compress 首页和分组详情。
                     * 真正压缩时 BitrateCalculator 会优先使用已有码率；这里为 0 时会根据文件大小和时长估算。
                     */
                    bitrate = 0
                )
            }
        }
        return videos
    }
}
