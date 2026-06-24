package com.example.similarscandemo.model

/**
 * 指纹状态用于断点续扫：App 被杀或用户下次进入时，DONE 的资源不需要重新计算。
 */
enum class FingerprintStatus {
    PENDING,
    DONE,
    FAILED,
    SKIPPED
}
