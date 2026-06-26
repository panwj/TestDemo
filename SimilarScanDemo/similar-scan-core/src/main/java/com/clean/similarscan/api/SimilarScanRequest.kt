package com.clean.similarscan.api

/**
 * SDK 对外扫描请求。
 *
 * 当前 Demo 只需要区分普通增量扫描和强制全量扫描。后续抽成独立 module 时，可以在
 * 这里继续补充扫描范围、并发策略、是否计算 SHA-256、是否延迟质量分等配置项。
 */
data class SimilarScanRequest(
    val forceFull: Boolean = false
)
