package com.clean.similarscan.internal.scanner

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.clean.similarscan.internal.model.MediaAsset
import com.clean.similarscan.internal.model.MediaKind
import com.clean.similarscan.permission.SimilarScanPermissionChecker
import com.clean.similarscan.internal.util.int
import com.clean.similarscan.internal.util.long
import com.clean.similarscan.internal.util.string
import com.clean.similarscan.internal.util.useCursor
import java.util.Date

/**
 * 负责从 MediaStore 读取原始媒体列表。
 */
class MediaStoreRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    fun forEachMediaBatch(
        batchSize: Int,
        imageGenerationAfter: Long = 0L,
        videoGenerationAfter: Long = 0L,
        onBatch: (List<MediaAsset>) -> Unit
    ) {
        if (SimilarScanPermissionChecker.canReadImages(appContext)) {
            /*
             * 不能静默吞掉 MediaStore 查询异常。全量扫描若只成功枚举一部分资源，
             * 后续“清理未出现记录”会把缓存中的合法结果误判为已删除。
             * 异常直接抛给前台服务，扫描游标也不会提前推进。
             */
            forEachImageBatch(batchSize, imageGenerationAfter, onBatch)
        }
        if (SimilarScanPermissionChecker.canReadVideos(appContext)) {
            forEachVideoBatch(batchSize, videoGenerationAfter, onBatch)
        }
    }

    private fun forEachImageBatch(
        batchSize: Int,
        generationAfter: Long,
        onBatch: (List<MediaAsset>) -> Unit
    ) {
        val batch = mutableListOf<MediaAsset>()
        val projection = imageProjection()
        val generationSelection = generationSelection(
            MediaStore.Images.Media.GENERATION_MODIFIED,
            generationAfter
        )
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.SIZE} > 0${generationSelection.first}",
            generationSelection.second,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        ).useCursor { cursor ->
            while (cursor.moveToNext()) {
                batch += imageFromCursor(cursor)
                if (batch.size >= batchSize) {
                    onBatch(batch.toList())
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) onBatch(batch.toList())
    }

    private fun forEachVideoBatch(
        batchSize: Int,
        generationAfter: Long,
        onBatch: (List<MediaAsset>) -> Unit
    ) {
        val batch = mutableListOf<MediaAsset>()
        val projection = videoProjection()
        val generationSelection = generationSelection(
            MediaStore.Video.Media.GENERATION_MODIFIED,
            generationAfter
        )
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Video.Media.SIZE} > 0${generationSelection.first}",
            generationSelection.second,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        ).useCursor { cursor ->
            while (cursor.moveToNext()) {
                batch += videoFromCursor(cursor)
                if (batch.size >= batchSize) {
                    onBatch(batch.toList())
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) onBatch(batch.toList())
    }

    fun queryImages(limit: Int): List<MediaAsset> {
        val projection = imageProjection()

        return resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.SIZE} > 0",
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        ).useCursor { cursor ->
            val assets = mutableListOf<MediaAsset>()
            while (cursor.moveToNext() && assets.size < limit) {
                assets += imageFromCursor(cursor)
            }
            assets
        }
    }

    fun queryVideos(limit: Int): List<MediaAsset> {
        val projection = videoProjection()

        return resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Video.Media.SIZE} > 0",
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        ).useCursor { cursor ->
            val assets = mutableListOf<MediaAsset>()
            while (cursor.moveToNext() && assets.size < limit) {
                assets += videoFromCursor(cursor)
            }
            assets
        }
    }

    private fun imageProjection() = buildList {
        add(MediaStore.Images.Media._ID)
        add(MediaStore.Images.Media.DISPLAY_NAME)
        add(MediaStore.Images.Media.SIZE)
        add(MediaStore.Images.Media.WIDTH)
        add(MediaStore.Images.Media.HEIGHT)
        add(MediaStore.Images.Media.DATE_ADDED)
        add(MediaStore.Images.Media.DATE_TAKEN)
        add(MediaStore.Images.Media.DATE_MODIFIED)
        add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        add(MediaStore.Images.Media.MIME_TYPE)
        if (Build.VERSION.SDK_INT >= 29) add(MediaStore.Images.Media.RELATIVE_PATH)
        if (Build.VERSION.SDK_INT >= 30) {
            add(MediaStore.Images.Media.IS_FAVORITE)
            add(MediaStore.Images.Media.GENERATION_ADDED)
            add(MediaStore.Images.Media.GENERATION_MODIFIED)
        }
    }.toTypedArray()

    private fun videoProjection() = buildList {
        add(MediaStore.Video.Media._ID)
        add(MediaStore.Video.Media.DISPLAY_NAME)
        add(MediaStore.Video.Media.SIZE)
        add(MediaStore.Video.Media.WIDTH)
        add(MediaStore.Video.Media.HEIGHT)
        add(MediaStore.Video.Media.DURATION)
        add(MediaStore.Video.Media.DATE_ADDED)
        add(MediaStore.Video.Media.DATE_TAKEN)
        add(MediaStore.Video.Media.DATE_MODIFIED)
        add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        add(MediaStore.Video.Media.MIME_TYPE)
        if (Build.VERSION.SDK_INT >= 29) add(MediaStore.Video.Media.RELATIVE_PATH)
        if (Build.VERSION.SDK_INT >= 30) {
            add(MediaStore.Video.Media.IS_FAVORITE)
            add(MediaStore.Video.Media.GENERATION_ADDED)
            add(MediaStore.Video.Media.GENERATION_MODIFIED)
        }
    }.toTypedArray()

    private fun imageFromCursor(cursor: android.database.Cursor): MediaAsset {
        val id = cursor.long(MediaStore.Images.Media._ID)
        val name = cursor.string(MediaStore.Images.Media.DISPLAY_NAME)
        val bucket = cursor.string(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        val relativePath = if (Build.VERSION.SDK_INT >= 29) cursor.string(MediaStore.Images.Media.RELATIVE_PATH) else ""
        val dateAdded = cursor.long(MediaStore.Images.Media.DATE_ADDED)
        val generationAdded = if (Build.VERSION.SDK_INT >= 30) cursor.long(MediaStore.Images.Media.GENERATION_ADDED) else 0L
        val generationModified = if (Build.VERSION.SDK_INT >= 30) cursor.long(MediaStore.Images.Media.GENERATION_MODIFIED) else 0L
        val kind = if (MediaClassifier.looksLikeScreenshot(name)) MediaKind.SCREENSHOT else MediaKind.PHOTO
        return MediaAsset(
            id = id,
            uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
            kind = kind,
            name = name.ifBlank { "Image $id" },
            width = cursor.int(MediaStore.Images.Media.WIDTH),
            height = cursor.int(MediaStore.Images.Media.HEIGHT),
            duration = 0L,
            size = cursor.long(MediaStore.Images.Media.SIZE),
            createdAt = dateFrom(cursor.long(MediaStore.Images.Media.DATE_TAKEN), dateAdded),
            updatedAt = Date(cursor.long(MediaStore.Images.Media.DATE_MODIFIED) * 1000L),
            dateAdded = dateAdded,
            bucket = bucket,
            pathHint = relativePath,
            mimeType = cursor.string(MediaStore.Images.Media.MIME_TYPE),
            isFavorite = Build.VERSION.SDK_INT >= 30 && cursor.int(MediaStore.Images.Media.IS_FAVORITE) == 1,
            /*
             * 竞品构造 CGAsset 时 duplicateReference 使用的 edited 固定为 false。
             * generation 只用于增量扫描，不能把“数据库记录更新”解释为“用户编辑”。
             */
            isEdited = false,
            generationAdded = generationAdded,
            generationModified = generationModified,
            chatSource = MediaClassifier.chatSource(name, bucket, relativePath)
        )
    }

    private fun videoFromCursor(cursor: android.database.Cursor): MediaAsset {
        val id = cursor.long(MediaStore.Video.Media._ID)
        val name = cursor.string(MediaStore.Video.Media.DISPLAY_NAME)
        val bucket = cursor.string(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        val relativePath = if (Build.VERSION.SDK_INT >= 29) cursor.string(MediaStore.Video.Media.RELATIVE_PATH) else ""
        val dateAdded = cursor.long(MediaStore.Video.Media.DATE_ADDED)
        val generationAdded = if (Build.VERSION.SDK_INT >= 30) cursor.long(MediaStore.Video.Media.GENERATION_ADDED) else 0L
        val generationModified = if (Build.VERSION.SDK_INT >= 30) cursor.long(MediaStore.Video.Media.GENERATION_MODIFIED) else 0L
        val kind = if (MediaClassifier.looksLikeScreenRecording(name)) MediaKind.SCREEN_RECORDING else MediaKind.VIDEO
        return MediaAsset(
            id = id,
            uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
            kind = kind,
            name = name.ifBlank { "Video $id" },
            width = cursor.int(MediaStore.Video.Media.WIDTH),
            height = cursor.int(MediaStore.Video.Media.HEIGHT),
            duration = cursor.long(MediaStore.Video.Media.DURATION),
            size = cursor.long(MediaStore.Video.Media.SIZE),
            createdAt = dateFrom(cursor.long(MediaStore.Video.Media.DATE_TAKEN), dateAdded),
            updatedAt = Date(cursor.long(MediaStore.Video.Media.DATE_MODIFIED) * 1000L),
            dateAdded = dateAdded,
            bucket = bucket,
            pathHint = relativePath,
            mimeType = cursor.string(MediaStore.Video.Media.MIME_TYPE),
            isFavorite = Build.VERSION.SDK_INT >= 30 && cursor.int(MediaStore.Video.Media.IS_FAVORITE) == 1,
            isEdited = false,
            generationAdded = generationAdded,
            generationModified = generationModified,
            chatSource = MediaClassifier.chatSource(name, bucket, relativePath)
        )
    }

    private fun dateFrom(dateTaken: Long, dateAddedSeconds: Long): Date {
        return Date(if (dateTaken > 0L) dateTaken else dateAddedSeconds * 1000L)
    }

    private fun generationSelection(column: String, generationAfter: Long): Pair<String, Array<String>?> {
        return if (Build.VERSION.SDK_INT >= 30 && generationAfter > 0L) {
            " AND $column > ?" to arrayOf(generationAfter.toString())
        } else {
            "" to null
        }
    }
}
