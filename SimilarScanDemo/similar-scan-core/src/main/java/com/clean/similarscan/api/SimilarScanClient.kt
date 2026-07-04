package com.clean.similarscan.api

import android.graphics.Bitmap
import com.clean.similarscan.api.model.MediaAsset
import com.clean.similarscan.api.model.ProductCategory
import com.clean.similarscan.api.model.ProductCategoryType
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
    fun loadGroups(groupLimit: Int = Int.MAX_VALUE): List<SimilarGroup>

    /**
     * 查询产品分类结果，宿主 UI 优先使用该接口，不直接拼装数据库分组。
     *
     * groupLimit 只限制底层相似/相同分组数量，不限制产品分类数量，也不限制每组资源数量。
     * previewAssetLimit 只限制每个分组随结果返回的预览资源数量，不影响 totalAssetCount/totalSizeBytes。
     * 首页可以传入较小值提升渲染性能；详情页需要完整资源时保留默认值。
     */
    fun loadProductCategories(
        groupLimit: Int = Int.MAX_VALUE,
        previewAssetLimit: Int = Int.MAX_VALUE
    ): List<ProductCategory>

    /** 查询单个产品分类，详情页优先使用该接口，避免为了一个分类加载全部分类资源。 */
    fun loadProductCategory(
        type: ProductCategoryType,
        previewAssetLimit: Int = Int.MAX_VALUE
    ): ProductCategory?

    /**
     * 分页读取非分组产品分类下的资源。
     *
     * 适用于 Other Screenshots、Other Videos、Other 等平铺列表详情页。
     * Similar/Duplicate 这类分组详情请使用 loadSimilarGroupAssets()。如果误传 grouped=true
     * 的分类类型，SDK 会返回空列表而不是抛异常，调用方应根据 ProductCategoryType.grouped
     * 选择正确分页入口。
     */
    fun loadProductCategoryAssets(
        type: ProductCategoryType,
        offset: Int,
        limit: Int
    ): List<MediaAsset>

    /** 分页读取某个相似/相同分组下的资源，用于分组详情或大图预览按需加载。 */
    fun loadSimilarGroupAssets(
        groupId: Long,
        offset: Int,
        limit: Int
    ): List<MediaAsset>

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
