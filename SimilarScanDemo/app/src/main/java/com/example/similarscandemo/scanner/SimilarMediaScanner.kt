package com.example.similarscandemo.scanner

import android.content.Context
import android.os.Build
import android.provider.MediaStore
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
        var processed = 0
        val processedKinds = linkedSetOf<MediaKind>()

        // 每次扫描从当前数据库指纹构建轻量 BK-Tree，确保图片候选按汉明距离完整召回。
        visualIndexes = mutableMapOf(
            MediaKind.PHOTO to buildVisualIndex(MediaKind.PHOTO),
            MediaKind.SCREENSHOT to buildVisualIndex(MediaKind.SCREENSHOT)
        )

        val modeName = if (fullScan) "full" else "incremental"
        progress(ScanProgress(ScanStage.ENUMERATING, 0, database.groupCount(), "Starting $modeName MediaStore scan."))
        repository.forEachMediaBatch(
            batchSize = BATCH_SIZE,
            imageGenerationAfter = imageGenerationAfter,
            videoGenerationAfter = videoGenerationAfter
        ) { batch ->
            progress(
                ScanProgress(
                    stage = ScanStage.FINGERPRINTING,
                    processedCount = processed,
                    discoveredGroupCount = database.groupCount(),
                    message = "Processing batch of ${batch.size} assets."
                )
            )

            batch.forEach { asset ->
                processedKinds += asset.kind
                if (asset.kind == MediaKind.PHOTO || asset.kind == MediaKind.SCREENSHOT) {
                    maxImageGeneration = maxOf(maxImageGeneration, asset.generationModified)
                } else {
                    maxVideoGeneration = maxOf(maxVideoGeneration, asset.generationModified)
                }
                val token = database.upsertAsset(asset, scanToken)
                if (token == null || !database.prepareAssetForRescan(token)) {
                    // 用户正在删除或资源状态已变化，本轮扫描跳过，不覆盖用户操作。
                    return@forEach
                }
                if (asset.kind == MediaKind.VIDEO || asset.kind == MediaKind.SCREEN_RECORDING) {
                    processVideo(token, asset)
                } else {
                    processVisual(token, asset)
                }
                processed++
            }

            progress(
                ScanProgress(
                    stage = ScanStage.MATCHING,
                    processedCount = processed,
                    discoveredGroupCount = database.groupCount(),
                    message = "Scanned $processed assets. Results are being updated."
                )
            )
        }

        if (fullScan && hasFullVisualAccess) {
            database.removeAssetsNotSeenInScan(scanToken)
        }
        /*
         * 扫描过程中先增量写组以便 UI 及时展示；完成阶段再按竞品的“时间排序 +
         * 锚点直接相似”规则确定性重建本轮涉及类型，避免结果受扫描顺序影响。
         */
        database.rebuildSimilarGroups(processedKinds)
        database.cleanupInvalidGroups()
        scanStateStore.save(
            maxImageGeneration,
            maxVideoGeneration,
            mediaStoreVersion,
            completedFullScan = fullScan && hasFullVisualAccess
        )
        val groups = database.loadGroups(MAX_GROUPS_TO_SHOW)
        val elapsed = System.currentTimeMillis() - startedAt
        progress(
            ScanProgress(
                stage = ScanStage.COMPLETED,
                processedCount = processed,
                discoveredGroupCount = groups.size,
                message = "Completed $modeName media scan."
            )
        )
        return ScanResult(
            assetCount = processed,
            groups = groups,
            message = "Finished $modeName scan in ${elapsed}ms using cached fingerprints and candidate matching."
        )
    }

    fun loadBitmap(asset: MediaAsset, thumbSize: Int = 1024) = bitmapLoader.loadBitmap(asset, thumbSize)

    fun loadCachedGroups(limit: Int = MAX_GROUPS_TO_SHOW) = database.loadGroups(limit)

    private fun processVisual(token: AssetScanToken, asset: MediaAsset) {
        val visual = buildVisualFingerprint(asset)
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
            database.findDuplicateReferenceCandidates(token.assetId, asset, visual.hash)
        val contentSha256 = if (duplicateReferenceCandidates.isEmpty()) {
            null
        } else {
            digestCalculator.sha256(asset.uri)
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
        val candidateIds = visualIndexes
            .getValue(asset.kind)
            .query(
                visual.hash.imageHash,
                Threshold.maxCandidateDistance(asset.kind)
            )
        val similarCandidates = database.loadCandidatesByIds(candidateIds, token.assetId).filter { candidate ->
            // Duplicates 和 Similar 互斥，避免首页重复统计同一资源。
            if (candidate.assetId !in duplicateIds &&
                visual.hash.isSimilarTo(candidate.hash, asset.kind)
            ) {
                true
            } else {
                false
            }
        }

        /*
         * 指纹先使用 revision 乐观锁提交。若用户在计算期间发起删除，提交返回 false，
         * 后续分组和 BK-Tree 更新全部放弃，避免被删除资源重新出现。
         */
        val committed = database.markFingerprintDone(
            token,
            visual.hash,
            asset,
            contentSha256,
            visual.qualityScore
        )
        if (!committed) return

        duplicateReferenceCandidates.forEach { candidate ->
            database.linkDuplicateAssets(asset.kind, token.assetId, candidate.assetId)
        }
        similarCandidates.forEach { candidate ->
            database.linkSimilarAssets(asset.kind, token.assetId, candidate.assetId)
        }
        visualIndexes.getValue(asset.kind).add(token.assetId, visual.hash.imageHash)
    }

    private fun processVideo(token: AssetScanToken, asset: MediaAsset) {
        /*
         * 视频不复用图片缩略图流程。竞品通过 MediaMetadataRetriever 抽取 7～13 帧，
         * 对每帧生成组合 Hash，再在两段视频的全部帧之间交叉匹配。
         */
        val fingerprint = videoFingerprintCalculator.calculate(asset)
        if (!fingerprint.isValid()) {
            database.markFingerprintFailed(token)
            return
        }

        val similarCandidates = database.findVideoFingerprintCandidates(token.assetId, asset).filter { candidate ->
            val candidateFingerprint = candidate.videoFingerprint ?: return@filter false
            fingerprint.isSimilarTo(candidateFingerprint)
        }
        if (!database.markVideoFingerprintDone(token, fingerprint, asset, fingerprint.qualityScore)) {
            return
        }
        similarCandidates.forEach { candidate ->
            database.linkSimilarAssets(asset.kind, token.assetId, candidate.assetId)
        }
    }

    private fun buildVisualFingerprint(asset: MediaAsset): VisualFingerprintResult? {
        // 指纹输入走竞品兼容加载；UI 预览仍通过 loadBitmap() 使用真实媒体 URI。
        val bitmap = bitmapLoader.loadFingerprintBitmap(asset) ?: return null
        return try {
            VisualFingerprintResult(
                hash = HashCalculator.buildHash(bitmap),
                qualityScore = MediaQualityAnalyzer.score(bitmap, asset)
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
    }
}

private data class VisualFingerprintResult(
    val hash: CombinedHash,
    val qualityScore: Double
)
