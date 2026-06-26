package com.clean.similarscan.api

import android.graphics.Bitmap
import com.clean.similarscan.api.model.MediaAsset
import java.io.Closeable

/**
 * SDK 对外缩略图加载接口。
 *
 * 它只负责 UI 预览图，不参与扫描指纹计算。单独暴露该接口后，接入方无需直接依赖
 * scanner.MediaBitmapLoader 这类内部实现，后续替换缓存策略或封面策略不会影响 app 层。
 */
interface SimilarScanImageLoader : Closeable {
    fun loadBitmap(asset: MediaAsset, thumbSize: Int = 1024): Bitmap?

    override fun close() = Unit
}
