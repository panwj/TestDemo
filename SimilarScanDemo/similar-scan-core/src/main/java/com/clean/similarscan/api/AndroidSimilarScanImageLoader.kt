package com.clean.similarscan.api

import android.content.Context
import android.graphics.Bitmap
import com.clean.similarscan.api.model.MediaAsset
import com.clean.similarscan.internal.scanner.MediaBitmapLoader

/**
 * Android MediaStore 缩略图加载适配器。
 *
 * 与扫描 SDK 复用同一套 MediaBitmapLoader 策略：图片优先系统 loadThumbnail，视频封面
 * 与系统相册展示保持一致。这里不创建 SimilarMediaScanner，因此列表加载不会额外打开数据库。
 */
internal class AndroidSimilarScanImageLoader(context: Context) : SimilarScanImageLoader {
    private val loader = MediaBitmapLoader(context.applicationContext.contentResolver)

    override fun loadBitmap(asset: MediaAsset, thumbSize: Int): Bitmap? {
        return loader.loadBitmap(asset.toInternal(), thumbSize)
    }
}
