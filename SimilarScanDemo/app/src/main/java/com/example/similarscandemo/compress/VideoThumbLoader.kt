package com.example.similarscandemo.compress

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.widget.ImageView
import com.clean.similarscan.api.SimilarScanImageLoader
import com.clean.similarscan.api.SimilarScanSdk
import com.clean.similarscan.api.model.MediaAsset
import com.clean.similarscan.api.model.MediaKind
import com.clean.videocompress.api.model.CompressVideoAsset
import java.util.Date
import java.util.concurrent.Executors

object VideoThumbLoader {
    private val cache = LruCache<String, Bitmap>(80)
    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val loaders = mutableMapOf<Context, SimilarScanImageLoader>()

    fun load(imageView: ImageView, asset: CompressVideoAsset, size: Int) {
        val key = "${asset.uri}_$size"
        imageView.tag = key
        cache.get(key)?.let {
            imageView.setImageBitmap(it)
            return
        }
        executor.execute {
            val bitmap = loadSync(imageView.context.applicationContext, asset, size) ?: return@execute
            cache.put(key, bitmap)
            mainHandler.post {
                if (imageView.tag == key) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun loadSync(context: Context, asset: CompressVideoAsset, size: Int): Bitmap? {
        return try {
            /*
             * 压缩页视频封面与相似识别列表保持同一套策略。
             * 统一复用 SimilarScanImageLoader，避免压缩页直接走 loadThumbnail/ThumbnailUtils
             * 导致不同页面同一个视频封面不一致。
             */
            imageLoader(context).loadBitmap(asset.toMediaAsset(), size)
        } catch (_: Throwable) {
            null
        }
    }

    private fun imageLoader(context: Context): SimilarScanImageLoader {
        val appContext = context.applicationContext
        return synchronized(loaders) {
            loaders.getOrPut(appContext) { SimilarScanSdk.createImageLoader(appContext) }
        }
    }

    private fun CompressVideoAsset.toMediaAsset(): MediaAsset {
        val created = Date(dateAddedSeconds * 1000L)
        val updated = Date(dateModifiedSeconds * 1000L)
        return MediaAsset(
            id = id,
            uri = uri,
            kind = MediaKind.VIDEO,
            name = displayName,
            width = width,
            height = height,
            duration = durationMs,
            size = sizeBytes,
            createdAt = created,
            updatedAt = updated,
            dateAdded = dateAddedSeconds,
            bucket = "",
            pathHint = "",
            mimeType = "video/*"
        )
    }
}
