package com.example.similarscandemo.scanner

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.example.similarscandemo.database.ScanDatabase
import com.example.similarscandemo.database.AssetScanToken
import com.example.similarscandemo.model.MediaAsset
import com.example.similarscandemo.model.MediaKind
import com.example.similarscandemo.model.ScanProgress
import com.example.similarscandemo.model.ScanResult
import com.example.similarscandemo.model.ScanStage
import com.example.similarscandemo.permission.MediaPermissionHelper
import com.example.similarscandemo.similarity.CombinedHash
import com.example.similarscandemo.similarity.ContentDigestCalculator
import com.example.similarscandemo.similarity.HashCalculator
import com.example.similarscandemo.similarity.HammingBkTree
import com.example.similarscandemo.similarity.MediaQualityAnalyzer
import com.example.similarscandemo.similarity.Threshold
import com.example.similarscandemo.similarity.VideoFingerprintCalculator
import java.util.UUID

/**
 * 产品级扫描编排器。
 *
 * 关键点：
 * 1. MediaStore 按批枚举，不一次性持有全部资源。
 * 2. 指纹和相似组落库，支持断点续扫与下次快速展示。
 * 3. 图片使用 BK-Tree 召回候选，完成阶段按竞品锚点规则确定性重建分组。
 * 4. 每批结束回调 UI，让结果边扫边展示。
 */
class SimilarMediaScanner(context: Context) {
    private val appContext = context.applicationContext
    private val repository = MediaStoreRepository(appContext)
    private val bitmapLoader = MediaBitmapLoader(context.contentResolver)
    private val digestCalculator = ContentDigestCalculator(context.contentResolver)
    private val videoFingerprintCalculator = VideoFingerprintCalculator(appContext)
    private val database = ScanDatabase(appContext)
    private val scanStateStore = ScanStateStore(appContext)
    private var visualIndexes: MutableMap<MediaKind, HammingBkTree> = mutableMapOf()

    fun scan(
        forceFull: Boolean = false,
        progress: (ScanProgress) -> Unit = {}
    ): ScanResult {
        val startedAt = System.currentTimeMillis()
        val scanToken = UUID.randomUUID().toString()
        val checkpoint = scanStateStore.checkpoint()
        val hasFullVisualAccess = MediaPermissionHelper.hasFullVisualAccess(appContext)
        val mediaStoreVersion = if (Build.VERSION.SDK_INT >= 30) {
            MediaStore.getVersion(appContext)
        } else {
            "legacy"
        }
        val fullScan = forceFull ||
            database.assetCount() == 0 ||
            !hasFullVisualAccess ||
            scanStateStore.shouldRunFullScan(mediaStoreVersion)
        val imageGenerationAfter = if (fullScan) 0L else checkpoint.imageGeneration
        val videoGenerationAfter = if (fullScan) 0L else checkpoint.videoGeneration
        var maxImageGeneration = checkpoint.imageGeneration
        var maxVideoGeneration = checkpoint.videoGeneration
        var visited = 0
        var fingerprinted = 0
        var skippedUnchanged = 0
        val changedKinds = linkedSetOf<MediaKind>()
        val metrics = ScanMetrics()

        // 每次扫描从当前数据库指纹构建轻量 BK-Tree，确保图片候选按汉明距离完整召回。
        visualIndexes = mutableMapOf(
            MediaKind.PHOTO to buildVisualIndex(MediaKind.PHOTO),
            MediaKind.SCREENSHOT to buildVisualIndex(MediaKind.SCREENSHOT)
        )

        val modeName = if (fullScan) "full" else "incremental"
        progress(ScanProgress(ScanStage.ENUMERATING, 0, database.groupCount(), "Starting $modeName MediaStore scan."))
        metrics.measure("enumerate_and_fingerprint_total") {
            repository.forEachMediaBatch(
                batchSize = BATCH_SIZE,
                imageGenerationAfter = imageGenerationAfter,
                videoGenerationAfter = videoGenerationAfter
            ) { batch ->
                progress(
                    ScanProgress(
                        stage = ScanStage.FINGERPRINTING,
                        processedCount = visited,
                        discoveredGroupCount = database.groupCount(),
                        message = "Processing batch of ${batch.size} assets."
                    )
                )

                batch.forEach { asset ->
                    visited++
                    if (asset.kind == MediaKind.PHOTO || asset.kind == MediaKind.SCREENSHOT) {
                        maxImageGeneration = maxOf(maxImageGeneration, asset.generationModified)
                    } else {
                        maxVideoGeneration = maxOf(maxVideoGeneration, asset.generationModified)
                    }
                    val token = metrics.measure("upsert_asset") {
                        database.upsertAsset(asset, scanToken)
                    }
                    if (token == null) {
                        // 用户正在删除或资源状态已变化，本轮扫描跳过，不覆盖用户操作。
                        return@forEach
                    }

                    /*
                     * 全量扫描现在承担“校验媒体库是否完整”的职责，但不再等同于“重算全部指纹”。
                     * 如果 MediaStore 关键元数据和算法版本都没变，旧 fingerprint 与旧分组仍然有效，
                     * 这里只更新 last_seen_scan 后直接跳过，扫描 9k/10w 资源时收益最大。
                     */
                    if (!token.needsFingerprint) {
                        skippedUnchanged++
                        return@forEach
                    }

                    changedKinds += asset.kind
                    if (!metrics.measure("prepare_asset_for_rescan") { database.prepareAssetForRescan(token) }) {
                        // 用户正在删除或资源状态已变化，本轮扫描跳过，不覆盖用户操作。
                        return@forEach
                    }
                    if (asset.kind == MediaKind.VIDEO || asset.kind == MediaKind.SCREEN_RECORDING) {
                        metrics.measure("process_video") { processVideo(token, asset, metrics) }
                    } else {
                        metrics.measure("process_visual") { processVisual(token, asset, metrics) }
                    }
                    fingerprinted++
                }

                progress(
                    ScanProgress(
                        stage = ScanStage.MATCHING,
                        processedCount = visited,
                        discoveredGroupCount = database.groupCount(),
                        message = "Scanned $visited assets. $fingerprinted updated, $skippedUnchanged reused."
                    )
                )
            }
        }

        if (fullScan && hasFullVisualAccess) {
            metrics.measure("remove_assets_not_seen") { database.removeAssetsNotSeenInScan(scanToken) }
        }
        /*
         * 扫描过程中先增量写组以便 UI 及时展示；完成阶段再按竞品的“时间排序 +
         * 锚点直接相似”规则确定性重建本轮涉及类型，避免结果受扫描顺序影响。
         */
        metrics.measure("rebuild_similar_groups") { database.rebuildSimilarGroups(changedKinds) }
        metrics.measure("cleanup_invalid_groups") { database.cleanupInvalidGroups() }
        scanStateStore.save(
            maxImageGeneration,
            maxVideoGeneration,
            mediaStoreVersion,
            completedFullScan = fullScan && hasFullVisualAccess
        )
        val groups = metrics.measure("load_groups") { database.loadGroups(MAX_GROUPS_TO_SHOW) }
        val elapsed = System.currentTimeMillis() - startedAt
        metrics.logSummary(
            modeName = modeName,
            visited = visited,
            fingerprinted = fingerprinted,
            skippedUnchanged = skippedUnchanged,
            elapsed = elapsed
        )
        progress(
            ScanProgress(
                stage = ScanStage.COMPLETED,
                processedCount = visited,
                discoveredGroupCount = groups.size,
                message = "Completed $modeName media scan."
            )
        )
        return ScanResult(
            assetCount = visited,
            groups = groups,
            message = "Finished $modeName scan in ${elapsed}ms. Updated $fingerprinted, reused $skippedUnchanged cached fingerprints."
        )
    }

    fun loadBitmap(asset: MediaAsset, thumbSize: Int = 1024) = bitmapLoader.loadBitmap(asset, thumbSize)

    fun loadCachedGroups(limit: Int = MAX_GROUPS_TO_SHOW) = database.loadGroups(limit)

    /**
     * SimilarMediaScanner 持有 SQLiteOpenHelper。前台服务每次扫描都会创建 scanner，
     * 扫描结束后必须显式关闭数据库连接，避免系统在 GC 时报告 SQLiteConnection 泄漏。
     */
    fun close() {
        database.close()
    }

    private fun processVisual(token: AssetScanToken, asset: MediaAsset, metrics: ScanMetrics) {
        val visual = metrics.measure("build_visual_fingerprint") { buildVisualFingerprint(asset, metrics) }
        if (visual == null || !visual.hash.isValid()) {
            database.markFingerprintFailed(token)
            return
        }
        /*
         * 竞品的 duplicateReference 由媒体类型、宽高、感知 hash、编辑状态和文件大小组成。
         * SHA-256 继续保留为验证证据，但不再作为进入 Duplicates 的唯一条件，否则经过
         * 元数据重写、重新保存但视觉内容一致的图片会被 Demo 漏掉。
         */
        val duplicateReferenceCandidates =
            metrics.measure("find_duplicate_candidates") {
                database.findDuplicateReferenceCandidates(token.assetId, asset, visual.hash)
            }
        val contentSha256 = if (duplicateReferenceCandidates.isEmpty()) {
            null
        } else {
            metrics.measure("sha256_current_asset") { digestCalculator.sha256(asset.uri) }
        }
        asset.contentSha256 = contentSha256
        asset.qualityScore = visual.qualityScore

        duplicateReferenceCandidates.forEach { candidate ->
            /*
             * 对候选文件补算 SHA-256，数据会缓存到 fingerprint 表。
             * SHA 不同并不阻止竞品式重复归类，但可在后续诊断页面区分“字节完全相同”
             * 与“竞品组合引用相同”。
             */
            if (contentSha256 != null && database.contentShaForAsset(candidate.assetId) == null) {
                digestCalculator.sha256(candidate.asset.uri)?.also { calculated ->
                    database.updateContentSha(candidate.assetId, calculated)
                }
            }
        }

        val duplicateIds = duplicateReferenceCandidates.mapTo(mutableSetOf()) { it.assetId }
        val candidateIds = metrics.measure("bk_tree_visual_query") {
            visualIndexes
                .getValue(asset.kind)
                .query(
                    visual.hash.imageHash,
                    Threshold.maxCandidateDistance(asset.kind)
                )
        }
        val similarCandidates = metrics.measure("load_and_filter_visual_candidates") {
            database.loadCandidatesByIds(candidateIds, token.assetId).filter { candidate ->
            // Duplicates 和 Similar 互斥，避免首页重复统计同一资源。
                if (candidate.assetId !in duplicateIds &&
                    visual.hash.isSimilarTo(candidate.hash, asset.kind)
                ) {
                    true
                } else {
                    false
                }
            }
        }

        /*
         * 指纹先使用 revision 乐观锁提交。若用户在计算期间发起删除，提交返回 false，
         * 后续分组和 BK-Tree 更新全部放弃，避免被删除资源重新出现。
         */
        val committed = metrics.measure("mark_visual_fingerprint_done") {
            database.markFingerprintDone(
                token,
                visual.hash,
                asset,
                contentSha256,
                visual.qualityScore
            )
        }
        if (!committed) return

        duplicateReferenceCandidates.forEach { candidate ->
            database.linkDuplicateAssets(asset.kind, token.assetId, candidate.assetId)
        }
        similarCandidates.forEach { candidate ->
            database.linkSimilarAssets(asset.kind, token.assetId, candidate.assetId)
        }
        visualIndexes.getValue(asset.kind).add(token.assetId, visual.hash.imageHash)
    }

    private fun processVideo(token: AssetScanToken, asset: MediaAsset, metrics: ScanMetrics) {
        /*
         * 视频不复用图片缩略图流程。两阶段 quick/full 在真机数据上会漏掉相似视频，
         * 并且候选补算完整指纹会抵消节省的抽帧成本；这里恢复为单阶段 7 帧稳定识别。
         */
        val fingerprint = metrics.measure("calculate_video_fingerprint") {
            videoFingerprintCalculator.calculate(asset)
        }
        if (!fingerprint.isValid()) {
            metrics.increment("video_fingerprint_failed")
            database.markFingerprintFailed(token)
            return
        }
        if (fingerprint.frames.size == 1) {
            metrics.increment("video_system_thumbnail_fingerprint")
        } else {
            metrics.increment("video_mmr_fingerprint")
        }

        val similarCandidates = metrics.measure("load_and_filter_video_candidates") {
            database.findVideoFingerprintCandidates(token.assetId, asset).filter { candidate ->
                val candidateFingerprint = candidate.videoFingerprint ?: return@filter false
                fingerprint.isSimilarTo(candidateFingerprint, asset.kind)
            }
        }
        if (!metrics.measure("mark_video_fingerprint_done") {
                database.markVideoFingerprintDone(token, fingerprint, asset, fingerprint.qualityScore)
            }
        ) {
            return
        }
        similarCandidates.forEach { candidate ->
            database.linkSimilarAssets(asset.kind, token.assetId, candidate.assetId)
        }
    }

    private fun buildVisualFingerprint(asset: MediaAsset, metrics: ScanMetrics): VisualFingerprintResult? {
        // 指纹输入走竞品兼容加载；UI 预览仍通过 loadBitmap() 使用真实媒体 URI。
        val fingerprintBitmap = metrics.measure("load_fingerprint_bitmap") {
            bitmapLoader.loadFingerprintBitmapWithSource(asset, FINGERPRINT_BITMAP_SIZE)
        } ?: return null
        metrics.increment(
            when (fingerprintBitmap.source) {
                FingerprintBitmapSource.SYSTEM_MEDIASTORE_THUMBNAIL -> "image_system_mediastore_thumbnail"
                FingerprintBitmapSource.SYSTEM_LOAD_THUMBNAIL -> "image_system_load_thumbnail"
                FingerprintBitmapSource.DECODE_URI -> "image_decode_uri"
                FingerprintBitmapSource.DECODE_FILE -> "image_decode_file"
            }
        )
        val bitmap = fingerprintBitmap.bitmap
        return try {
            VisualFingerprintResult(
                hash = metrics.measure("calculate_image_hash") { HashCalculator.buildHash(bitmap) },
                qualityScore = metrics.measure("calculate_quality_score") {
                    MediaQualityAnalyzer.score(bitmap, asset)
                }
            )
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 从 SQLite 中恢复一种图片类型的 BK-Tree。
     *
     * 已缓存资源立即参与本轮增量匹配；新资源完成指纹后也会实时加入该索引。
     */
    private fun buildVisualIndex(kind: MediaKind): HammingBkTree {
        return HammingBkTree().also { tree ->
            database.loadHashIndex(kind).forEach { entry ->
                tree.add(entry.assetId, entry.imageHash)
            }
        }
    }

    companion object {
        private const val BATCH_SIZE = 500
        private const val MAX_GROUPS_TO_SHOW = Int.MAX_VALUE
        /*
         * dHash 最终只需要 9x8 采样，colorHash 是 8x3 颜色直方图；扫描阶段使用 512
         * 缩略图可以显著降低 MediaProvider/Bitmap 解码成本。UI 预览仍使用 1024。
         */
        private const val FINGERPRINT_BITMAP_SIZE = 512
    }
}

private data class VisualFingerprintResult(
    val hash: CombinedHash,
    val qualityScore: Double
)

private class ScanMetrics {
    private val totals = linkedMapOf<String, Long>()
    private val counts = linkedMapOf<String, Int>()

    fun <T> measure(name: String, block: () -> T): T {
        val startedAt = System.nanoTime()
        return try {
            block()
        } finally {
            add(name, (System.nanoTime() - startedAt) / 1_000_000L)
        }
    }

    fun logSummary(
        modeName: String,
        visited: Int,
        fingerprinted: Int,
        skippedUnchanged: Int,
        elapsed: Long
    ) {
        Log.d(
            TAG,
            "scan=$modeName elapsed=${elapsed}ms visited=$visited fingerprinted=$fingerprinted reused=$skippedUnchanged"
        )
        totals.forEach { (name, duration) ->
            Log.d(TAG, "metric.$name=${duration}ms")
        }
        counts.forEach { (name, count) ->
            Log.d(TAG, "count.$name=$count")
        }
    }

    private fun add(name: String, durationMs: Long) {
        totals[name] = (totals[name] ?: 0L) + durationMs
    }

    fun increment(name: String) {
        counts[name] = (counts[name] ?: 0) + 1
    }

    companion object {
        private const val TAG = "SimilarScanMetrics"
    }
}
