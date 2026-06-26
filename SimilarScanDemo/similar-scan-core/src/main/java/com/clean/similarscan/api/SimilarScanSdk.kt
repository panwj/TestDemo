package com.clean.similarscan.api

import android.content.Context

/**
 * SDK 创建入口。
 *
 * Demo 当前仍在 app module 内调试效果；后续抽成独立 Android Library 时，接入方只需要
 * 依赖 module 并通过 SimilarScanSdk.create(context) 获取扫描能力。
 */
object SimilarScanSdk {
    fun create(context: Context): SimilarScanClient {
        return AndroidSimilarScanClient(context.applicationContext)
    }

    fun createImageLoader(context: Context): SimilarScanImageLoader {
        return AndroidSimilarScanImageLoader(context.applicationContext)
    }
}
