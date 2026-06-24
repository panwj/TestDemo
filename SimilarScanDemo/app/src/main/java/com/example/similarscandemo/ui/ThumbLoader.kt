package com.example.similarscandemo.ui

import android.widget.ImageView
import com.example.similarscandemo.model.MediaAsset
import com.example.similarscandemo.scanner.MediaBitmapLoader

object ThumbLoader {
    private val loaders = mutableMapOf<Int, BitmapLoader>()
    private var forceRefresh = false

    @Synchronized
    fun loadBitmap(imageView: ImageView, asset: MediaAsset, size: Int) {
        val hashCode = imageView.hashCode()
        val loader = loaders.getOrPut(hashCode) { BitmapLoader(imageView.context.contentResolver) }
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

class BitmapLoader(private val resolver: android.content.ContentResolver) {
    private val cache = android.util.LruCache<String, android.graphics.Bitmap>(50)
    private val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
    private val mediaBitmapLoader = MediaBitmapLoader(resolver)

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
             * 统一走 MediaBitmapLoader：API 29+ 使用 ContentResolver.loadThumbnail，
             * API 23-28 优先使用 MediaStore 缩略图，失败后才回退文件解码。
             */
            val thumb = mediaBitmapLoader.loadBitmap(asset, size)
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
    }

    fun removeFromCache(key: String) {
        cache.remove(key)
    }
}
