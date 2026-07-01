package com.clean.videocompress.api

import com.clean.videocompress.api.model.CompressVideoAsset
import com.clean.videocompress.api.model.VideoBucket
import com.clean.videocompress.api.model.VideoCompressRequest

/**
 * 视频压缩能力对外门面。
 */
interface VideoCompressClient {
    /**
     * 从系统媒体库读取可压缩视频，并按时间倒序返回。
     */
    fun loadVideos(): List<CompressVideoAsset>

    /**
     * 根据配置中的分桶规则生成压缩首页分类。
     */
    fun buildBuckets(videos: List<CompressVideoAsset>): List<VideoBucket>

    /**
     * 执行一次视频压缩。返回的任务可用于取消压缩。
     */
    fun compress(request: VideoCompressRequest, observer: VideoCompressObserver): VideoCompressTask
}
