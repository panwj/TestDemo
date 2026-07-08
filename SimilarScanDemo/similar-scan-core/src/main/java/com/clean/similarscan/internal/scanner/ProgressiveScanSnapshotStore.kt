package com.clean.similarscan.internal.scanner

import com.clean.similarscan.internal.model.GroupCategory
import com.clean.similarscan.internal.model.MediaAsset
import com.clean.similarscan.internal.model.MediaKind
import com.clean.similarscan.internal.model.ProductCategory
import com.clean.similarscan.internal.model.SimilarGroup
import kotlin.math.absoluteValue

/**
 * 扫描过程中的轻量首页结果快照。
 *
 * 这里仅聚合已经通过当前算法确认的 candidate edge，用于首页边扫边展示；
 * 不参与 dHash/阈值/BK-Tree 判断，也不替代最终数据库分组。
 */
internal object ProgressiveScanSnapshotStore {
    private val lock = Any()
    private val assetsByDbId = linkedMapOf<Long, MediaAsset>()
    private val buckets = linkedMapOf<BucketKey, SnapshotBucket>()
    private val duplicateAssetIds = linkedSetOf<Long>()
    private var active = false
    private var version = 0L
    private var edgeCount = 0

    fun start() {
        synchronized(lock) {
            active = true
            version = 0L
            assetsByDbId.clear()
            buckets.clear()
            duplicateAssetIds.clear()
            edgeCount = 0
        }
    }

    fun finish() {
        synchronized(lock) {
            active = false
            assetsByDbId.clear()
            buckets.clear()
            duplicateAssetIds.clear()
            edgeCount = 0
            version++
        }
    }

    fun removeUris(uris: Collection<String>) {
        if (uris.isEmpty()) return
        synchronized(lock) {
            val removeSet = uris.toSet()
            val removedIds = assetsByDbId
                .filterValues { it.uri.toString() in removeSet }
                .keys
                .toSet()
            if (removedIds.isEmpty()) return
            removedIds.forEach(assetsByDbId::remove)
            duplicateAssetIds.removeAll(removedIds)
            buckets.values.forEach { it.remove(removedIds) }
            buckets.entries.removeAll { (_, bucket) ->
                bucket.groupIds(emptySet()).none { it.size >= 2 }
            }
            edgeCount = buckets.values.sumOf { it.edgeCount() }
            version++
        }
    }

    fun isActive(): Boolean = synchronized(lock) { active }

    fun recordEdges(
        category: GroupCategory,
        kind: MediaKind,
        firstAssetId: Long,
        firstAsset: MediaAsset,
        candidates: Collection<Pair<Long, MediaAsset>>
    ): Boolean {
        if (candidates.isEmpty()) return false
        synchronized(lock) {
            if (!active) return false
            if (!assetsByDbId.containsKey(firstAssetId) && assetsByDbId.size >= MAX_SNAPSHOT_ASSETS) {
                return false
            }
            val remainingEdgeCapacity = MAX_SNAPSHOT_EDGES - edgeCount
            if (remainingEdgeCapacity <= 0) return false
            val acceptedCandidates = mutableListOf<Pair<Long, MediaAsset>>()
            var projectedAssetCount = assetsByDbId.size + if (assetsByDbId.containsKey(firstAssetId)) 0 else 1
            candidates.forEach { candidate ->
                val candidateId = candidate.first
                if (candidateId == firstAssetId || acceptedCandidates.size >= remainingEdgeCapacity) {
                    return@forEach
                }
                if (!assetsByDbId.containsKey(candidateId)) {
                    if (projectedAssetCount >= MAX_SNAPSHOT_ASSETS) return@forEach
                    projectedAssetCount++
                }
                acceptedCandidates += candidate
            }
            if (acceptedCandidates.isEmpty()) return false
            assetsByDbId[firstAssetId] = firstAsset.snapshotCopy()
            if (category == GroupCategory.DUPLICATE) {
                duplicateAssetIds += firstAssetId
                duplicateAssetIds += acceptedCandidates.map { it.first }
            }
            val bucket = buckets.getOrPut(BucketKey(category, kind)) { SnapshotBucket() }
            bucket.ensure(firstAssetId)
            acceptedCandidates.forEach { (candidateId, candidateAsset) ->
                assetsByDbId[candidateId] = candidateAsset.snapshotCopy()
                bucket.ensure(candidateId)
                if (bucket.link(firstAssetId, candidateId)) edgeCount++
            }
            version++
            return true
        }
    }

    fun hasVisibleGroups(): Boolean = synchronized(lock) {
        active && groupsLocked(1).isNotEmpty()
    }

    fun groupCount(): Int = synchronized(lock) {
        groupsLocked(1).size
    }

    fun categories(previewAssetLimit: Int): List<ProductCategory> = synchronized(lock) {
        if (!active && buckets.isEmpty()) return emptyList()
        ProductCategoryBuilder.build(groupsLocked(previewAssetLimit))
    }

    fun groupAssetsPage(groupId: Long, offset: Int, limit: Int): List<MediaAsset> = synchronized(lock) {
        if (groupId >= 0L || limit <= 0) return emptyList()
        groupsLocked(Int.MAX_VALUE)
            .firstOrNull { it.id == groupId }
            ?.assets
            .orEmpty()
            .drop(offset.coerceAtLeast(0))
            .take(limit)
    }

    private fun groupsLocked(previewAssetLimit: Int): List<SimilarGroup> {
        return buckets.flatMap { (key, bucket) ->
            val excludedIds = if (key.category == GroupCategory.SIMILAR) {
                duplicateAssetIds
            } else {
                emptySet()
            }
            bucket.groupIds(excludedIds)
                .asSequence()
                .filter { it.size >= 2 }
                .mapNotNull { ids ->
                    val assets = ids.mapNotNull { assetsByDbId[it] }
                    if (assets.size < 2) return@mapNotNull null
                    val sortedAssets = assets.sortedWith(MEDIA_TIME_DESC)
                    SimilarGroup(
                        id = snapshotGroupId(key, ids),
                        title = key.category.name,
                        subtitle = "",
                        category = key.category,
                        kind = key.kind,
                        assets = sortedAssets.take(previewAssetLimit),
                        totalAssetCount = sortedAssets.size,
                        totalSizeBytes = sortedAssets.sumOf { it.size },
                        latestAssetTimeMillis = sortedAssets.maxOfOrNull {
                            maxOf(it.createdAt.time, it.dateAdded * 1000L)
                        } ?: 0L
                    )
                }
                .toList()
        }
    }

    private fun snapshotGroupId(key: BucketKey, ids: Collection<Long>): Long {
        val seed = ids.firstOrNull() ?: 0L
        val raw = 31L * key.hashCode().toLong() + seed
        return -raw.absoluteValue.coerceAtLeast(1L)
    }

    private fun MediaAsset.snapshotCopy(): MediaAsset {
        return copy(
            contentSha256 = contentSha256,
            qualityScore = qualityScore,
            hash = null
        )
    }

    private data class BucketKey(
        val category: GroupCategory,
        val kind: MediaKind
    )

    private class SnapshotBucket {
        private val links = linkedMapOf<Long, LinkedHashSet<Long>>()

        fun ensure(id: Long) {
            links.getOrPut(id) { linkedSetOf() }
        }

        fun link(first: Long, second: Long): Boolean {
            val firstLinks = links.getOrPut(first) { linkedSetOf() }
            val secondLinks = links.getOrPut(second) { linkedSetOf() }
            val added = firstLinks.add(second)
            secondLinks.add(first)
            return added
        }

        fun groupIds(excludedIds: Set<Long>): List<List<Long>> {
            val remainingIds = links.keys
                .filterNot { it in excludedIds }
                .sortedWith(
                    compareByDescending<Long> { id ->
                        // The caller sorts concrete assets again. This keeps anchor selection stable
                        // while avoiding transitive connected-component grouping.
                        id
                    }
                )
                .toCollection(linkedSetOf())
            val groups = mutableListOf<List<Long>>()
            links.keys.forEach { anchorId ->
                if (!remainingIds.remove(anchorId)) return@forEach
                val matchedIds = links[anchorId]
                    .orEmpty()
                    .filter(remainingIds::contains)
                if (matchedIds.isEmpty()) return@forEach
                groups += listOf(anchorId) + matchedIds
                matchedIds.forEach(remainingIds::remove)
            }
            return groups
        }

        fun remove(ids: Set<Long>) {
            ids.forEach(links::remove)
            links.values.forEach { it.removeAll(ids) }
        }

        fun edgeCount(): Int {
            return links.values.sumOf { it.size } / 2
        }
    }

    private val MEDIA_TIME_DESC = compareByDescending<MediaAsset> { it.createdAt.time }
        .thenByDescending { it.dateAdded }
        .thenByDescending { it.id }

    private const val MAX_SNAPSHOT_ASSETS = 150_000
    private const val MAX_SNAPSHOT_EDGES = 450_000
}
