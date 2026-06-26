package com.clean.similarscan.api

import com.clean.similarscan.api.model.ScanProgress

/**
 * SDK 扫描进度监听。
 *
 * 这里保持普通回调形式，避免核心扫描能力依赖 Activity、Service、广播或协程。
 * Demo 的 MediaScanService 负责把该回调转换成前台通知和页面广播。
 */
fun interface SimilarScanObserver {
    fun onProgress(progress: ScanProgress)
}
