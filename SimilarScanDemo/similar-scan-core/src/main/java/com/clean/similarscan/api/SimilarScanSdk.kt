package com.clean.similarscan.api

import android.content.Context

/**
 * SDK 创建入口。
 *
 * 接入方通过 SimilarScanSdk.create(context) 获取扫描能力，通过 createImageLoader(context)
 * 获取与扫描链路一致的媒体预览加载能力。
 */
object SimilarScanSdk {
    fun create(context: Context): SimilarScanClient {
        return AndroidSimilarScanClient(context.applicationContext)
    }

    fun createImageLoader(context: Context): SimilarScanImageLoader {
        return AndroidSimilarScanImageLoader(context.applicationContext)
    }
}
