package com.clean.similarscan.api.model

/**
 * SDK 对外扫描阶段。
 */
enum class ScanStage {
    IDLE,
    ENUMERATING,
    FINGERPRINTING,
    MATCHING,
    COMPLETED,
    FAILED
}
