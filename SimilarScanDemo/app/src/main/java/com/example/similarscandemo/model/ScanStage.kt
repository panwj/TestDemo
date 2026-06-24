package com.example.similarscandemo.model

/**
 * 产品级扫描拆成多个阶段，UI 可以按阶段展示更准确的进度。
 */
enum class ScanStage {
    IDLE,
    ENUMERATING,
    FINGERPRINTING,
    MATCHING,
    COMPLETED,
    FAILED
}
