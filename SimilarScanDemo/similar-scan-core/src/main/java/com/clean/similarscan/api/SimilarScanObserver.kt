package com.clean.similarscan.api

import com.clean.similarscan.api.model.ScanProgress

/**
 * SDK 扫描进度监听。
 *
 * 这里保持普通回调形式，避免核心扫描能力依赖 Activity、Service、广播或协程。
 * 宿主应用可按自身架构把该回调转换成前台通知、页面状态或持久化任务进度。
 */
fun interface SimilarScanObserver {
    fun onProgress(progress: ScanProgress)
}
