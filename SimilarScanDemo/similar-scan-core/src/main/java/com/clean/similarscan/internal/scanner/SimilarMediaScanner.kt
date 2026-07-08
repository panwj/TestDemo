package com.clean.similarscan.internal.scanner

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.clean.similarscan.internal.database.ScanDatabase
import com.clean.similarscan.internal.database.AssetScanToken
import com.clean.similarscan.internal.model.MediaAsset
import com.clean.similarscan.internal.model.MediaKind
import com.clean.similarscan.internal.model.ProductCategoryType
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
 * 3. 图片使用 BK-Tree 召回候选，完成阶段按锚点直连规则确定性重建分组。
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
    private var lastIntermediateGroupPublishAt = 0L
    private var lastIntermediateGroupPublishVisited = 0
    private var intermediateEdgesSinceLastPublish = 0
    private var intermediateGroupPublishCount = 0
    private val imageComputeExecutor = Executors.newFixedThreadPool(
        IMAGE_COMPUTE_THREADS,
        NamedThreadFactory("similar-image")
    )
    private val videoComputeExecutor = Executors.newFixedThreadPool(
        VIDEO_COMPUTE_THREADS,
        NamedThreadFactory("similar-video")
    )

    /**
     * 执行一次同步扫描。
     *
     * 该方法会阻塞当前线程，宿主层必须放在前台服务、Worker 或其他后台线程中调用。
     * forceFull 只表示全量枚举和媒体库对账，不表示强制重算全部指纹；未变化资源会复用旧
     * fingerprint。扫描过程中所有数据库写入仍由扫描线程串行提交，计算线程只负责生成指纹。
     */
    fun scan(
        forceFull: Boolean = false,
        imageFingerprintSize: Int = DEFAULT_IMAGE_FINGERPRINT_SIZE,
        calculateDuplicateSha256DuringScan: Boolean = false,
        videoFingerprintMode: VideoFingerprintMode = VideoFingerprintMode.BALANCED,
        enableIntermediateGroupPublish: Boolean = true,
        firstIntermediateGroupPublishIntervalMs: Long = DEFAULT_FIRST_INTERMEDIATE_GROUP_PUBLISH_INTERVAL_MS,
        firstIntermediateGroupPublishMinAssets: Int = DEFAULT_FIRST_INTERMEDIATE_GROUP_PUBLISH_MIN_ASSETS,
        firstIntermediateGroupPublishMinEdges: Int = DEFAULT_FIRST_INTERMEDIATE_GROUP_PUBLISH_MIN_EDGES,
        intermediateGroupPublishIntervalMs: Long = DEFAULT_INTERMEDIATE_GROUP_PUBLISH_INTERVAL_MS,
        intermediateGroupPublishMinAssets: Int = DEFAULT_INTERMEDIATE_GROUP_PUBLISH_MIN_ASSETS,
        intermediateGroupPublishMinEdges: Int = DEFAULT_INTERMEDIATE_GROUP_PUBLISH_MIN_EDGES,
        maxIntermediateGroupPublishCount: Int = DEFAULT_MAX_INTERMEDIATE_GROUP_PUBLISH_COUNT,
        enableMetricsLog: Boolean = true,
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
        val cachedAssetCount = database.assetCount()
        val fullScan = forceFull ||
            cachedAssetCount == 0 ||
            !hasFullVisualAccess ||
            scanStateStore.shouldRunFullScan(mediaStoreVersion)
        val imageGenerationAfter = if (fullScan) 0L else checkpoint.imageGeneration
        val videoGenerationAfter = if (fullScan) 0L else checkpoint.videoGeneration
        var maxImageGeneration = checkpoint.imageGeneration
        var maxVideoGeneration = checkpoint.videoGeneration
        val metrics = ScanMetrics()
        val estimatedMediaCount = metrics.measure("estimate_media_count") {
            repository.estimateMediaCount(imageGenerationAfter, videoGenerationAfter)
        }
        var visited = 0
        var fingerprinted = 0
        var skippedUnchanged = 0
        var lastProgressAt = startedAt
        var lastProgressVisited = 0
        val progressAssetDelta = adaptiveProgressAssetDelta(estimatedMediaCount)
        val changedKinds = linkedSetOf<MediaKind>()
        var publishedGroupCount = database.groupCount()
        lastIntermediateGroupPublishAt = startedAt
        lastIntermediateGroupPublishVisited = 0
        intermediateEdgesSinceLastPublish = 0
        intermediateGroupPublishCount = 0
        videoFingerprintCalculator.resetAdaptiveState()

        // 每次扫描从当前数据库指纹构建轻量 BK-Tree，确保图片候选按汉明距离完整召回。
        visualIndexes = mutableMapOf(
            MediaKind.PHOTO to buildVisualIndex(MediaKind.PHOTO, imageFingerprintSize),
            MediaKind.SCREENSHOT to buildVisualIndex(MediaKind.SCREENSHOT, imageFingerprintSize)
        )

        val modeName = if (fullScan) "full" else "incremental"
        progress(
            scanProgress(
                stage = ScanStage.ENUMERATING,
                processedCount = 0,
                discoveredGroupCount = publishedGroupCount,
                message = "Starting $modeName MediaStore scan.",
                startedAt = startedAt
            )
        )
        metrics.measure("enumerate_and_fingerprint_total") {
            val pendingJobs = FingerprintJobScheduler()
            fun publishAndReportIfNeeded(): Boolean {
                val resultUpdated = publishIntermediateGroupsIfNeeded(
                    enabled = enableIntermediateGroupPublish,
                    changedKinds = changedKinds,
                    imageFingerprintSize = imageFingerprintSize,
                    videoFingerprintMode = videoFingerprintMode,
                    metrics = metrics,
                    visited = visited,
                    startedAt = startedAt,
                    estimatedAssetCount = maxOf(estimatedMediaCount, cachedAssetCount, visited),
                    firstIntervalMs = firstIntermediateGroupPublishIntervalMs,
                    firstMinAssets = firstIntermediateGroupPublishMinAssets,
                    firstMinEdges = firstIntermediateGroupPublishMinEdges,
                    intervalMs = intermediateGroupPublishIntervalMs,
                    minAssets = intermediateGroupPublishMinAssets,
                    minEdges = intermediateGroupPublishMinEdges,
                    maxPublishCount = maxIntermediateGroupPublishCount
                )
                if (!resultUpdated) return false

                publishedGroupCount = database.groupCount()
                progress(
                    scanProgress(
                        stage = ScanStage.MATCHING,
                        processedCount = visited,
                        discoveredGroupCount = publishedGroupCount,
                        message = "Publishing partial groups for $visited scanned assets.",
                        resultUpdated = true,
                        startedAt = startedAt
                    )
                )
                lastProgressAt = System.currentTimeMillis()
                lastProgressVisited = visited
                return true
            }

            repository.forEachMediaBatch(
                batchSize = BATCH_SIZE,
                imageGenerationAfter = imageGenerationAfter,
                videoGenerationAfter = videoGenerationAfter
            ) { batch ->
                batch.forEach { asset ->
                    visited++
                    val now = System.currentTimeMillis()
                    if (
                        visited == 1 ||
                        visited - lastProgressVisited >= progressAssetDelta ||
                        now - lastProgressAt >= PROGRESS_INTERVAL_MS
                    ) {
                        progress(
                            scanProgress(
                                stage = ScanStage.FINGERPRINTING,
                                processedCount = visited,
                                discoveredGroupCount = publishedGroupCount,
                                message = scanProgressMessage(visited, estimatedMediaCount),
                                startedAt = startedAt
                            )
                        )
                        lastProgressAt = now
                        lastProgressVisited = visited
                    }
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
                        publishAndReportIfNeeded()
                    }
                    if (jobType == FingerprintJobType.VIDEO) {
                        pendingJobs.add(submitVideoJob(token, asset, metrics, videoFingerprintMode))
                    } else {
                        pendingJobs.add(submitVisualJob(token, asset, metrics, imageFingerprintSize))
                    }
                    fingerprinted++
                    if (visited % IN_BATCH_COMMIT_CHECK_ASSET_DELTA == 0) {
                        commitCompletedJobs(
                            pendingJobs,
                            metrics,
                            calculateDuplicateSha256DuringScan,
                            videoFingerprintMode
                        )
                        publishAndReportIfNeeded()
                    }
                }
                commitCompletedJobs(
                    pendingJobs,
                    metrics,
                    calculateDuplicateSha256DuringScan,
                    videoFingerprintMode
                )
                publishAndReportIfNeeded()

                progress(
                    scanProgress(
                        stage = ScanStage.MATCHING,
                        processedCount = visited,
                        discoveredGroupCount = publishedGroupCount,
                        message = "Scanned $visited assets. $fingerprinted updated, $skippedUnchanged reused.",
                        startedAt = startedAt
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
                publishAndReportIfNeeded()
            }
        }

        if (fullScan && hasFullVisualAccess) {
            metrics.measure("remove_assets_not_seen") { database.removeAssetsNotSeenInScan(scanToken) }
        }
        /*
         * 扫描过程中先写 candidate edge，并按节流策略阶段性物化给 UI 展示；完成阶段再按
         * 当前规则的“时间排序 + 锚点直接相似”规则确定性重建本轮涉及类型，避免结果受扫描
         * 顺序和中间刷新时机影响。
         */
        metrics.measure("rebuild_similar_groups") {
            database.rebuildSimilarGroups(changedKinds, imageFingerprintSize, videoFingerprintMode, metrics)
        }
        metrics.measure("cleanup_invalid_groups") { database.cleanupInvalidGroups() }
        scanStateStore.save(
            maxImageGeneration,
            maxVideoGeneration,
            mediaStoreVersion,
            completedFullScan = fullScan && hasFullVisualAccess
        )
        val groups = metrics.measure("load_groups") { database.loadGroups(DEFAULT_GROUP_LIMIT) }
        val elapsed = System.currentTimeMillis() - startedAt
        val elapsedText = ScanElapsedFormatter.format(elapsed)
        if (enableMetricsLog) {
            metrics.logSummary(
                modeName = modeName,
                visited = visited,
                fingerprinted = fingerprinted,
                skippedUnchanged = skippedUnchanged,
                elapsed = elapsed,
                elapsedText = elapsedText
            )
        }
        progress(
            ScanProgress(
                stage = ScanStage.COMPLETED,
                processedCount = visited,
                discoveredGroupCount = groups.size,
                message = "Completed $modeName media scan.",
                resultUpdated = true,
                elapsedTimeMs = elapsed,
                elapsedTimeText = elapsedText
            )
        )
        return ScanResult(
            assetCount = visited,
            groups = groups,
            message = "Finished $modeName scan in $elapsedText. Updated $fingerprinted, reused $skippedUnchanged cached fingerprints.",
            elapsedTimeMs = elapsed,
            elapsedTimeText = elapsedText
        )
    }

    private fun scanProgress(
        stage: ScanStage,
        processedCount: Int,
        discoveredGroupCount: Int,
        message: String,
        resultUpdated: Boolean = false,
        startedAt: Long
    ): ScanProgress {
        val elapsed = System.currentTimeMillis() - startedAt
        return ScanProgress(
            stage = stage,
            processedCount = processedCount,
            discoveredGroupCount = discoveredGroupCount,
            message = message,
            resultUpdated = resultUpdated,
            elapsedTimeMs = elapsed,
            elapsedTimeText = ScanElapsedFormatter.format(elapsed)
        )
    }

    /**
     * 加载 UI 预览 Bitmap。
     *
     * 该入口不参与指纹计算，供 SDK 对外 imageLoader/client 复用。
     */
    fun loadBitmap(asset: MediaAsset, thumbSize: Int = 1024) = bitmapLoader.loadBitmap(asset, thumbSize)

    /**
     * 读取缓存分组。
     *
     * 用于 App 启动后先展示上次扫描结果，也用于扫描中按进度刷新当前已落库结果。
     */
    fun loadCachedGroups(
        groupLimit: Int = DEFAULT_GROUP_LIMIT,
        previewAssetLimit: Int = Int.MAX_VALUE
    ) = database.loadGroups(groupLimit, previewAssetLimit)

    fun loadCachedGroups(
        productCategoryType: ProductCategoryType,
        groupLimit: Int = DEFAULT_GROUP_LIMIT,
        previewAssetLimit: Int = Int.MAX_VALUE
    ) = database.loadGroups(productCategoryType, groupLimit, previewAssetLimit)

    /**
     * 按产品分类分页读取资源。
     *
     * 该方法只读取展示数据，不参与扫描、指纹计算或相似分组生成。
     */
    fun loadProductCategoryAssets(
        productCategoryType: ProductCategoryType,
        offset: Int,
        limit: Int
    ) = database.loadProductCategoryAssets(productCategoryType, offset, limit)

    /**
     * 按相似组分页读取资源。
     *
     * 用于详情页或预览页按需加载大分组，避免一次性把全部媒体放入内存。
     */
    fun loadSimilarGroupAssets(
        groupId: Long,
        offset: Int,
        limit: Int
    ) = database.loadGroupAssetsPage(groupId, offset, limit)

    /**
     * SimilarMediaScanner 持有 Room 数据库连接。前台服务每次扫描都会创建 scanner，
     * 扫描结束后必须显式关闭连接，避免系统在 GC 时报告数据库连接泄漏。
     */
    fun close() {
        imageComputeExecutor.shutdownNow()
        videoComputeExecutor.shutdownNow()
        database.close()
    }

    /**
     * 提交图片/截图指纹计算任务。
     *
     * 任务只做 Bitmap 加载、dHash/colorHash 和质量分计算，不直接写数据库。
     */
    private fun submitVisualJob(
        token: AssetScanToken,
        asset: MediaAsset,
        metrics: ScanMetrics,
        imageFingerprintSize: Int
    ): PendingFingerprintJob {
        return PendingFingerprintJob(
            type = FingerprintJobType.IMAGE,
            token = token,
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

    /**
     * 提交图片/截图计算结果。
     *
     * 这里完成重复候选查找、相似候选召回、revision 乐观锁提交、分组关系写入和 BK-Tree 更新。
     */
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
         * 当前规则的 duplicateReference 由媒体类型、宽高、感知 hash、编辑状态和文件大小组成。
         * SHA-256 继续保留为验证证据，但不再作为进入 Duplicates 的唯一条件，否则经过
         * 元数据重写、重新保存但视觉内容一致的图片会被 该规则漏掉。
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
             * SHA 不同并不阻止参考式重复归类，但可在后续诊断页面区分“字节完全相同”
             * 与“组合引用相同”。
             */
            if (contentSha256 != null && database.contentShaForAsset(candidate.assetId) == null) {
                digestCalculator.sha256(candidate.asset.uri)?.also { calculated ->
                    database.updateContentSha(candidate.assetId, calculated)
                }
            }
        }

        val excludedCandidateIds = duplicateReferenceCandidates.mapTo(mutableSetOf()) { it.assetId }
        excludedCandidateIds += token.assetId
        val candidateIds = metrics.measure("bk_tree_visual_query") {
            metrics.increment("visual_bk_query")
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
                .also { visitedNodes ->
                    metrics.addCount("visual_bk_nodes", visitedNodes)
                }
            metrics.addCount("visual_bk_candidates", candidateIdsBuffer.size)
            if (candidateIdsBuffer.isEmpty()) metrics.increment("visual_bk_empty_result")
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

        val duplicateCandidateIds = duplicateReferenceCandidates.map { it.assetId }
        if (duplicateCandidateIds.isNotEmpty()) {
            metrics.measure("link_duplicate_assets") {
                database.linkDuplicateAssets(asset.kind, token.assetId, duplicateCandidateIds)
            }
            metrics.addCount("duplicate_edges", duplicateCandidateIds.size)
            intermediateEdgesSinceLastPublish += duplicateCandidateIds.size
        }
        if (similarCandidateIds.isNotEmpty()) {
            metrics.measure("link_similar_assets") {
                database.linkSimilarAssets(asset.kind, token.assetId, similarCandidateIds)
            }
            metrics.addCount("similar_edges", similarCandidateIds.size)
            intermediateEdgesSinceLastPublish += similarCandidateIds.size
        }
        visualIndexes.getValue(asset.kind).add(token.assetId, visual.hash.imageHash)
        visualHashCache.getValue(asset.kind)[token.assetId] = visual.hash
    }

    /**
     * 提交视频/录屏指纹计算任务。
     *
     * 视频指纹可能涉及多次 MMR 抽帧，因此使用独立线程池，避免阻塞图片扫描。
     */
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
            token = token,
            future = videoComputeExecutor.submit(Callable<ComputedFingerprint> {
                metrics.measure("process_video_compute") {
                    VideoComputedFingerprint(
                        token = token,
                        asset = asset,
                        fingerprint = metrics.measure("calculate_video_fingerprint") {
                            videoFingerprintCalculator.calculate(asset, videoFingerprintMode, metrics::increment)
                        }
                    )
                }
            })
        )
    }

    /**
     * 提交视频/录屏计算结果。
     *
     * 提交流程先查询候选并完成多帧精判，再通过 revision 乐观锁写入 fingerprint。
     * 如果资源在计算期间被删除，markVideoFingerprintDone 会失败，后续分组写入会被放弃。
     */
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
            VideoFingerprintSource.REFERENCE_FRAMES -> metrics.increment("video_reference_fingerprint")
            else -> metrics.increment("video_mmr_fingerprint")
        }

        val similarCandidates = metrics.measure("load_and_filter_video_candidates") {
            database.findVideoFingerprintCandidates(token.assetId, asset, videoFingerprintMode).filter { candidate ->
                val candidateFingerprint = candidate.videoFingerprint ?: return@filter false
                val compareKind = if (
                    asset.kind == MediaKind.SCREEN_RECORDING ||
                    candidate.asset.kind == MediaKind.SCREEN_RECORDING
                ) {
                    MediaKind.SCREEN_RECORDING
                } else {
                    MediaKind.VIDEO
                }
                hasAnyFrameWithinCandidateDistance(fingerprint, candidateFingerprint, compareKind) &&
                    fingerprint.isSimilarTo(candidateFingerprint, compareKind)
            }
        }
        if (!metrics.measure("mark_video_fingerprint_done") {
                database.markVideoFingerprintDone(token, fingerprint, asset, fingerprint.qualityScore)
            }
        ) {
            return
        }
        val similarCandidateIds = similarCandidates.map { it.assetId }
        if (similarCandidateIds.isNotEmpty()) {
            metrics.measure("link_similar_assets") {
                database.linkSimilarAssets(asset.kind, token.assetId, similarCandidateIds)
            }
            metrics.addCount("similar_edges", similarCandidateIds.size)
            intermediateEdgesSinceLastPublish += similarCandidateIds.size
        }
    }

    /**
     * 将扫描中已累计的候选边阶段性物化为 UI 可读取的 Similar/Duplicate 分组。
     *
     * 这里复用最终重建的 edge-table 路径，只在批次边界按“时间 + 资源数/边数”节流触发，
     * 避免回到每条边都维护 group 的高成本模式。
     */
    private fun publishIntermediateGroupsIfNeeded(
        enabled: Boolean,
        changedKinds: Set<MediaKind>,
        imageFingerprintSize: Int,
        videoFingerprintMode: VideoFingerprintMode,
        metrics: ScanMetrics,
        visited: Int,
        startedAt: Long,
        estimatedAssetCount: Int,
        firstIntervalMs: Long,
        firstMinAssets: Int,
        firstMinEdges: Int,
        intervalMs: Long,
        minAssets: Int,
        minEdges: Int,
        maxPublishCount: Int
    ): Boolean {
        if (!enabled || changedKinds.isEmpty()) return false
        val adaptiveMaxPublishCount = adaptiveIntermediateMaxPublishCount(estimatedAssetCount, maxPublishCount)
        if (intermediateGroupPublishCount >= adaptiveMaxPublishCount) return false
        val policy = intermediatePublishPolicy(
            estimatedAssetCount = estimatedAssetCount,
            firstIntervalMs = firstIntervalMs,
            firstMinAssets = firstMinAssets,
            firstMinEdges = firstMinEdges,
            intervalMs = intervalMs,
            minAssets = minAssets,
            minEdges = minEdges
        )
        val now = System.currentTimeMillis()
        if (now - lastIntermediateGroupPublishAt < policy.intervalMs) return false

        val assetDelta = visited - lastIntermediateGroupPublishVisited
        if (!policy.shouldPublish(assetDelta, intermediateEdgesSinceLastPublish)) return false

        metrics.measure("intermediate_group_publish") {
            database.rebuildSimilarGroups(
                changedKinds,
                imageFingerprintSize,
                videoFingerprintMode,
                metrics,
                finalPass = false
            )
        }
        metrics.increment("intermediate_group_publish_count")
        metrics.increment(
            if (intermediateGroupPublishCount == 0) {
                "intermediate_group_publish_first"
            } else {
                "intermediate_group_publish_subsequent"
            }
        )
        metrics.addCount("intermediate_group_publish_assets", assetDelta)
        metrics.addCount("intermediate_group_publish_edges", intermediateEdgesSinceLastPublish)
        if (intermediateGroupPublishCount == 0) {
            metrics.addCount("intermediate_group_publish_first_elapsed_ms", (now - startedAt).toInt())
            metrics.addCount("intermediate_group_publish_first_assets", assetDelta)
            metrics.addCount("intermediate_group_publish_first_edges", intermediateEdgesSinceLastPublish)
        }
        lastIntermediateGroupPublishAt = System.currentTimeMillis()
        lastIntermediateGroupPublishVisited = visited
        intermediateEdgesSinceLastPublish = 0
        intermediateGroupPublishCount++
        return true
    }

    private fun adaptiveIntermediateMaxPublishCount(estimatedAssetCount: Int, requestedMax: Int): Int {
        val adaptiveMax = when {
            estimatedAssetCount <= 2_000 -> 1
            estimatedAssetCount <= 10_000 -> 2
            estimatedAssetCount <= 50_000 -> 2
            else -> 3
        }
        return requestedMax.coerceAtMost(adaptiveMax)
    }

    private fun intermediatePublishPolicy(
        estimatedAssetCount: Int,
        firstIntervalMs: Long,
        firstMinAssets: Int,
        firstMinEdges: Int,
        intervalMs: Long,
        minAssets: Int,
        minEdges: Int
    ): IntermediatePublishPolicy {
        if (intermediateGroupPublishCount == 0) {
            val adaptiveFirst = when {
                estimatedAssetCount <= 500 -> IntermediatePublishPolicy(
                    intervalMs = 1_000L,
                    minAssets = 20,
                    minEdges = 1,
                    requireEdgesForAssetGate = true
                )
                estimatedAssetCount <= 2_000 -> IntermediatePublishPolicy(
                    intervalMs = 1_500L,
                    minAssets = 40,
                    minEdges = 1,
                    requireEdgesForAssetGate = true
                )
                estimatedAssetCount <= 10_000 -> IntermediatePublishPolicy(
                    intervalMs = 2_000L,
                    minAssets = 80,
                    minEdges = 1,
                    requireEdgesForAssetGate = true
                )
                estimatedAssetCount <= 50_000 -> IntermediatePublishPolicy(
                    intervalMs = 3_000L,
                    minAssets = 100,
                    minEdges = 1,
                    requireEdgesForAssetGate = true
                )
                else -> IntermediatePublishPolicy(
                    intervalMs = 5_000L,
                    minAssets = 200,
                    minEdges = 1,
                    requireEdgesForAssetGate = true
                )
            }
            return adaptiveFirst.copy(
                intervalMs = adaptiveFirst.intervalMs.coerceAtMost(firstIntervalMs),
                minAssets = adaptiveFirst.minAssets.coerceAtMost(firstMinAssets),
                minEdges = adaptiveFirst.minEdges.coerceAtMost(firstMinEdges)
            )
        }
        return when {
            estimatedAssetCount <= 2_000 -> IntermediatePublishPolicy(
                intervalMs = 30_000L.coerceAtMost(intervalMs),
                minAssets = 500.coerceAtMost(minAssets),
                minEdges = 1_000.coerceAtMost(minEdges)
            )
            estimatedAssetCount <= 10_000 -> IntermediatePublishPolicy(
                intervalMs = 45_000L.coerceAtMost(intervalMs),
                minAssets = 2_500.coerceAtMost(minAssets),
                minEdges = 5_000.coerceAtMost(minEdges)
            )
            estimatedAssetCount <= 50_000 -> IntermediatePublishPolicy(
                intervalMs = intervalMs,
                minAssets = minAssets,
                minEdges = minEdges
            )
            else -> IntermediatePublishPolicy(
                intervalMs = 120_000L.coerceAtLeast(intervalMs),
                minAssets = 20_000.coerceAtLeast(minAssets),
                minEdges = 40_000.coerceAtLeast(minEdges)
            )
        }
    }

    private fun adaptiveProgressAssetDelta(estimatedAssetCount: Int): Int {
        return when {
            estimatedAssetCount <= 500 -> 10
            estimatedAssetCount <= 2_000 -> 25
            estimatedAssetCount <= 10_000 -> 100
            else -> 250
        }
    }

    private fun scanProgressMessage(visited: Int, estimatedMediaCount: Int): String {
        return if (estimatedMediaCount > 0) {
            "Scanning $visited of $estimatedMediaCount media."
        } else {
            "Scanning $visited media."
        }
    }

    /**
     * 从待提交队列取出一个任务并提交结果。
     *
     * preferredType 用于当前类型队列满时优先释放同类型任务，降低图片/视频互相挤压。
     */
    private fun commitNextJob(
        pendingJobs: FingerprintJobScheduler,
        metrics: ScanMetrics,
        calculateDuplicateSha256DuringScan: Boolean,
        videoFingerprintMode: VideoFingerprintMode,
        preferredType: FingerprintJobType?
    ) {
        val job = pendingJobs.removeNext(preferredType)
        val result = try {
            job.await()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (_: Exception) {
            metrics.increment("fingerprint_job_failed")
            database.markFingerprintFailed(job.token)
            return
        }
        when (result) {
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

    /**
     * 非阻塞提交所有已经完成的任务。
     *
     * 每个 MediaStore 批次结束后调用一次，让 UI 尽快看到已完成的扫描结果。
     */
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

    /**
     * 生成图片/截图视觉指纹。
     *
     * 相似判断只依赖 dHash + colorHash；质量分只用于 Best 排序，因此当前主链路使用轻量
     * 元数据质量分，避免首次扫描时为每张图片额外做清晰度/曝光采样。
     */
    private fun buildVisualFingerprint(
        asset: MediaAsset,
        metrics: ScanMetrics,
        imageFingerprintSize: Int
    ): VisualFingerprintResult? {
        // 指纹输入走扫描专用缩略图加载；UI 预览仍通过 loadBitmap() 使用真实媒体 URI。
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
                     * 首次扫描阶段只写入轻量元数据质量分，避免每张图片都做清晰度/
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

    /**
     * 视频候选预筛。
     *
     * 只要两段视频任意有效帧的 dHash 距离落在最大候选范围内，才进入完整 colorHash +
     * 多帧精判。该预筛只减少无意义候选，不改变最终相似阈值。
     */
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
        private const val DEFAULT_GROUP_LIMIT = Int.MAX_VALUE
        private const val IMAGE_COMPUTE_THREADS = 4
        private const val VIDEO_COMPUTE_THREADS = 2
        private const val DEFAULT_FIRST_INTERMEDIATE_GROUP_PUBLISH_INTERVAL_MS = 3_000L
        private const val DEFAULT_FIRST_INTERMEDIATE_GROUP_PUBLISH_MIN_ASSETS = 100
        private const val DEFAULT_FIRST_INTERMEDIATE_GROUP_PUBLISH_MIN_EDGES = 1
        private const val DEFAULT_INTERMEDIATE_GROUP_PUBLISH_INTERVAL_MS = 75_000L
        private const val DEFAULT_INTERMEDIATE_GROUP_PUBLISH_MIN_ASSETS = 10_000
        private const val DEFAULT_INTERMEDIATE_GROUP_PUBLISH_MIN_EDGES = 20_000
        private const val DEFAULT_MAX_INTERMEDIATE_GROUP_PUBLISH_COUNT = 3
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val IN_BATCH_COMMIT_CHECK_ASSET_DELTA = 25
        /*
         * dHash 最终只需要 9x8 采样，colorHash 是 8x3 颜色直方图。真机 9k 图片测试中，
         * MediaStore 缩略图读取是最大耗时点，因此扫描指纹统一压到 256：既保留足够颜色/
         * 结构信息，又减少系统缩略图解码、缩放、像素遍历和 GC 压力。UI 预览仍使用 1024。
         */
        private const val DEFAULT_IMAGE_FINGERPRINT_SIZE = 256
    }
}

private data class IntermediatePublishPolicy(
    val intervalMs: Long,
    val minAssets: Int,
    val minEdges: Int,
    val requireEdgesForAssetGate: Boolean = false
) {
    fun shouldPublish(assetDelta: Int, edgeDelta: Int): Boolean {
        if (edgeDelta >= minEdges) return true
        if (assetDelta < minAssets) return false
        return !requireEdgesForAssetGate || edgeDelta > 0
    }
}

private data class VisualFingerprintResult(
    val hash: CombinedHash,
    val qualityScore: Double
)

/**
 * 一个等待提交的异步指纹任务。
 */
private class PendingFingerprintJob(
    val type: FingerprintJobType,
    val token: AssetScanToken,
    val future: Future<ComputedFingerprint>
) {
    /**
     * 等待任务完成，并展开 ExecutionException 中的真实异常。
     */
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

/**
 * 指纹任务提交队列。
 *
 * 该类限制图片和视频的待提交任务数量，避免扫描线程连续提交过多 Bitmap/抽帧任务导致
 * 内存峰值过高。结果提交仍由扫描线程按队列取出后串行执行。
 */
private class FingerprintJobScheduler {
    private val jobs = mutableListOf<PendingFingerprintJob>()
    private var pendingImageCount = 0
    private var pendingVideoCount = 0

    fun isNotEmpty(): Boolean = jobs.isNotEmpty()

    /**
     * 判断指定类型是否还能继续提交新任务。
     */
    fun hasCapacity(type: FingerprintJobType): Boolean {
        return pendingCount(type) < maxPendingCount(type)
    }

    fun add(job: PendingFingerprintJob) {
        jobs += job
        increment(job.type)
    }

    fun hasCompletedJob(): Boolean = jobs.any { it.future.isDone }

    /**
     * 取出下一个需要提交的任务。
     *
     * 优先选择已经完成的任务；如果没有完成任务，则优先取 preferredType，最后才取队首任务。
     */
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

/**
 * 给扫描线程池生成可读线程名，方便真机日志和性能分析。
 */
private class NamedThreadFactory(private val prefix: String) : ThreadFactory {
    private val nextId = AtomicInteger(1)

    override fun newThread(runnable: Runnable): Thread {
        return Thread(runnable, "$prefix-${nextId.getAndIncrement()}").apply {
            isDaemon = true
        }
    }
}
