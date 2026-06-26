package com.clean.similarscan.api

import android.graphics.Bitmap
import com.clean.similarscan.api.model.MediaAsset
import com.clean.similarscan.api.model.ProductCategory
import com.clean.similarscan.api.model.ScanResult
import com.clean.similarscan.api.model.SimilarGroup
import java.io.Closeable

/**
 * 图库相似扫描能力的对外门面。
 *
 * 该接口只暴露 api.model 下的 DTO，不暴露数据库实体、指纹对象或内部分组实现。
 */
interface SimilarScanClient : Closeable {
    /** 执行同步扫描。调用方应放到后台线程或 Service 中执行。 */
    fun scan(
        request: SimilarScanRequest = SimilarScanRequest(),
        observer: SimilarScanObserver = SimilarScanObserver {}
    ): ScanResult

    /** 查询底层相似/相同分组，主要用于诊断或内部适配。 */
    fun loadGroups(limit: Int = Int.MAX_VALUE): List<SimilarGroup>

    /** 查询产品首页分类结果，Demo UI 优先使用该接口，不直接拼装数据库分组。 */
    fun loadProductCategories(limit: Int = Int.MAX_VALUE): List<ProductCategory>

    /** 为预览/列表加载媒体缩略图；扫描指纹 Bitmap 加载仍在核心扫描内部完成。 */
    fun loadBitmap(asset: MediaAsset, thumbSize: Int = 1024): Bitmap?

    /** 恢复上次进程被杀导致悬挂的删除中状态。 */
    fun recoverStaleDeletePending()

    /** 系统删除确认前标记资源删除中，避免后台扫描把它们重新写回结果。 */
    fun markDeletePending(uris: Collection<String>): Set<String>

    /** 系统确认删除后提交结果并清理本地扫描缓存。 */
    fun finalizeDelete(uris: Collection<String>)

    /** 用户取消系统删除时恢复资源，等待后续增量扫描重新校验。 */
    fun restoreDeletePending(uris: Collection<String>)
}
