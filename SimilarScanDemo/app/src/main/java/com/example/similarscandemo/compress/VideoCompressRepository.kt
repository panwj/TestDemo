package com.example.similarscandemo.compress

import android.content.Context
import com.clean.videocompress.api.VideoCompressSdk
import com.clean.videocompress.api.model.CompressVideoAsset
import com.clean.videocompress.api.model.VideoBucket

/**
 * Demo 层视频压缩数据缓存。
 *
 * Compress 首页和分组详情会连续读取同一批视频。如果每个页面都重新 query MediaStore，
 * 用户会感知到二次加载延迟。这里做短生命周期内存缓存，页面展示更快；
 * 每次回到 Compress 首页仍可通过 forceRefresh 拉取最新媒体库数据。
 */
object VideoCompressRepository {
    @Volatile
    private var cachedVideos: List<CompressVideoAsset>? = null

    @Volatile
    private var cachedBuckets: List<VideoBucket>? = null

    fun loadBuckets(context: Context, forceRefresh: Boolean = false): List<VideoBucket> {
        if (!forceRefresh) {
            cachedBuckets?.let { return it }
        }
        val client = VideoCompressSdk.create(context.applicationContext)
        val videos = client.loadVideos()
        val buckets = client.buildBuckets(videos)
        cachedVideos = videos
        cachedBuckets = buckets
        return buckets
    }

    fun loadBucket(context: Context, key: String): VideoBucket? {
        return loadBuckets(context.applicationContext, forceRefresh = false).firstOrNull { it.key == key }
    }

    fun clear() {
        cachedVideos = null
        cachedBuckets = null
    }
}
