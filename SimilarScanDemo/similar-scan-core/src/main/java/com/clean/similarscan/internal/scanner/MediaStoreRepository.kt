package com.clean.similarscan.internal.scanner

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
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
 *
 * 该类只做“资源枚举和基础字段归一化”，不计算指纹、不访问数据库、不做相似判断。
 * 上层扫描器根据这里产出的 MediaAsset 决定是否复用旧指纹或提交计算任务。
 */
class MediaStoreRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    /**
     * 轻量估算本轮扫描会访问的媒体数量。
     *
     * 这里只读取 Cursor.count，不构建 MediaAsset；用于进度展示和阶段性分组发布的自适应
     * 策略。即使部分 ROM 的 count 不准确，也只影响 UI 节流，不影响扫描结果。
     */
    fun estimateMediaCount(
        imageGenerationAfter: Long = 0L,
        videoGenerationAfter: Long = 0L
    ): Int {
        var count = 0
        if (SimilarScanPermissionChecker.canReadImages(appContext)) {
            count += countMedia(
                uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                idColumn = MediaStore.Images.Media._ID,
                sizeColumn = MediaStore.Images.Media.SIZE,
                generationColumn = MediaStore.Images.Media.GENERATION_MODIFIED,
                generationAfter = imageGenerationAfter
            )
        }
        if (SimilarScanPermissionChecker.canReadVideos(appContext)) {
            count += countMedia(
                uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                idColumn = MediaStore.Video.Media._ID,
                sizeColumn = MediaStore.Video.Media.SIZE,
                generationColumn = MediaStore.Video.Media.GENERATION_MODIFIED,
                generationAfter = videoGenerationAfter
            )
        }
        return count
    }

    /**
     * 按批枚举当前授权范围内的图片和视频资源。
     *
     * generationAfter 参数只在 API 30+ 且大于 0 时生效；传入 0 表示全量枚举。
     * 当图片和视频权限都可用时，采用交错批次输出，避免大量图片把视频任务完全排到最后。
     */
    fun forEachMediaBatch(
        batchSize: Int,
        imageGenerationAfter: Long = 0L,
        videoGenerationAfter: Long = 0L,
        onBatch: (List<MediaAsset>) -> Unit
    ) {
        val canReadImages = SimilarScanPermissionChecker.canReadImages(appContext)
        val canReadVideos = SimilarScanPermissionChecker.canReadVideos(appContext)
        if (canReadImages && canReadVideos) {
            /*
             * 不能静默吞掉 MediaStore 查询异常。全量扫描若只成功枚举一部分资源，
             * 后续“清理未出现记录”会把缓存中的合法结果误判为已删除。
             * 异常直接抛给前台服务，扫描游标也不会提前推进。
             */
            forEachInterleavedMediaBatch(batchSize, imageGenerationAfter, videoGenerationAfter, onBatch)
            return
        }
        if (canReadImages) {
            forEachImageBatch(batchSize, imageGenerationAfter, onBatch)
        }
        if (canReadVideos) {
            forEachVideoBatch(batchSize, videoGenerationAfter, onBatch)
        }
    }

    /**
     * 图片、视频双 Cursor 交替输出。
     *
     * 每次最多输出一个图片批次和一个视频批次，让图片线程池和视频线程池都能尽早开始工作。
     */
    private fun forEachInterleavedMediaBatch(
        batchSize: Int,
        imageGenerationAfter: Long,
        videoGenerationAfter: Long,
        onBatch: (List<MediaAsset>) -> Unit
    ) {
        val imageGenerationSelection = generationSelection(
            MediaStore.Images.Media.GENERATION_MODIFIED,
            imageGenerationAfter
        )
        val videoGenerationSelection = generationSelection(
            MediaStore.Video.Media.GENERATION_MODIFIED,
            videoGenerationAfter
        )
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProjection(),
            "${MediaStore.Images.Media.SIZE} > 0${imageGenerationSelection.first}",
            imageGenerationSelection.second,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        ).useCursor { imageCursor ->
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection(),
                "${MediaStore.Video.Media.SIZE} > 0${videoGenerationSelection.first}",
                videoGenerationSelection.second,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            ).useCursor { videoCursor ->
                var imageHasNext = true
                var videoHasNext = true
                while (imageHasNext || videoHasNext) {
                    if (imageHasNext) {
                        imageHasNext = emitBatch(imageCursor, batchSize, ::imageFromCursor, onBatch)
                    }
                    if (videoHasNext) {
                        videoHasNext = emitBatch(videoCursor, batchSize, ::videoFromCursor, onBatch)
                    }
                }
            }
        }
    }

    /**
     * 从指定 Cursor 中取出一个批次。
     *
     * 返回 true 表示本次读满 batchSize，Cursor 后面可能还有数据；返回 false 表示已经读到末尾。
     */
    private fun emitBatch(
        cursor: Cursor,
        batchSize: Int,
        mapper: (Cursor) -> MediaAsset,
        onBatch: (List<MediaAsset>) -> Unit
    ): Boolean {
        val batch = ArrayList<MediaAsset>(batchSize)
        while (batch.size < batchSize && cursor.moveToNext()) {
            batch += mapper(cursor)
        }
        if (batch.isNotEmpty()) {
            onBatch(batch)
        }
        return batch.size == batchSize
    }

    private fun countMedia(
        uri: Uri,
        idColumn: String,
        sizeColumn: String,
        generationColumn: String,
        generationAfter: Long
    ): Int {
        val generationSelection = generationSelection(generationColumn, generationAfter)
        return resolver.query(
            uri,
            arrayOf(idColumn),
            "$sizeColumn > 0${generationSelection.first}",
            generationSelection.second,
            null
        ).useCursor { cursor ->
            cursor.count
        }
    }

    /**
     * 只枚举图片资源。
     *
     * 用于仅图片授权，或者宿主产品只想处理图片集合的场景。
     */
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

    /**
     * 只枚举视频资源。
     *
     * 用于仅视频授权，或者宿主产品只想处理视频集合的场景。
     */
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

    /**
     * 查询最近的图片资源。
     *
     * 主要用于诊断、调试或非扫描场景。主扫描链路应使用 [forEachMediaBatch]，避免一次性
     * 把大量资源加载到内存。
     */
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

    /**
     * 查询最近的视频资源。
     *
     * 主要用于诊断、调试或非扫描场景。主扫描链路应使用 [forEachMediaBatch]。
     */
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

    /**
     * 图片查询字段。
     *
     * 高版本字段按 API 分支加入，避免低版本设备查询不存在的列。
     */
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

    /**
     * 视频查询字段。
     */
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

    /**
     * 将图片 Cursor 当前行转换成 SDK 内部统一资产模型。
     */
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
             * MediaStore generation 只用于增量扫描，不能把“数据库记录更新”解释为
             * “用户编辑”。编辑状态后续如需接入，应使用稳定来源单独判断。
             */
            isEdited = false,
            generationAdded = generationAdded,
            generationModified = generationModified,
            chatSource = MediaClassifier.chatSource(name, bucket, relativePath)
        )
    }

    /**
     * 将视频 Cursor 当前行转换成 SDK 内部统一资产模型。
     */
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

    /**
     * 生成媒体时间。
     *
     * 优先使用拍摄时间；拍摄时间缺失时退回入库时间，保证排序和分组都有稳定时间源。
     */
    private fun dateFrom(dateTaken: Long, dateAddedSeconds: Long): Date {
        return Date(if (dateTaken > 0L) dateTaken else dateAddedSeconds * 1000L)
    }

    /**
     * 生成 API 30+ 增量扫描查询条件。
     *
     * 低版本或 generationAfter 为 0 时返回空条件，表示全量读取当前授权范围内资源。
     */
    private fun generationSelection(column: String, generationAfter: Long): Pair<String, Array<String>?> {
        return if (Build.VERSION.SDK_INT >= 30 && generationAfter > 0L) {
            " AND $column > ?" to arrayOf(generationAfter.toString())
        } else {
            "" to null
        }
    }
}
