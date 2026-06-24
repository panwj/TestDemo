package com.example.similarscandemo.model

/**
 * 媒体资源在本地数据库中的生命周期状态。
 *
 * ACTIVE：可被扫描、展示和选择。
 * DELETE_PENDING：用户已发起系统删除确认；扫描线程必须跳过该资源。
 * DELETED：系统已确认删除，本地关联数据即将或已经清理。
 * UNAVAILABLE：资源暂时不可读取，保留记录等待后续增量扫描修复。
 */
enum class MediaAssetState {
    ACTIVE,
    DELETE_PENDING,
    DELETED,
    UNAVAILABLE
}
