package com.clean.similarscan.internal.scanner

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.clean.similarscan.internal.database.ScanDatabase
import com.clean.similarscan.internal.database.AssetScanToken
import com.clean.similarscan.internal.model.MediaAsset
import com.clean.similarscan.internal.model.MediaKind
import com.clean.similarscan.internal.model.ScanProgress
import com.clean.similarscan.internal.model.ScanResult
import com.clean.similarscan.internal.model.ScanStage
import com.clean.similarscan.permission.SimilarScanPermissionChecker
import com.clean.similarscan.internal.similarity.CombinedHash
import com.clean.similarscan.internal.similarity.ContentDigestCalculator
import com.clean.similarscan.internal.similarity.HashCalculator
import com.clean.similarscan.internal.similarity.HammingBkTree
import com.clean.similarscan.internal.similarity.MediaQualityAnalyzer
import com.clean.similarscan.internal.similarity.Threshold
import com.clean.similarscan.internal.similarity.VideoFingerprint
import com.clean.similarscan.internal.similarity.VideoFingerprintCalculator
import com.clean.similarscan.internal.similarity.VideoFingerprintMode
import com.clean.similarscan.internal.similarity.VideoFingerprintSource
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
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
internal class SimilarMediaScanner(context: Context) {
    private val appContext = context.applicationContext
    private val repository = MediaStoreRepository(appContext)
    private val bitmapLoader = MediaBitmapLoader(context.contentResolver)
    private val digestCalculator = ContentDigestCalculator(context.contentResolver)
    private val videoFingerprintCalculator = VideoFingerprintCalculator(appContext)
    private val database = ScanDatabase(appContext)
    private val scanStateStore = ScanStateStore(appContext)
    private var visualIndexes: MutableMap<MediaKind, HammingBkTree> = mutableMapOf()
    private var visualHashCache: MutableMap<MediaKind, MutableMap<Long, CombinedHash>> = mutableMapOf()
    private val candidateIdsBuffer = ArrayList<Long>(256)
    private val candidateSeenBuffer = HashSet<Long>(256)
    private val imageComputeExecutor = Executors.newFixedThreadPool(
        IMAGE_COMPUTE_THREADS,
        NamedThreadFactory("similar-image")
    )
    private val videoComputeExecutor = Executors.newFixedThreadPool(
        VIDEO_COMPUTE_THREADS,
        NamedThreadFactory("similar-video")
    )

    fun scan(
        forceFull: Boolean = false,
        imageFingerprintSize: Int = DEFAULT_IMAGE_FINGERPRINT_SIZE,
        calculateDuplicateSha256DuringScan: Boolean = false,
        videoFingerprintMode: VideoFingerprintMode = VideoFingerprintMode.BALANCED,
        progress: (ScanProgress) -> Unit = {}
    ): ScanResult {
        val startedAt = System.currentTimeMillis()
        val scanToken = UUID.randomUUID().toString()
        val checkpoint = scanStateStore.checkpoint()
        val hasFullVisualAccess = SimilarScanPermissionChecker.hasFullVisualAccess(appContext)
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
            MediaKind.PHOTO to buildVisualIndex(MediaKind.PHOTO, imageFingerprintSize),
            MediaKind.SCREENSHOT to buildVisualIndex(MediaKind.SCREENSHOT, imageFingerprintSize)
        )

        val modeName = if (fullScan) "full" else "incremental"
        progress(ScanProgress(ScanStage.ENUMERATING, 0, database.groupCount(), "Starting $modeName MediaStore scan."))
        metrics.measure("enumerate_and_fingerprint_total") {
            val pendingJobs = FingerprintJobScheduler()
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
                        database.upsertAsset(
                            asset = asset,
                            scanToken = scanToken,
                            imageFingerprintSize = imageFingerprintSize,
                            videoFingerprintMode = videoFingerprintMode
                        )
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
                    val jobType = if (asset.kind == MediaKind.VIDEO || asset.kind == MediaKind.SCREEN_RECORDING) {
                        FingerprintJobType.VIDEO
                    } else {
                        FingerprintJobType.IMAGE
                    }
                    while (!pendingJobs.hasCapacity(jobType)) {
                        commitNextJob(
                            pendingJobs,
                            metrics,
                            calculateDuplicateSha256DuringScan,
                            videoFingerprintMode,
                            preferredType = jobType
                        )
                    }
                    if (jobType == FingerprintJobType.VIDEO) {
                        pendingJobs.add(submitVideoJob(token, asset, metrics, videoFingerprintMode))
                    } else {
                        pendingJobs.add(submitVisualJob(token, asset, metrics, imageFingerprintSize))
                    }
                    fingerprinted++
                }
                commitCompletedJobs(
                    pendingJobs,
                    metrics,
                    calculateDuplicateSha256DuringScan,
                    videoFingerprintMode
                )

                progress(
                    ScanProgress(
                        stage = ScanStage.MATCHING,
                        processedCount = visited,
                        discoveredGroupCount = database.groupCount(),
                        message = "Scanned $visited assets. $fingerprinted updated, $skippedUnchanged reused."
                    )
                )
            }
            while (pendingJobs.isNotEmpty()) {
                commitNextJob(
                    pendingJobs,
                    metrics,
                    calculateDuplicateSha256DuringScan,
                    videoFingerprintMode,
                    preferredType = null
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
        metrics.measure("rebuild_similar_groups") {
            database.rebuildSimilarGroups(changedKinds, imageFingerprintSize, videoFingerprintMode)
        }
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
        imageComputeExecutor.shutdownNow()
        videoComputeExecutor.shutdownNow()
        database.close()
    }

    private fun submitVisualJob(
        token: AssetScanToken,
        asset: MediaAsset,
        metrics: ScanMetrics,
        imageFingerprintSize: Int
    ): PendingFingerprintJob {
        return PendingFingerprintJob(
            type = FingerprintJobType.IMAGE,
            future = imageComputeExecutor.submit(Callable<ComputedFingerprint> {
                metrics.measure("process_visual_compute") {
                    VisualComputedFingerprint(
                        token = token,
                        asset = asset,
                        visual = metrics.measure("build_visual_fingerprint") {
                            buildVisualFingerprint(asset, metrics, imageFingerprintSize)
                        }
                    )
                }
            })
        )
    }

    private fun commitVisualResult(
        computed: VisualComputedFingerprint,
        metrics: ScanMetrics,
        calculateDuplicateSha256DuringScan: Boolean
    ) {
        val token = computed.token
        val asset = computed.asset
        val visual = computed.visual
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
        val contentSha256 = if (duplicateReferenceCandidates.isNotEmpty() && calculateDuplicateSha256DuringScan) {
            metrics.measure("sha256_current_asset") { digestCalculator.sha256(asset.uri) }
        } else {
            null
        }
        asset.contentSha256 = contentSha256
        asset.qualityScore = visual.qualityScore

        if (calculateDuplicateSha256DuringScan) duplicateReferenceCandidates.forEach { candidate ->
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
        val excludedCandidateIds = duplicateIds + token.assetId
        val candidateIds = metrics.measure("bk_tree_visual_query") {
            candidateIdsBuffer.clear()
            candidateSeenBuffer.clear()
            visualIndexes
                .getValue(asset.kind)
                .queryInto(
                    visual.hash.imageHash,
                    Threshold.maxCandidateDistance(asset.kind),
                    excludedCandidateIds,
                    candidateIdsBuffer,
                    candidateSeenBuffer
                )
            candidateIdsBuffer
        }
        val similarCandidateIds = metrics.measure("filter_visual_candidates_in_memory") {
            val cache = visualHashCache.getValue(asset.kind)
            candidateIds.filter { candidateId ->
                val candidateHash = cache[candidateId] ?: return@filter false
                visual.hash.isSimilarTo(candidateHash, asset.kind)
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
        similarCandidateIds.forEach { candidateId ->
            database.linkSimilarAssets(asset.kind, token.assetId, candidateId)
        }
        visualIndexes.getValue(asset.kind).add(token.assetId, visual.hash.imageHash)
        visualHashCache.getValue(asset.kind)[token.assetId] = visual.hash
    }

    private fun submitVideoJob(
        token: AssetScanToken,
        asset: MediaAsset,
        metrics: ScanMetrics,
        videoFingerprintMode: VideoFingerprintMode
    ): PendingFingerprintJob {
        /*
         * 视频不复用图片缩略图流程。两阶段 quick/full 在真机数据上会漏掉相似视频，
         * 并且候选补算完整指纹会抵消节省的抽帧成本；这里按请求的视频模式一次性
         * 生成最终指纹，提交阶段再统一召回和精判候选。
         */
        return PendingFingerprintJob(
            type = FingerprintJobType.VIDEO,
            future = videoComputeExecutor.submit(Callable<ComputedFingerprint> {
                metrics.measure("process_video_compute") {
                    VideoComputedFingerprint(
                        token = token,
                        asset = asset,
                        fingerprint = metrics.measure("calculate_video_fingerprint") {
                            videoFingerprintCalculator.calculate(asset, videoFingerprintMode)
                        }
                    )
                }
            })
        )
    }

    private fun commitVideoResult(
        computed: VideoComputedFingerprint,
        metrics: ScanMetrics,
        videoFingerprintMode: VideoFingerprintMode
    ) {
        val token = computed.token
        val asset = computed.asset
        val fingerprint = computed.fingerprint
        if (!fingerprint.isValid()) {
            metrics.increment("video_fingerprint_failed")
            database.markFingerprintFailed(token)
            return
        }
        when (fingerprint.source) {
            VideoFingerprintSource.SYSTEM_THUMBNAIL -> metrics.increment("video_system_thumbnail_fingerprint")
            VideoFingerprintSource.COMPETITOR_FRAMES -> metrics.increment("video_competitor_fingerprint")
            else -> metrics.increment("video_mmr_fingerprint")
        }

        val similarCandidates = metrics.measure("load_and_filter_video_candidates") {
            database.findVideoFingerprintCandidates(token.assetId, asset, videoFingerprintMode).filter { candidate ->
                val candidateFingerprint = candidate.videoFingerprint ?: return@filter false
                hasAnyFrameWithinCandidateDistance(fingerprint, candidateFingerprint, asset.kind) &&
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

    private fun commitNextJob(
        pendingJobs: FingerprintJobScheduler,
        metrics: ScanMetrics,
        calculateDuplicateSha256DuringScan: Boolean,
        videoFingerprintMode: VideoFingerprintMode,
        preferredType: FingerprintJobType?
    ) {
        val job = pendingJobs.removeNext(preferredType)
        when (val result = job.await()) {
            is VisualComputedFingerprint -> {
                metrics.measure("process_visual") {
                    commitVisualResult(result, metrics, calculateDuplicateSha256DuringScan)
                }
            }
            is VideoComputedFingerprint -> {
                metrics.measure("process_video") {
                    commitVideoResult(result, metrics, videoFingerprintMode)
                }
            }
        }
    }

    private fun commitCompletedJobs(
        pendingJobs: FingerprintJobScheduler,
        metrics: ScanMetrics,
        calculateDuplicateSha256DuringScan: Boolean,
        videoFingerprintMode: VideoFingerprintMode
    ) {
        while (pendingJobs.hasCompletedJob()) {
            commitNextJob(
                pendingJobs,
                metrics,
                calculateDuplicateSha256DuringScan,
                videoFingerprintMode,
                preferredType = null
            )
        }
    }

    private fun buildVisualFingerprint(
        asset: MediaAsset,
        metrics: ScanMetrics,
        imageFingerprintSize: Int
    ): VisualFingerprintResult? {
        // 指纹输入走竞品兼容加载；UI 预览仍通过 loadBitmap() 使用真实媒体 URI。
        val fingerprintBitmap = metrics.measure("load_fingerprint_bitmap") {
            bitmapLoader.loadFingerprintBitmapWithSource(asset, imageFingerprintSize)
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
                    /*
                     * 首次扫描阶段只写入轻量元数据质量分，避免 9010 张图片都做清晰度/
                     * 曝光采样。相似/相同识别只依赖 dHash + colorHash，不依赖质量分；
                     * 质量分仅用于 Best 排序，因此主链路先用分辨率、收藏、编辑状态兜底。
                     */
                    MediaQualityAnalyzer.metadataScore(asset)
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
    private fun buildVisualIndex(kind: MediaKind, imageFingerprintSize: Int): HammingBkTree {
        val cache = mutableMapOf<Long, CombinedHash>()
        visualHashCache[kind] = cache
        return HammingBkTree().also { tree ->
            database.loadHashIndex(kind, imageFingerprintSize).forEach { entry ->
                tree.add(entry.assetId, entry.imageHash)
                cache[entry.assetId] = entry.hash
            }
        }
    }

    private fun hasAnyFrameWithinCandidateDistance(
        first: VideoFingerprint,
        second: VideoFingerprint,
        kind: MediaKind
    ): Boolean {
        val maxDistance = Threshold.maxCandidateDistance(kind)
        return first.frames.asSequence()
            .filter(CombinedHash::isValid)
            .any { frame ->
                second.frames.asSequence()
                    .filter(CombinedHash::isValid)
                    .any { candidate ->
                        java.lang.Long.bitCount(frame.imageHash xor candidate.imageHash) <= maxDistance
                    }
            }
    }

    companion object {
        private const val BATCH_SIZE = 500
        private const val MAX_GROUPS_TO_SHOW = Int.MAX_VALUE
        private const val IMAGE_COMPUTE_THREADS = 4
        private const val VIDEO_COMPUTE_THREADS = 2
        /*
         * dHash 最终只需要 9x8 采样，colorHash 是 8x3 颜色直方图。真机 9k 图片测试中，
         * MediaStore 缩略图读取是最大耗时点，因此扫描指纹统一压到 256：既保留足够颜色/
         * 结构信息，又减少系统缩略图解码、缩放、像素遍历和 GC 压力。UI 预览仍使用 1024。
         */
        private const val DEFAULT_IMAGE_FINGERPRINT_SIZE = 256
    }
}

private data class VisualFingerprintResult(
    val hash: CombinedHash,
    val qualityScore: Double
)

private class PendingFingerprintJob(
    val type: FingerprintJobType,
    val future: Future<ComputedFingerprint>
) {
    fun await(): ComputedFingerprint {
        return try {
            future.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }
}

private enum class FingerprintJobType {
    IMAGE,
    VIDEO
}

private class FingerprintJobScheduler {
    private val jobs = mutableListOf<PendingFingerprintJob>()
    private var pendingImageCount = 0
    private var pendingVideoCount = 0

    fun isNotEmpty(): Boolean = jobs.isNotEmpty()

    fun hasCapacity(type: FingerprintJobType): Boolean {
        return pendingCount(type) < maxPendingCount(type)
    }

    fun add(job: PendingFingerprintJob) {
        jobs += job
        increment(job.type)
    }

    fun hasCompletedJob(): Boolean = jobs.any { it.future.isDone }

    fun removeNext(preferredType: FingerprintJobType?): PendingFingerprintJob {
        val completedIndex = jobs.indexOfFirst { it.future.isDone }
        if (completedIndex >= 0) return removeAt(completedIndex)

        val preferredIndex = preferredType?.let { type ->
            jobs.indexOfFirst { it.type == type }.takeIf { it >= 0 }
        }
        return removeAt(preferredIndex ?: 0)
    }

    private fun pendingCount(type: FingerprintJobType): Int {
        return when (type) {
            FingerprintJobType.IMAGE -> pendingImageCount
            FingerprintJobType.VIDEO -> pendingVideoCount
        }
    }

    private fun maxPendingCount(type: FingerprintJobType): Int {
        return when (type) {
            FingerprintJobType.IMAGE -> MAX_PENDING_IMAGE_FINGERPRINT_JOBS
            FingerprintJobType.VIDEO -> MAX_PENDING_VIDEO_FINGERPRINT_JOBS
        }
    }

    private fun removeAt(index: Int): PendingFingerprintJob {
        val job = jobs.removeAt(index)
        decrement(job.type)
        return job
    }

    private fun increment(type: FingerprintJobType) {
        when (type) {
            FingerprintJobType.IMAGE -> pendingImageCount++
            FingerprintJobType.VIDEO -> pendingVideoCount++
        }
    }

    private fun decrement(type: FingerprintJobType) {
        when (type) {
            FingerprintJobType.IMAGE -> pendingImageCount--
            FingerprintJobType.VIDEO -> pendingVideoCount--
        }
    }

    private companion object {
        private const val MAX_PENDING_IMAGE_FINGERPRINT_JOBS = 640
        private const val MAX_PENDING_VIDEO_FINGERPRINT_JOBS = 48
    }
}

private sealed interface ComputedFingerprint

private data class VisualComputedFingerprint(
    val token: AssetScanToken,
    val asset: MediaAsset,
    val visual: VisualFingerprintResult?
) : ComputedFingerprint

private data class VideoComputedFingerprint(
    val token: AssetScanToken,
    val asset: MediaAsset,
    val fingerprint: VideoFingerprint
) : ComputedFingerprint

private class NamedThreadFactory(private val prefix: String) : ThreadFactory {
    private val nextId = AtomicInteger(1)

    override fun newThread(runnable: Runnable): Thread {
        return Thread(runnable, "$prefix-${nextId.getAndIncrement()}").apply {
            isDaemon = true
        }
    }
}

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
        val totalsSnapshot: Map<String, Long>
        val countsSnapshot: Map<String, Int>
        synchronized(this) {
            totalsSnapshot = totals.toMap()
            countsSnapshot = counts.toMap()
        }
        Log.d(
            TAG,
            "scan=$modeName elapsed=${elapsed}ms visited=$visited fingerprinted=$fingerprinted reused=$skippedUnchanged"
        )
        totalsSnapshot.forEach { (name, duration) ->
            Log.d(TAG, "metric.$name=${duration}ms")
        }
        countsSnapshot.forEach { (name, count) ->
            Log.d(TAG, "count.$name=$count")
        }
    }

    private fun add(name: String, durationMs: Long) {
        synchronized(this) {
            totals[name] = (totals[name] ?: 0L) + durationMs
        }
    }

    fun increment(name: String) {
        synchronized(this) {
            counts[name] = (counts[name] ?: 0) + 1
        }
    }

    companion object {
        private const val TAG = "SimilarScanMetrics"
    }
}
