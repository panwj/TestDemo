package com.example.similarscandemo.ui

import android.widget.ImageView
import com.clean.similarscan.api.model.MediaAsset
import com.clean.similarscan.api.SimilarScanImageLoader
import com.clean.similarscan.api.SimilarScanSdk

object ThumbLoader {
    private val loaders = mutableMapOf<Int, BitmapLoader>()
    private var forceRefresh = false

    @Synchronized
    fun loadBitmap(imageView: ImageView, asset: MediaAsset, size: Int) {
        val hashCode = imageView.hashCode()
        val loader = loaders.getOrPut(hashCode) { BitmapLoader(imageView.context.applicationContext) }
        loader.loadBitmap(imageView, asset, size, forceRefresh)
    }

    fun cleanup() {
        loaders.values.forEach { it.recycle() }
        loaders.clear()
    }

    fun setForceRefresh(force: Boolean) {
        forceRefresh = force
        if (force) {
            cleanup()
        }
    }

    fun removeFromCache(asset: MediaAsset, size: Int) {
        val key = "${asset.uri}_$size"
        loaders.values.forEach { it.removeFromCache(key) }
    }
}

class BitmapLoader(private val context: android.content.Context) {
    private val cache = android.util.LruCache<String, android.graphics.Bitmap>(50)
    private val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
    private var imageLoader: SimilarScanImageLoader? = null

    fun loadBitmap(imageView: ImageView, asset: MediaAsset, size: Int, forceRefresh: Boolean = false) {
        val key = "${asset.uri}_$size"

        /*
         * 必须在读取缓存前就更新 tag。RecyclerView 会复用 ImageView，如果新 item
         * 命中缓存但旧异步任务稍后返回，旧任务会依据过期 tag 把封面覆盖回来。
         */
        imageView.tag = key
        if (!forceRefresh) {
            cache.get(key)?.let {
                imageView.setImageBitmap(it)
                return
            }
        } else {
            cache.remove(key)
        }

        executor.execute {
            try {
                val bitmap = loadBitmapSync(asset, size) ?: return@execute
                cache.put(key, bitmap)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (imageView.tag == key) {
                        imageView.setImageBitmap(bitmap)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun loadBitmapSync(asset: MediaAsset, size: Int): android.graphics.Bitmap? {
        return try {
            /*
             * UI 封面必须和系统相册、竞品保持一致，因此这里不再自行抽取“最佳帧”。
             * 统一走 SDK 暴露的图片加载接口，避免 app 层直接依赖 scanner 内部实现。
             */
            val thumb = imageLoader().loadBitmap(asset, size)
            thumb?.let {
                val scale = size.toFloat() / it.width.coerceAtMost(it.height)
                val newWidth = (it.width * scale).toInt()
                val newHeight = (it.height * scale).toInt()
                android.graphics.Bitmap.createScaledBitmap(it, newWidth, newHeight, true)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun recycle() {
        cache.evictAll()
        imageLoader?.close()
        imageLoader = null
    }

    fun removeFromCache(key: String) {
        cache.remove(key)
    }

    private fun imageLoader(): SimilarScanImageLoader {
        return imageLoader ?: SimilarScanSdk.createImageLoader(context).also { imageLoader = it }
    }
}
