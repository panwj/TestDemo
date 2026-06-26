package com.clean.similarscan.api

import android.content.Context
import android.graphics.Bitmap
import com.clean.similarscan.api.model.MediaAsset
import com.clean.similarscan.api.model.ProductCategory
import com.clean.similarscan.api.model.ScanResult
import com.clean.similarscan.api.model.SimilarGroup
import com.clean.similarscan.internal.database.ScanDatabase
import com.clean.similarscan.internal.scanner.ProductCategoryBuilder
import com.clean.similarscan.internal.scanner.SimilarMediaScanner

/**
 * 基于当前 Demo 实现的 SDK 适配器。
 *
 * 该类是 UI/Service 与核心扫描实现之间的隔离层：页面只依赖 SimilarScanClient，
 * 后续把 scanner/database/similarity 迁到独立 module 时，UI 层不需要继续跟随改动。
 */
internal class AndroidSimilarScanClient(context: Context) : SimilarScanClient {
    private val appContext = context.applicationContext
    private val scanner = SimilarMediaScanner(appContext)
    private var database: ScanDatabase? = null

    override fun scan(
        request: SimilarScanRequest,
        observer: SimilarScanObserver
    ): ScanResult {
        return scanner.scan(request.forceFull) { progress ->
            observer.onProgress(progress.toApi())
        }.toApi()
    }

    override fun loadGroups(limit: Int): List<SimilarGroup> {
        return scanner.loadCachedGroups(limit).map { it.toApi() }
    }

    override fun loadProductCategories(limit: Int): List<ProductCategory> {
        return ProductCategoryBuilder.build(scanner.loadCachedGroups(limit)).map { it.toApi() }
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
