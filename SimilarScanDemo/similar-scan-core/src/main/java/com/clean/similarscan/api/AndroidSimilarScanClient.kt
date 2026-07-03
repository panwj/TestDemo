package com.clean.similarscan.api

import android.content.Context
import android.graphics.Bitmap
import com.clean.similarscan.api.model.MediaAsset
import com.clean.similarscan.api.model.ProductCategory
import com.clean.similarscan.api.model.ProductCategoryType
import com.clean.similarscan.api.model.ScanResult
import com.clean.similarscan.api.model.SimilarGroup
import com.clean.similarscan.internal.database.ScanDatabase
import com.clean.similarscan.internal.scanner.ProductCategoryBuilder
import com.clean.similarscan.internal.scanner.SimilarMediaScanner
import com.clean.similarscan.internal.similarity.VideoFingerprintMode as InternalVideoFingerprintMode

/**
 * Android 平台默认 SDK 适配器。
 *
 * 该类隔离对外 API 与内部 scanner/database/similarity 实现，宿主应用只依赖
 * SimilarScanClient，不直接访问内部包。
 */
internal class AndroidSimilarScanClient(context: Context) : SimilarScanClient {
    private val appContext = context.applicationContext
    private val scanner = SimilarMediaScanner(appContext)
    private var database: ScanDatabase? = null

    override fun scan(
        request: SimilarScanRequest,
        observer: SimilarScanObserver
    ): ScanResult {
        return scanner.scan(
            forceFull = request.forceFull,
            imageFingerprintSize = request.normalizedImageFingerprintSize,
            calculateDuplicateSha256DuringScan = request.calculateDuplicateSha256DuringScan,
            videoFingerprintMode = InternalVideoFingerprintMode.valueOf(request.videoFingerprintMode.name),
            enableMetricsLog = request.enableMetricsLog
        ) { progress ->
            observer.onProgress(progress.toApi())
        }.toApi()
    }

    override fun loadGroups(groupLimit: Int): List<SimilarGroup> {
        return scanner.loadCachedGroups(groupLimit).map { it.toApi() }
    }

    override fun loadProductCategories(
        groupLimit: Int,
        previewAssetLimit: Int
    ): List<ProductCategory> {
        return ProductCategoryBuilder
            .build(scanner.loadCachedGroups(groupLimit, previewAssetLimit))
            .map { it.toApi() }
    }

    override fun loadProductCategory(
        type: ProductCategoryType,
        previewAssetLimit: Int
    ): ProductCategory? {
        val internalType = com.clean.similarscan.internal.model.ProductCategoryType.valueOf(type.name)
        return ProductCategoryBuilder
            .build(scanner.loadCachedGroups(internalType, previewAssetLimit = previewAssetLimit))
            .firstOrNull { it.type.name == type.name }
            ?.toApi()
    }

    override fun loadBitmap(asset: MediaAsset, thumbSize: Int): Bitmap? {
        return scanner.loadBitmap(asset.toInternal(), thumbSize)
    }

    override fun recoverStaleDeletePending() {
        database().recoverStaleDeletePending()
    }

    override fun markDeletePending(uris: Collection<String>): Set<String> {
        return database().markDeletePending(uris)
    }

    override fun finalizeDelete(uris: Collection<String>) {
        database().finalizeDelete(uris)
    }

    override fun restoreDeletePending(uris: Collection<String>) {
        database().restoreDeletePending(uris)
    }

    override fun close() {
        scanner.close()
        database?.close()
        database = null
    }

    private fun database(): ScanDatabase {
        return database ?: ScanDatabase(appContext).also { database = it }
    }
}
