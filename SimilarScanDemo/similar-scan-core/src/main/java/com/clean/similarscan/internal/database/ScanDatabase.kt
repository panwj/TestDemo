package com.clean.similarscan.internal.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import com.clean.similarscan.internal.model.GroupCategory
import com.clean.similarscan.internal.model.MediaAsset
import com.clean.similarscan.internal.model.MediaKind
import com.clean.similarscan.internal.model.ProductCategoryType
import com.clean.similarscan.internal.model.SimilarGroup
import com.clean.similarscan.internal.similarity.CombinedHash
import com.clean.similarscan.internal.similarity.HammingBkTree
import com.clean.similarscan.internal.similarity.Threshold
import com.clean.similarscan.internal.similarity.VideoFingerprint
import com.clean.similarscan.internal.similarity.VideoFingerprintMode
import com.clean.similarscan.internal.similarity.VideoFingerprintSource
import com.clean.similarscan.internal.util.FormatUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DB_NAME = "similar_scan.db"
private const val DB_VERSION = 27

/**
 * Room 数据库承载扫描库连接。
 *
 * Entity 只描述扫描缓存表结构；扫描链路仍通过下方原生 SQL 执行复杂查询和批量写入，
 * 以保证数据库框架切换不改变相似识别结果。
 */
@Entity(
    tableName = "media_asset",
    indices = [
        Index(value = ["media_store_id", "type"], unique = true),
        Index(value = ["type", "fingerprint_status"], name = "idx_asset_type_status"),
        Index(value = ["state", "type"], name = "idx_asset_state_type"),
        Index(value = ["type", "duration", "size"], name = "idx_asset_type_duration_size"),
        Index(value = ["type", "state", "size", "width", "height", "is_edited"], name = "idx_asset_duplicate_ref"),
        Index(value = ["type", "state", "fingerprint_algorithm_version"], name = "idx_asset_video_candidate")
    ]
)
internal data class MediaAssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "media_store_id") val mediaStoreId: Long,
    val uri: String,
    val type: String,
    val name: String,
    val width: Int?,
    val height: Int?,
    val duration: Long?,
    val size: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long?,
    @ColumnInfo(name = "date_added", defaultValue = "0") val dateAdded: Long = 0,
    val bucket: String?,
    @ColumnInfo(name = "path_hint") val pathHint: String?,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    @ColumnInfo(name = "is_favorite", defaultValue = "0") val isFavorite: Int = 0,
    @ColumnInfo(name = "is_edited", defaultValue = "0") val isEdited: Int = 0,
    @ColumnInfo(name = "generation_added", defaultValue = "0") val generationAdded: Long = 0,
    @ColumnInfo(name = "generation_modified", defaultValue = "0") val generationModified: Long = 0,
    @ColumnInfo(name = "chat_source") val chatSource: String?,
    @ColumnInfo(defaultValue = "'ACTIVE'") val state: String = "ACTIVE",
    @ColumnInfo(name = "state_changed_at", defaultValue = "0") val stateChangedAt: Long = 0,
    @ColumnInfo(defaultValue = "1") val revision: Long = 1,
    @ColumnInfo(name = "fingerprint_status") val fingerprintStatus: String,
    @ColumnInfo(name = "last_scanned_at") val lastScannedAt: Long?,
    @ColumnInfo(name = "last_seen_scan") val lastSeenScan: String?,
    @ColumnInfo(name = "source_signature") val sourceSignature: String?,
    @ColumnInfo(name = "fingerprint_algorithm_version", defaultValue = "0") val fingerprintAlgorithmVersion: Int = 0
)

@Entity(
    tableName = "fingerprint",
    foreignKeys = [
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["hash_prefix", "aspect_bucket", "duration_bucket"], name = "idx_fingerprint_candidate"),
        Index(value = ["image_hash"], name = "idx_fingerprint_image_hash"),
        Index(value = ["duration_bucket", "aspect_bucket"], name = "idx_fingerprint_video_bucket"),
        Index(value = ["content_sha256"], name = "idx_fingerprint_sha"),
        Index(value = ["potential_identifier"], name = "idx_fingerprint_potential")
    ]
)
internal data class FingerprintEntity(
    @PrimaryKey
    @ColumnInfo(name = "asset_id") val assetId: Long,
    @ColumnInfo(name = "image_hash") val imageHash: Long,
    @ColumnInfo(name = "color_hash") val colorHash: String,
    @ColumnInfo(name = "hash_prefix") val hashPrefix: Int,
    @ColumnInfo(name = "aspect_bucket") val aspectBucket: Int,
    @ColumnInfo(name = "duration_bucket") val durationBucket: Long,
    @ColumnInfo(name = "video_frame_hashes") val videoFrameHashes: String?,
    @ColumnInfo(name = "video_frame_colors") val videoFrameColors: String?,
    @ColumnInfo(name = "content_sha256") val contentSha256: String?,
    @ColumnInfo(name = "quality_score", defaultValue = "0") val qualityScore: Double = 0.0,
    @ColumnInfo(name = "potential_identifier") val potentialIdentifier: String?,
    @ColumnInfo(name = "video_fingerprint_source") val videoFingerprintSource: String?
)

@Entity(tableName = "similar_group")
internal data class SimilarGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val type: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Entity(
    tableName = "similar_group_item",
    primaryKeys = ["group_id", "asset_id"],
    foreignKeys = [
        ForeignKey(
            entity = SimilarGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["asset_id"], name = "idx_group_item_asset")]
)
internal data class SimilarGroupItemEntity(
    @ColumnInfo(name = "group_id") val groupId: Long,
    @ColumnInfo(name = "asset_id") val assetId: Long
)

@Database(
    entities = [
        MediaAssetEntity::class,
        FingerprintEntity::class,
        SimilarGroupEntity::class,
        SimilarGroupItemEntity::class
    ],
    version = DB_VERSION,
    exportSchema = false
)
internal abstract class ScanRoomDatabase : RoomDatabase()

/**
 * Room 原生 SQL 执行适配层。
 *
 * 当前扫描链路包含动态查询、批量插入和 Cursor 分页读取；这层只把原生 SQL 调用转发到
 * Room 管理的数据库连接，避免扫描链路直接持有底层数据库实现。
 */
private class SqlDb(private val delegate: SupportSQLiteDatabase) {
    fun setForeignKeyConstraintsEnabled(enabled: Boolean) {
        delegate.setForeignKeyConstraintsEnabled(enabled)
    }

    fun execSQL(sql: String) {
        delegate.execSQL(sql)
    }

    fun execSQL(sql: String, bindArgs: Array<out Any?>) {
        delegate.execSQL(sql, bindArgs)
    }

    fun rawQuery(sql: String, selectionArgs: Array<String>?): Cursor {
        return delegate.query(sql, selectionArgs.toBindArgs())
    }

    fun beginTransaction() {
        delegate.beginTransaction()
    }

    fun setTransactionSuccessful() {
        delegate.setTransactionSuccessful()
    }

    fun endTransaction() {
        delegate.endTransaction()
    }

    @Suppress("UNUSED_PARAMETER")
    fun insert(table: String, nullColumnHack: String?, values: ContentValues): Long {
        return delegate.insert(table, CONFLICT_NONE, values)
    }

    @Suppress("UNUSED_PARAMETER")
    fun insertWithOnConflict(
        table: String,
        nullColumnHack: String?,
        values: ContentValues,
        conflictAlgorithm: Int
    ): Long {
        return delegate.insert(table, conflictAlgorithm, values)
    }

    fun update(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?
    ): Int {
        return delegate.update(
            table,
            CONFLICT_NONE,
            values,
            whereClause,
            whereArgs.toBindArgs()
        )
    }

    fun delete(table: String, whereClause: String?, whereArgs: Array<String>?): Int {
        return delegate.delete(table, whereClause, whereArgs.toBindArgs())
    }

    fun compileStatement(sql: String): SupportSQLiteStatement {
        return delegate.compileStatement(sql)
    }

    private fun Array<String>?.toBindArgs(): Array<Any?> {
        return this?.map<String, Any?> { it }?.toTypedArray() ?: emptyArray()
    }

    companion object {
        const val CONFLICT_NONE = 0
        const val CONFLICT_IGNORE = 4
        const val CONFLICT_REPLACE = 5
    }
}

/**
 * 产品级扫描必须落库：这样才能支持 10 万资源、断点续扫、增量扫描和结果即时展示。
 */
internal class ScanDatabase(context: Context) {
    private val roomDatabase: ScanRoomDatabase = Room.databaseBuilder(
        context.applicationContext,
        ScanRoomDatabase::class.java,
        DB_NAME
    )
        /*
         * 扫描服务会持续写库，详情页/首页会同时读库。WAL 允许读写并发，
         * 减少数据库锁等待。
         */
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        /*
         * 扫描库保存的是可重建缓存。SDK 仍处于开发验证阶段，内部表结构变化时直接
         * 重建缓存，避免旧指纹结构混入当前扫描结果。
         */
        .fallbackToDestructiveMigration()
        .addCallback(
            object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    configureDatabase(SqlDb(db))
                }
            }
        )
        .build()

    private val writableDatabase: SqlDb
        get() = SqlDb(roomDatabase.openHelper.writableDatabase)

    private val readableDatabase: SqlDb
        get() = SqlDb(roomDatabase.openHelper.readableDatabase)

    /** 关闭 Room 持有的数据库连接，避免扫描服务结束后连接泄漏。 */
    fun close() {
        roomDatabase.close()
    }

    private fun configureDatabase(db: SqlDb) {
        // 保证删除 media_asset/similar_group 时，关联指纹和组成员不会变成孤立数据。
        db.setForeignKeyConstraintsEnabled(true)
        /*
         * busy_timeout 属于会返回结果的 PRAGMA。使用 query 后立即关闭 Cursor，可以
         * 兼容不同系统版本，同时保留等待写锁释放的能力。
         */
        db.rawQuery("PRAGMA busy_timeout=3000", null).close()
    }

    /**
     * 清空所有扫描产物。
     *
     * 当指纹算法或关键参数变化时必须调用。媒体元数据、指纹和分组相互关联，只删除
     * fingerprint 会留下无法解释的旧分组，因此这里统一事务清理。
     */
    fun clearScanData() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("similar_group_item", null, null)
            db.delete("similar_group", null, null)
            db.delete("fingerprint", null, null)
            db.delete("media_asset", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 写入媒体元数据并返回本轮扫描令牌。
     *
     * DELETE_PENDING/DELETED 资源不会重新变为 ACTIVE。扫描计算完成后必须携带返回的
     * revision 提交，若用户期间执行删除，revision 会变化，旧计算结果将被拒绝。
     *
     * 这里同时计算 source_signature：它描述“当前媒体内容是否足以复用旧指纹”。
     * 如果签名、算法版本和 fingerprint 记录都存在且一致，本轮只更新 last_seen_scan，
     * 扫描器会跳过 bitmap 解码、dHash/colorHash 和视频抽帧，避免全量校验时重复计算。
     */
    fun upsertAsset(
        asset: MediaAsset,
        scanToken: String = "",
        imageFingerprintSize: Int = DEFAULT_IMAGE_FINGERPRINT_SIZE,
        videoFingerprintMode: VideoFingerprintMode = VideoFingerprintMode.BALANCED
    ): AssetScanToken? {
        val db = writableDatabase
        val existingId = findAssetId(asset.id, asset.kind)
        val sourceSignature = SourceSignature.from(asset)
        val algorithmVersion = fingerprintAlgorithmVersion(asset.kind, imageFingerprintSize, videoFingerprintMode)
        val values = ContentValues().apply {
            put("media_store_id", asset.id)
            put("uri", asset.uri.toString())
            put("type", asset.kind.name)
            put("name", asset.name)
            put("width", asset.width)
            put("height", asset.height)
            put("duration", asset.duration)
            put("size", asset.size)
            put("created_at", asset.createdAt.time)
            put("updated_at", asset.updatedAt.time)
            put("date_added", asset.dateAdded)
            put("bucket", asset.bucket)
            put("path_hint", asset.pathHint)
            put("mime_type", asset.mimeType)
            put("is_favorite", if (asset.isFavorite) 1 else 0)
            put("is_edited", if (asset.isEdited) 1 else 0)
            put("generation_added", asset.generationAdded)
            put("generation_modified", asset.generationModified)
            put("chat_source", asset.chatSource)
            put("last_scanned_at", System.currentTimeMillis())
            put("last_seen_scan", scanToken)
            put("source_signature", sourceSignature)
            put("fingerprint_algorithm_version", algorithmVersion)
        }
        if (existingId != null) {
            val current = assetStateRevisionAndFingerprint(existingId) ?: return null
            if (current.state == "DELETE_PENDING" || current.state == "DELETED") return null
            val canReuseFingerprint = current.hasFingerprint &&
                current.sourceSignature == sourceSignature &&
                current.algorithmVersion == algorithmVersion
            values.put("fingerprint_status", if (canReuseFingerprint) "DONE" else "PENDING")
            db.update("media_asset", values, "id=?", arrayOf(existingId.toString()))
            return AssetScanToken(
                assetId = existingId,
                revision = current.revision,
                needsFingerprint = !canReuseFingerprint
            )
        } else {
            values.put("state", "ACTIVE")
            values.put("state_changed_at", System.currentTimeMillis())
            values.put("revision", 1L)
            values.put("fingerprint_status", "PENDING")
            val id = db.insert("media_asset", null, values)
            return if (id > 0L) AssetScanToken(id, 1L, needsFingerprint = true) else null
        }
    }

    fun markFingerprintDone(
        token: AssetScanToken,
        hash: CombinedHash,
        asset: MediaAsset,
        contentSha256: String?,
        qualityScore: Double
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (!isTokenActive(db, token)) return false
            db.insertWithOnConflict(
                "fingerprint",
                null,
                ContentValues().apply {
                    put("asset_id", token.assetId)
                    put("image_hash", hash.imageHash)
                    put("color_hash", ColorHashCodec.encode(hash.colorHash))
                    put("hash_prefix", HashBuckets.hashPrefix(hash.imageHash))
                    put("aspect_bucket", HashBuckets.aspectBucket(asset.width, asset.height))
                    put("duration_bucket", HashBuckets.durationBucket(asset.duration))
                    putNull("video_frame_hashes")
                    putNull("video_frame_colors")
                    put("content_sha256", contentSha256)
                    put("quality_score", qualityScore)
                    put("potential_identifier", HashBuckets.potentialIdentifier(asset))
                    putNull("video_fingerprint_source")
                },
                SqlDb.CONFLICT_REPLACE
            )
            db.update(
                "media_asset",
                ContentValues().apply { put("fingerprint_status", "DONE") },
                "id=?",
                arrayOf(token.assetId.toString())
            )
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun markVideoFingerprintDone(
        token: AssetScanToken,
        fingerprint: VideoFingerprint,
        asset: MediaAsset,
        qualityScore: Double
    ): Boolean {
        // 主字段保留第一张有效帧，完整帧序列另存于 video_frame_* 字段。
        val representative = fingerprint.frames.firstOrNull { it.isValid() }
            ?: fingerprint.frames.first()
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (!isTokenActive(db, token)) return false
            db.insertWithOnConflict(
                "fingerprint",
                null,
                ContentValues().apply {
                    put("asset_id", token.assetId)
                    put("image_hash", representative.imageHash)
                    put("color_hash", ColorHashCodec.encode(representative.colorHash))
                    put("hash_prefix", HashBuckets.hashPrefix(representative.imageHash))
                    put("aspect_bucket", HashBuckets.aspectBucket(asset.width, asset.height))
                    put("duration_bucket", HashBuckets.durationBucket(asset.duration))
                    put("video_frame_hashes", VideoFingerprintCodec.encodeHashes(fingerprint))
                    put("video_frame_colors", VideoFingerprintCodec.encodeColors(fingerprint))
                    putNull("content_sha256")
                    put("quality_score", qualityScore)
                    put("potential_identifier", HashBuckets.potentialIdentifier(asset))
                    put("video_fingerprint_source", fingerprint.source.name)
                },
                SqlDb.CONFLICT_REPLACE
            )
            db.update(
                "media_asset",
                ContentValues().apply { put("fingerprint_status", "DONE") },
                "id=?",
                arrayOf(token.assetId.toString())
            )
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun markFingerprintFailed(token: AssetScanToken) {
        writableDatabase.update(
            "media_asset",
            ContentValues().apply { put("fingerprint_status", "FAILED") },
            "id=? AND state='ACTIVE' AND revision=?",
            arrayOf(token.assetId.toString(), token.revision.toString())
        )
    }

    fun markAssetScanned(token: AssetScanToken) {
        writableDatabase.update(
            "media_asset",
            ContentValues().apply { put("fingerprint_status", "DONE") },
            "id=? AND state='ACTIVE' AND revision=?",
            arrayOf(token.assetId.toString(), token.revision.toString())
        )
    }

    fun prepareAssetForRescan(token: AssetScanToken): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (!isTokenActive(db, token)) return false
            db.delete("similar_group_item", "asset_id=?", arrayOf(token.assetId.toString()))
            db.execSQL(
                """
                DELETE FROM similar_group
                WHERE id NOT IN (
                    SELECT DISTINCT group_id FROM similar_group_item
                )
                """.trimIndent()
            )
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun cleanupInvalidGroups() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                """
                DELETE FROM similar_group_item
                WHERE group_id IN (
                    SELECT g.id
                    FROM similar_group g
                    LEFT JOIN similar_group_item i ON i.group_id = g.id
                    GROUP BY g.id
                    HAVING COUNT(i.asset_id) < 2
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                DELETE FROM similar_group
                WHERE id NOT IN (
                    SELECT DISTINCT group_id FROM similar_group_item
                )
                """.trimIndent()
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 按锚点直连语义重建相似组。
     *
     * 图片先按创建时间升序排序，取当前最早资源作为锚点，只把“与锚点直接相似”的
     * 资源放进该组，然后从待处理集合移除整组。它不是相似关系的连通分量，因此
     * A≈B、B≈C 但 A≉C 时，C 不会借助 B 被并入 A 所在组。
     *
     * 图片使用单组合 Hash；视频为每一帧建立索引，最终使用当前规则的跨帧至少两次命中精判。
     */
    fun rebuildSimilarGroups(
        kinds: Set<MediaKind>,
        imageFingerprintSize: Int = DEFAULT_IMAGE_FINGERPRINT_SIZE,
        videoFingerprintMode: VideoFingerprintMode = VideoFingerprintMode.BALANCED
    ) {
        val supportedKinds = kinds.filterTo(linkedSetOf()) {
            it == MediaKind.PHOTO ||
                it == MediaKind.SCREENSHOT ||
                it == MediaKind.VIDEO ||
                it == MediaKind.SCREEN_RECORDING
        }
        if (supportedKinds.isEmpty()) return

        val db = writableDatabase
        db.beginTransaction()
        try {
            supportedKinds.forEach kindLoop@{ kind ->
                /*
                 * 扫描过程中 processVisual/processVideo 已经把相似候选实时写入
                 * Similar 分组。最终重建时先把这些候选读成邻接表，再删除旧组并按参考规则
                 * “锚点直连”规则重建，可以避免扫描完成后再次为同一批资源跑 BK-Tree。
                 *
                 * 如果是旧数据、异常中断或首次进入时没有可复用候选，则回退到原 BK-Tree
                 * 召回路径，保证结果不会因为候选表缺失而被清空。
                 */
                val reusableCandidateMap = loadExistingSimilarCandidateMap(db, kind)
                deleteSimilarGroups(db, kind)
                val records = loadGroupingFingerprints(db, kind, imageFingerprintSize, videoFingerprintMode)
                if (records.size < 2) return@kindLoop

                val recordsById = records.associateBy(GroupingFingerprint::assetId)
                val remainingIds = records.mapTo(linkedSetOf(), GroupingFingerprint::assetId)
                val index = if (reusableCandidateMap.isEmpty()) {
                    HammingBkTree().also { tree ->
                        records.forEach { record ->
                            record.indexHashes.forEach { imageHash ->
                                tree.add(record.assetId, imageHash)
                            }
                        }
                    }
                } else {
                    null
                }
                val insertGroupItemStatement = db.compileStatement(
                    "INSERT OR IGNORE INTO similar_group_item(group_id, asset_id) VALUES(?, ?)"
                )

                try {
                    records.forEach anchorLoop@{ anchor ->
                        // 已被更早锚点收进分组的资源不再作为新锚点。
                        if (!remainingIds.remove(anchor.assetId)) return@anchorLoop

                        val matchedIds = if (index == null) {
                            reusableCandidateMap[anchor.assetId]
                                .orEmpty()
                                .asSequence()
                        } else {
                            anchor.indexHashes
                                .asSequence()
                                .flatMap { imageHash ->
                                    index.query(
                                        imageHash,
                                        Threshold.maxCandidateDistance(kind)
                                    ).asSequence()
                                }
                                .distinct()
                        }
                            .filter(remainingIds::contains)
                            .filter { candidateId ->
                                val candidate = recordsById[candidateId] ?: return@filter false
                                if (kind == MediaKind.VIDEO || kind == MediaKind.SCREEN_RECORDING) {
                                    val anchorVideo = anchor.videoFingerprint ?: return@filter false
                                    val candidateVideo = candidate.videoFingerprint ?: return@filter false
                                    anchorVideo.isSimilarTo(candidateVideo, kind)
                                } else {
                                    anchor.hash.isSimilarTo(candidate.hash, kind)
                                }
                            }
                            .toList()

                        if (matchedIds.isEmpty()) return@anchorLoop
                        val groupId = db.insert(
                            "similar_group",
                            null,
                            ContentValues().apply {
                                put("category", GroupCategory.SIMILAR.name)
                                put("type", kind.name)
                                put("updated_at", System.currentTimeMillis())
                            }
                        )
                        insertGroupItem(insertGroupItemStatement, groupId, anchor.assetId)
                        matchedIds.forEach { assetId ->
                            insertGroupItem(insertGroupItemStatement, groupId, assetId)
                            remainingIds.remove(assetId)
                        }
                    }
                } finally {
                    insertGroupItemStatement.close()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun loadExistingSimilarCandidateMap(
        db: SqlDb,
        kind: MediaKind
    ): Map<Long, Set<Long>> {
        val groupMembers = linkedMapOf<Long, MutableList<Long>>()
        db.rawQuery(
            """
            SELECT g.id, i.asset_id
            FROM similar_group g
            JOIN similar_group_item i ON i.group_id = g.id
            JOIN media_asset a ON a.id = i.asset_id
            WHERE g.category = ?
              AND g.type = ?
              AND a.type = ?
              AND a.state = 'ACTIVE'
            ORDER BY g.id ASC
            """.trimIndent(),
            arrayOf(GroupCategory.SIMILAR.name, kind.name, kind.name)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                groupMembers
                    .getOrPut(cursor.getLong(0)) { mutableListOf() }
                    .add(cursor.getLong(1))
            }
        }
        if (groupMembers.isEmpty()) return emptyMap()

        /*
         * similar_group_item 只保存组成员，不保存具体边。这里把同组成员展开成候选邻接表，
         * 后续仍会执行 dHash/colorHash 或视频多帧精判，因此不会因为展开候选而直接放大分组。
         */
        val candidates = mutableMapOf<Long, MutableSet<Long>>()
        groupMembers.values.forEach { members ->
            if (members.size < 2) return@forEach
            members.forEach { assetId ->
                val others = candidates.getOrPut(assetId) { linkedSetOf() }
                members.forEach { otherId ->
                    if (otherId != assetId) others += otherId
                }
            }
        }
        return candidates
    }

    private fun deleteSimilarGroups(db: SqlDb, kind: MediaKind) {
        db.execSQL(
            """
            DELETE FROM similar_group_item
            WHERE group_id IN (
                SELECT id FROM similar_group WHERE category=? AND type=?
            )
            """.trimIndent(),
            arrayOf(GroupCategory.SIMILAR.name, kind.name)
        )
        db.delete(
            "similar_group",
            "category=? AND type=?",
            arrayOf(GroupCategory.SIMILAR.name, kind.name)
        )
    }

    private fun loadGroupingFingerprints(
        db: SqlDb,
        kind: MediaKind,
        imageFingerprintSize: Int = DEFAULT_IMAGE_FINGERPRINT_SIZE,
        videoFingerprintMode: VideoFingerprintMode = VideoFingerprintMode.BALANCED
    ): List<GroupingFingerprint> {
        /*
         * 视频专用流程直接使用 MediaStore date_added DESC 的输入顺序作为锚点顺序，
         * 与图片按拍摄时间升序的路径分开处理。
         */
        val orderBy = if (kind == MediaKind.VIDEO || kind == MediaKind.SCREEN_RECORDING) {
            "a.date_added DESC"
        } else {
            "a.created_at ASC, a.media_store_id DESC"
        }
        return db.rawQuery(
            """
            SELECT a.id, f.image_hash, f.color_hash,
                   f.video_frame_hashes, f.video_frame_colors, f.video_fingerprint_source
            FROM media_asset a
            JOIN fingerprint f ON f.asset_id=a.id
            WHERE a.type=?
              AND a.state='ACTIVE'
              AND a.fingerprint_status='DONE'
              AND a.fingerprint_algorithm_version=?
              AND NOT EXISTS (
                  SELECT 1
                  FROM similar_group_item duplicate_item
                  JOIN similar_group duplicate_group
                    ON duplicate_group.id=duplicate_item.group_id
                  WHERE duplicate_item.asset_id=a.id
                    AND duplicate_group.category=?
                    AND duplicate_group.type=?
              )
            ORDER BY $orderBy
            """.trimIndent(),
            arrayOf(
                kind.name,
                fingerprintAlgorithmVersion(kind, imageFingerprintSize, videoFingerprintMode).toString(),
                GroupCategory.DUPLICATE.name,
                kind.name
            )
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val videoFingerprint =
                        if (cursor.isNull(3) || cursor.isNull(4)) {
                            null
                        } else {
                            VideoFingerprintCodec.decode(
                                hashes = cursor.getString(3),
                                colors = cursor.getString(4),
                                source = if (cursor.isNull(5)) null else cursor.getString(5)
                            )
                        }
                    add(
                        GroupingFingerprint(
                            assetId = cursor.getLong(0),
                            hash = CombinedHash(
                                imageHash = cursor.getLong(1),
                                colorHash = ColorHashCodec.decode(cursor.getString(2))
                            ),
                            videoFingerprint = videoFingerprint
                        )
                    )
                }
            }
        }
    }

    fun removeAssetsByUris(uris: Collection<String>) {
        if (uris.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val placeholders = uris.joinToString(",") { "?" }
            val args = uris.toTypedArray()
            db.execSQL(
                "DELETE FROM similar_group_item WHERE asset_id IN (SELECT id FROM media_asset WHERE uri IN ($placeholders))",
                args
            )
            db.execSQL(
                "DELETE FROM fingerprint WHERE asset_id IN (SELECT id FROM media_asset WHERE uri IN ($placeholders))",
                args
            )
            db.delete("media_asset", "uri IN ($placeholders)", args)
            db.execSQL(
                "DELETE FROM similar_group WHERE id NOT IN (SELECT DISTINCT group_id FROM similar_group_item)"
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 系统删除确认弹出前，将资源标记为删除中。
     *
     * revision 同步递增，使正在后台计算的旧扫描令牌立即失效。
     */
    fun markDeletePending(uris: Collection<String>): Set<String> {
        if (uris.isEmpty()) return emptySet()
        val placeholders = uris.joinToString(",") { "?" }
        val args = uris.toTypedArray()
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val existing = db.rawQuery(
                "SELECT uri FROM media_asset WHERE uri IN ($placeholders) AND state='ACTIVE'",
                args
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            if (existing.isNotEmpty()) {
                val activePlaceholders = existing.joinToString(",") { "?" }
                db.execSQL(
                    """
                    UPDATE media_asset
                    SET state='DELETE_PENDING',
                        state_changed_at=?,
                        revision=revision+1
                    WHERE uri IN ($activePlaceholders) AND state='ACTIVE'
                    """.trimIndent(),
                    arrayOf(System.currentTimeMillis(), *existing.toTypedArray())
                )
            }
            db.setTransactionSuccessful()
            existing
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 用户取消系统删除时恢复资源，并标记为待重新扫描。
     */
    fun restoreDeletePending(uris: Collection<String>) {
        if (uris.isEmpty()) return
        val placeholders = uris.joinToString(",") { "?" }
        writableDatabase.execSQL(
            """
            UPDATE media_asset
            SET state='ACTIVE',
                state_changed_at=?,
                revision=revision+1,
                fingerprint_status='PENDING'
            WHERE uri IN ($placeholders) AND state='DELETE_PENDING'
            """.trimIndent(),
            arrayOf(System.currentTimeMillis(), *uris.toTypedArray())
        )
    }

    /**
     * 恢复因进程终止而没有收到系统删除结果的悬挂操作。
     *
     * App 重新启动时系统确认页面已经不存在，继续保留 DELETE_PENDING 没有意义。
     * 恢复后由 MediaStore 增量扫描判断文件实际存在或已经被删除。
     */
    fun recoverStaleDeletePending(now: Long = System.currentTimeMillis()) {
        writableDatabase.execSQL(
            """
            UPDATE media_asset
            SET state='ACTIVE',
                state_changed_at=?,
                revision=revision+1,
                fingerprint_status='PENDING'
            WHERE state='DELETE_PENDING'
            """.trimIndent(),
            arrayOf(now)
        )
    }

    /**
     * 系统确认删除后的最终提交。外键级联清理指纹和组成员。
     */
    fun finalizeDelete(uris: Collection<String>) {
        if (uris.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val placeholders = uris.joinToString(",") { "?" }
            db.delete(
                "media_asset",
                "uri IN ($placeholders) AND state='DELETE_PENDING'",
                uris.toTypedArray()
            )
            db.execSQL(
                "DELETE FROM similar_group WHERE id NOT IN " +
                    "(SELECT DISTINCT group_id FROM similar_group_item)"
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 全量扫描完成后移除 MediaStore 中已不存在的记录。
     *
     * 调用方必须确认拥有完整图片和视频权限，部分授权时不能据此判断资源已删除。
     */
    fun removeAssetsNotSeenInScan(scanToken: String) {
        if (scanToken.isBlank()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            // DELETE_PENDING 由用户操作控制，全量扫描结束时不能当作系统缺失资源清理。
            val staleSelection =
                "state != 'DELETE_PENDING' AND (last_seen_scan IS NULL OR last_seen_scan != ?)"
            val args = arrayOf(scanToken)
            db.execSQL(
                "DELETE FROM similar_group_item WHERE asset_id IN " +
                    "(SELECT id FROM media_asset WHERE $staleSelection)",
                args
            )
            db.execSQL(
                "DELETE FROM fingerprint WHERE asset_id IN " +
                    "(SELECT id FROM media_asset WHERE $staleSelection)",
                args
            )
            db.delete("media_asset", staleSelection, args)
            db.execSQL(
                "DELETE FROM similar_group WHERE id NOT IN " +
                    "(SELECT DISTINCT group_id FROM similar_group_item)"
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun findDuplicateCandidatesBySha(
        assetId: Long,
        kind: MediaKind,
        contentSha256: String
    ): List<CandidateFingerprint> {
        return readableDatabase.rawQuery(
            """
            SELECT a.id, a.media_store_id, a.uri, a.type, a.name, a.width, a.height, a.duration,
                   a.size, a.created_at, a.updated_at, a.bucket, a.path_hint,
                   f.image_hash, f.color_hash, f.video_frame_hashes, f.video_frame_colors,
                   f.video_fingerprint_source
            FROM fingerprint f
            JOIN media_asset a ON a.id = f.asset_id
            WHERE a.type = ?
              AND a.state = 'ACTIVE'
              AND a.id != ?
              AND f.content_sha256 = ?
            """.trimIndent(),
            arrayOf(
                kind.name,
                assetId.toString(),
                contentSha256
            )
        ).use { cursor -> candidateFingerprintsFrom(cursor) }
    }

    /**
     * 按参考规则 duplicateReference 规则查找重复候选。
     *
     * 参考规则引用由：媒体类型 + 宽高 + imageHash + 编辑状态 + 文件大小组成。
     * 这里直接在 SQL 层完成等值过滤，不再要求 SHA-256 完全一致。
     */
    fun findDuplicateReferenceCandidates(
        assetId: Long,
        asset: MediaAsset,
        hash: CombinedHash
    ): List<CandidateFingerprint> {
        return readableDatabase.rawQuery(
            """
            SELECT a.id, a.media_store_id, a.uri, a.type, a.name, a.width, a.height, a.duration,
                   a.size, a.created_at, a.updated_at, a.bucket, a.path_hint,
                   f.image_hash, f.color_hash, f.video_frame_hashes, f.video_frame_colors,
                   f.video_fingerprint_source
            FROM media_asset a
            JOIN fingerprint f ON f.asset_id = a.id
            WHERE a.type = ?
              AND a.state = 'ACTIVE'
              AND a.id != ?
              AND a.size = ?
              AND a.width = ?
              AND a.height = ?
              AND a.is_edited = ?
              AND f.image_hash = ?
            """.trimIndent(),
            arrayOf(
                asset.kind.name,
                assetId.toString(),
                asset.size.toString(),
                asset.width.toString(),
                asset.height.toString(),
                if (asset.isEdited) "1" else "0",
                hash.imageHash.toString()
            )
        ).use { cursor -> candidateFingerprintsFrom(cursor) }
    }

    fun contentShaForAsset(assetId: Long): String? {
        return readableDatabase.rawQuery(
            "SELECT content_sha256 FROM fingerprint WHERE asset_id=?",
            arrayOf(assetId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }

    fun updateContentSha(assetId: Long, contentSha256: String) {
        writableDatabase.update(
            "fingerprint",
            ContentValues().apply { put("content_sha256", contentSha256) },
            "asset_id=?",
            arrayOf(assetId.toString())
        )
    }

    /**
     * 加载图片 BK-Tree 和内存精判所需的索引数据。
     *
     * 早期版本这里只读取 assetId/imageHash，BK-Tree 召回后还要回 SQLite 批量读取
     * colorHash。真机 9k 图片下这部分约 18s。现在把 colorHash 一起放进内存索引：
     * BK-Tree 仍负责全库近邻召回，最终 dHash+colorHash 精判直接在内存完成，不牺牲准确性。
     */
    fun loadHashIndex(
        kind: MediaKind,
        imageFingerprintSize: Int = DEFAULT_IMAGE_FINGERPRINT_SIZE
    ): List<HashIndexEntry> {
        return readableDatabase.rawQuery(
            """
            SELECT a.id, f.image_hash, f.color_hash
            FROM fingerprint f
            JOIN media_asset a ON a.id = f.asset_id
            WHERE a.type = ?
              AND a.state = 'ACTIVE'
              AND a.fingerprint_algorithm_version = ?
              AND f.video_frame_hashes IS NULL
            """.trimIndent(),
            arrayOf(kind.name, fingerprintAlgorithmVersion(kind, imageFingerprintSize).toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        HashIndexEntry(
                            assetId = cursor.getLong(0),
                            hash = CombinedHash(
                                imageHash = cursor.getLong(1),
                                colorHash = ColorHashCodec.decode(cursor.getString(2))
                            )
                        )
                    )
                }
            }
        }
    }

    /**
     * 根据 BK-Tree 返回的 assetId 批量读取完整候选指纹。
     *
     * SQLite 单条语句可绑定参数数量有限，因此按 800 个 ID 分片查询。
     */
    fun loadCandidatesByIds(
        assetIds: Collection<Long>,
        excludedAssetId: Long
    ): List<CandidateFingerprint> {
        if (assetIds.isEmpty()) return emptyList()
        return assetIds
            .asSequence()
            .filter { it != excludedAssetId }
            .chunked(CANDIDATE_ID_CHUNK_SIZE)
            .flatMap { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                readableDatabase.rawQuery(
                    """
                    SELECT a.id, a.media_store_id, a.uri, a.type, a.name, a.width, a.height,
                           a.duration, a.size, a.created_at, a.updated_at, a.bucket, a.path_hint,
                           f.image_hash, f.color_hash, f.video_frame_hashes, f.video_frame_colors,
                           f.video_fingerprint_source
                    FROM fingerprint f
                    JOIN media_asset a ON a.id = f.asset_id
                    WHERE a.id IN ($placeholders)
                      AND a.state = 'ACTIVE'
                    """.trimIndent(),
                    chunk.map(Long::toString).toTypedArray()
                ).use { cursor -> candidateFingerprintsFrom(cursor) }.asSequence()
            }
            .toList()
    }

    fun findVideoFingerprintCandidates(
        assetId: Long,
        asset: MediaAsset,
        videoFingerprintMode: VideoFingerprintMode = VideoFingerprintMode.BALANCED
    ): List<CandidateFingerprint> {
        /*
         * 视频保存完整多帧指纹。为了避免同类型视频逐一比较时退化成近似 O(n²)，
         * 这里先用轻量元数据做候选收窄：
         * 1. 类型必须一致；
         * 2. 时长桶接近，优先排除明显不是同一段内容的视频；
         * 3. 宽高比接近，避免横屏/竖屏误召回；
         * 4. 指纹算法版本一致，防止旧算法结果参与新算法比较。
         *
         * 这些条件只用于召回，最终仍由多帧 dHash/colorHash 精判决定是否相似。
         */
        if (videoFingerprintMode == VideoFingerprintMode.REFERENCE_COMPAT) {
            return findCompetitorVideoFingerprintCandidates(assetId, asset, videoFingerprintMode)
        }
        val durationBucket = HashBuckets.durationBucket(asset.duration)
        val aspectBucket = HashBuckets.aspectBucket(asset.width, asset.height)
        return readableDatabase.rawQuery(
            """
            SELECT a.id, a.media_store_id, a.uri, a.type, a.name, a.width, a.height, a.duration,
                   a.size, a.created_at, a.updated_at, a.bucket, a.path_hint,
                   f.image_hash, f.color_hash, f.video_frame_hashes, f.video_frame_colors,
                   f.video_fingerprint_source
            FROM fingerprint f
            JOIN media_asset a ON a.id = f.asset_id
            WHERE a.type = ?
              AND a.state = 'ACTIVE'
              AND a.fingerprint_algorithm_version = ?
              AND a.id != ?
              AND f.video_frame_hashes IS NOT NULL
              AND ABS(f.duration_bucket - ?) <= ?
              AND ABS(f.aspect_bucket - ?) <= ?
            ORDER BY
              ABS(f.duration_bucket - ?) ASC,
              ABS(f.aspect_bucket - ?) ASC
            """.trimIndent(),
            arrayOf(
                asset.kind.name,
                fingerprintAlgorithmVersion(asset.kind, DEFAULT_IMAGE_FINGERPRINT_SIZE, videoFingerprintMode).toString(),
                assetId.toString(),
                durationBucket.toString(),
                videoDurationTolerance(asset.duration).toString(),
                aspectBucket.toString(),
                VIDEO_ASPECT_BUCKET_TOLERANCE.toString(),
                durationBucket.toString(),
                aspectBucket.toString()
            )
        ).use { cursor -> candidateFingerprintsFrom(cursor) }
    }

    private fun findCompetitorVideoFingerprintCandidates(
        assetId: Long,
        asset: MediaAsset,
        videoFingerprintMode: VideoFingerprintMode
    ): List<CandidateFingerprint> {
        return readableDatabase.rawQuery(
            """
            SELECT a.id, a.media_store_id, a.uri, a.type, a.name, a.width, a.height, a.duration,
                   a.size, a.created_at, a.updated_at, a.bucket, a.path_hint,
                   f.image_hash, f.color_hash, f.video_frame_hashes, f.video_frame_colors,
                   f.video_fingerprint_source
            FROM fingerprint f
            JOIN media_asset a ON a.id = f.asset_id
            WHERE a.type = ?
              AND a.state = 'ACTIVE'
              AND a.fingerprint_algorithm_version = ?
              AND a.id != ?
              AND f.video_frame_hashes IS NOT NULL
            ORDER BY a.date_added DESC
            """.trimIndent(),
            arrayOf(
                asset.kind.name,
                fingerprintAlgorithmVersion(asset.kind, DEFAULT_IMAGE_FINGERPRINT_SIZE, videoFingerprintMode).toString(),
                assetId.toString()
            )
        ).use { cursor -> candidateFingerprintsFrom(cursor) }
    }

    fun linkDuplicateAssets(type: MediaKind, firstAssetId: Long, secondAssetId: Long) {
        linkAssets(GroupCategory.DUPLICATE, type, firstAssetId, secondAssetId)
    }

    fun linkSimilarAssets(type: MediaKind, firstAssetId: Long, secondAssetId: Long) {
        linkAssets(GroupCategory.SIMILAR, type, firstAssetId, secondAssetId)
    }

    private fun linkAssets(category: GroupCategory, type: MediaKind, firstAssetId: Long, secondAssetId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 用户正在删除的资源不能被后台扫描重新加入任何分组。
            if (!areAssetsActive(db, firstAssetId, secondAssetId)) return

            if (category == GroupCategory.DUPLICATE) {
                /*
                 * “相同”是“相似”的更高优先级结果。同一资源只能属于其中一个分类。
                 * CSV 中 4 张截图同时位于两个分类，正是首页合计 214 -> 218 的原因。
                 */
                removeAssetsFromCategory(
                    db,
                    setOf(firstAssetId, secondAssetId),
                    GroupCategory.SIMILAR,
                    type
                )
                cleanupGroupsWithLessThanTwoAssets(db)
            } else if (
                groupIdForAsset(db, firstAssetId, GroupCategory.DUPLICATE, type) != null ||
                groupIdForAsset(db, secondAssetId, GroupCategory.DUPLICATE, type) != null
            ) {
                // 后续增量扫描不能把已经进入“相同”的资源重新加入“相似”。
                return
            }

            val firstGroup = groupIdForAsset(db, firstAssetId, category, type)
            val secondGroup = groupIdForAsset(db, secondAssetId, category, type)
            val targetGroup = when {
                firstGroup != null && secondGroup != null && firstGroup != secondGroup -> {
                    /*
                     * 两个已经稳定的组不能因为一条跨组相似边被整体合并。否则 A≈B、B≈C
                     * 会把 A 与 C 也放进同一组，截图列表会被单链式关系不断放大。
                     * Duplicate 分支此前可能已移除 Similar 关系，因此仍需提交事务。
                     */
                    db.setTransactionSuccessful()
                    return
                }
                firstGroup != null -> firstGroup
                secondGroup != null -> secondGroup
                else -> db.insert(
                    "similar_group",
                    null,
                    ContentValues().apply {
                        put("category", category.name)
                        put("type", type.name)
                        put("updated_at", System.currentTimeMillis())
                    }
                )
            }
            insertGroupItem(targetGroup, firstAssetId)
            insertGroupItem(targetGroup, secondAssetId)
            db.update(
                "similar_group",
                ContentValues().apply { put("updated_at", System.currentTimeMillis()) },
                "id=?",
                arrayOf(targetGroup.toString())
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun removeAssetsFromCategory(
        db: SqlDb,
        assetIds: Set<Long>,
        category: GroupCategory,
        kind: MediaKind
    ) {
        if (assetIds.isEmpty()) return
        val placeholders = assetIds.joinToString(",") { "?" }
        val args = buildList {
            addAll(assetIds.map(Long::toString))
            add(category.name)
            add(kind.name)
        }.toTypedArray()
        db.execSQL(
            """
            DELETE FROM similar_group_item
            WHERE asset_id IN ($placeholders)
              AND group_id IN (
                  SELECT id FROM similar_group WHERE category=? AND type=?
              )
            """.trimIndent(),
            args
        )
    }

    private fun cleanupGroupsWithLessThanTwoAssets(db: SqlDb) {
        db.execSQL(
            """
            DELETE FROM similar_group
            WHERE id IN (
                SELECT g.id
                FROM similar_group g
                LEFT JOIN similar_group_item i ON i.group_id = g.id
                GROUP BY g.id
                HAVING COUNT(i.asset_id) < 2
            )
            """.trimIndent()
        )
    }

    fun loadGroups(
        groupLimit: Int,
        assetLimitPerGroup: Int = Int.MAX_VALUE
    ): List<SimilarGroup> {
        val matchedGroups = loadMatchedGroups(groupLimit, assetLimitPerGroup)
        val otherGroups = loadOtherGroups(assetLimitPerGroup)
        return matchedGroups + otherGroups
    }

    fun loadGroups(
        productCategoryType: ProductCategoryType,
        groupLimit: Int,
        assetLimitPerGroup: Int = Int.MAX_VALUE
    ): List<SimilarGroup> {
        return when (productCategoryType) {
            ProductCategoryType.SIMILAR ->
                loadMatchedGroups(
                    groupLimit,
                    assetLimitPerGroup,
                    setOf(GroupCategory.SIMILAR),
                    setOf(MediaKind.PHOTO)
                )
            ProductCategoryType.DUPLICATES ->
                loadMatchedGroups(
                    groupLimit,
                    assetLimitPerGroup,
                    setOf(GroupCategory.DUPLICATE),
                    setOf(MediaKind.PHOTO, MediaKind.SCREENSHOT)
                )
            ProductCategoryType.SIMILAR_SCREENSHOTS ->
                loadMatchedGroups(
                    groupLimit,
                    assetLimitPerGroup,
                    setOf(GroupCategory.SIMILAR),
                    setOf(MediaKind.SCREENSHOT)
                )
            ProductCategoryType.SIMILAR_VIDEOS ->
                loadMatchedGroups(
                    groupLimit,
                    assetLimitPerGroup,
                    setOf(GroupCategory.SIMILAR),
                    setOf(MediaKind.VIDEO)
                )
            ProductCategoryType.SIMILAR_SCREEN_RECORDINGS ->
                loadMatchedGroups(
                    groupLimit,
                    assetLimitPerGroup,
                    setOf(GroupCategory.SIMILAR),
                    setOf(MediaKind.SCREEN_RECORDING)
                )
            ProductCategoryType.OTHER_SCREENSHOTS ->
                listOfOtherGroup(
                    title = "Other Screenshots",
                    groupKind = MediaKind.SCREENSHOT,
                    kinds = listOf(MediaKind.SCREENSHOT),
                    excludedCategories = setOf(GroupCategory.SIMILAR, GroupCategory.DUPLICATE),
                    assetLimit = assetLimitPerGroup
                )
            ProductCategoryType.CHAT_PHOTOS ->
                listOfOtherGroup(
                    title = "Chat Photos",
                    groupKind = MediaKind.PHOTO,
                    kinds = listOf(MediaKind.PHOTO),
                    excludedCategories = setOf(GroupCategory.SIMILAR, GroupCategory.DUPLICATE),
                    assetLimit = assetLimitPerGroup,
                    chatFilter = ChatFilter.CHAT_ONLY
                )
            ProductCategoryType.OTHER_SCREEN_RECORDINGS ->
                listOfOtherGroup(
                    title = "Other Screen Recordings",
                    groupKind = MediaKind.SCREEN_RECORDING,
                    kinds = listOf(MediaKind.SCREEN_RECORDING),
                    excludedCategories = setOf(GroupCategory.SIMILAR),
                    assetLimit = assetLimitPerGroup
                )
            ProductCategoryType.OTHER_VIDEOS ->
                listOfOtherGroup(
                    title = "Other Videos",
                    groupKind = MediaKind.VIDEO,
                    kinds = listOf(MediaKind.VIDEO),
                    excludedCategories = setOf(GroupCategory.SIMILAR),
                    assetLimit = assetLimitPerGroup
                )
            ProductCategoryType.OTHER ->
                listOfOtherGroup(
                    title = "Other Photos",
                    groupKind = MediaKind.PHOTO,
                    kinds = listOf(MediaKind.PHOTO),
                    excludedCategories = setOf(GroupCategory.SIMILAR, GroupCategory.DUPLICATE),
                    assetLimit = assetLimitPerGroup,
                    chatFilter = ChatFilter.NON_CHAT
                )
        }
    }

    private fun loadMatchedGroups(
        groupLimit: Int,
        assetLimitPerGroup: Int,
        categoryFilter: Set<GroupCategory> = emptySet(),
        kindFilter: Set<MediaKind> = emptySet()
    ): List<SimilarGroup> {
        val args = mutableListOf<String>()
        val categorySql = inClause("g.category", categoryFilter.map { it.name }, args)
        val kindSql = inClause("g.type", kindFilter.map { it.name }, args)
        args += groupLimit.toString()
        return readableDatabase.rawQuery(
            """
            SELECT g.id, g.category, g.type, COUNT(i.asset_id) AS c, SUM(a.size) AS total_size, MIN(a.created_at) AS oldest
            FROM similar_group g
            JOIN similar_group_item i ON i.group_id = g.id
            JOIN media_asset a ON a.id = i.asset_id
            WHERE a.state='ACTIVE'
              $categorySql
              $kindSql
            GROUP BY g.id
            HAVING c > 1
            ORDER BY g.category ASC, c DESC, g.updated_at DESC
            LIMIT ?
            """.trimIndent(),
            args.toTypedArray()
        ).use { cursor ->
            val groups = mutableListOf<SimilarGroup>()
            while (cursor.moveToNext()) {
                val groupId = cursor.getLong(0)
                val category = GroupCategory.valueOf(cursor.getString(1))
                val kind = MediaKind.valueOf(cursor.getString(2))
                val count = cursor.getInt(3)
                val totalSize = cursor.getLong(4)
                val oldest = DATE_FORMAT.format(Date(cursor.getLong(5)))
                val assets = loadGroupAssets(groupId, assetLimitPerGroup)
                groups += SimilarGroup(
                    id = groupId,
                    title = titleFor(category, kind),
                    subtitle = "$count assets · ${FormatUtils.formatBytes(totalSize)} · oldest $oldest",
                    category = category,
                    kind = kind,
                    assets = assets,
                    totalAssetCount = count,
                    totalSizeBytes = totalSize
                )
            }
            groups
        }
    }

    private fun loadOtherGroups(assetLimitPerGroup: Int): List<SimilarGroup> {
        val result = mutableListOf<SimilarGroup>()
        addOtherGroup(
            result,
            "Other Photos",
            MediaKind.PHOTO,
            listOf(MediaKind.PHOTO),
            setOf(GroupCategory.SIMILAR, GroupCategory.DUPLICATE),
            assetLimit = assetLimitPerGroup,
            chatFilter = ChatFilter.NON_CHAT
        )
        addOtherGroup(
            result,
            "Chat Photos",
            MediaKind.PHOTO,
            listOf(MediaKind.PHOTO),
            setOf(GroupCategory.SIMILAR, GroupCategory.DUPLICATE),
            assetLimit = assetLimitPerGroup,
            chatFilter = ChatFilter.CHAT_ONLY
        )
        addOtherGroup(
            result,
            "Other Screenshots",
            MediaKind.SCREENSHOT,
            listOf(MediaKind.SCREENSHOT),
            setOf(GroupCategory.SIMILAR, GroupCategory.DUPLICATE),
            assetLimit = assetLimitPerGroup
        )
        addOtherGroup(
            result,
            "Other Videos",
            MediaKind.VIDEO,
            listOf(MediaKind.VIDEO),
            setOf(GroupCategory.SIMILAR),
            assetLimit = assetLimitPerGroup
        )
        addOtherGroup(
            result,
            "Other Screen Recordings",
            MediaKind.SCREEN_RECORDING,
            listOf(MediaKind.SCREEN_RECORDING),
            setOf(GroupCategory.SIMILAR),
            assetLimit = assetLimitPerGroup
        )
        return result
    }

    private fun listOfOtherGroup(
        title: String,
        groupKind: MediaKind,
        kinds: List<MediaKind>,
        excludedCategories: Set<GroupCategory>,
        assetLimit: Int,
        chatFilter: ChatFilter = ChatFilter.ANY
    ): List<SimilarGroup> {
        val result = mutableListOf<SimilarGroup>()
        addOtherGroup(
            result = result,
            title = title,
            groupKind = groupKind,
            kinds = kinds,
            excludedCategories = excludedCategories,
            assetLimit = assetLimit,
            chatFilter = chatFilter
        )
        return result
    }

    private fun addOtherGroup(
        result: MutableList<SimilarGroup>,
        title: String,
        groupKind: MediaKind,
        kinds: List<MediaKind>,
        excludedCategories: Set<GroupCategory>,
        assetLimit: Int,
        chatFilter: ChatFilter = ChatFilter.ANY
    ) {
        val stats = loadAssetStats(
            kinds = kinds,
            excludedCategories = excludedCategories,
            chatFilter = chatFilter
        )
        if (stats.count == 0) return
        val assets = loadAssets(
            kinds = kinds,
            excludedCategories = excludedCategories,
            assetLimit = assetLimit,
            chatFilter = chatFilter
        )
        result += SimilarGroup(
            title = title,
            subtitle = "${stats.count} assets · ${FormatUtils.formatBytes(stats.totalSize)}",
            category = GroupCategory.OTHER,
            kind = groupKind,
            assets = assets,
            totalAssetCount = stats.count,
            totalSizeBytes = stats.totalSize
        )
    }

    fun groupCount(): Int {
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM similar_group", emptyArray()).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun assetCount(): Int {
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM media_asset WHERE state='ACTIVE'",
            emptyArray()
        ).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun findAssetId(mediaStoreId: Long, kind: MediaKind): Long? {
        return readableDatabase.rawQuery(
            "SELECT id FROM media_asset WHERE media_store_id=? AND type=?",
            arrayOf(mediaStoreId.toString(), kind.name)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }

    private fun assetStateRevisionAndFingerprint(assetId: Long): AssetScanSnapshot? {
        return readableDatabase.rawQuery(
            """
            SELECT a.state, a.revision, a.source_signature, a.fingerprint_algorithm_version,
                   CASE WHEN f.asset_id IS NULL THEN 0 ELSE 1 END
            FROM media_asset a
            LEFT JOIN fingerprint f ON f.asset_id = a.id
            WHERE a.id=?
            """.trimIndent(),
            arrayOf(assetId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                AssetScanSnapshot(
                    state = cursor.getString(0),
                    revision = cursor.getLong(1),
                    sourceSignature = cursor.getString(2),
                    algorithmVersion = cursor.getInt(3),
                    hasFingerprint = cursor.getInt(4) == 1
                )
            } else {
                null
            }
        }
    }

    private fun isTokenActive(db: SqlDb, token: AssetScanToken): Boolean {
        return db.rawQuery(
            "SELECT 1 FROM media_asset WHERE id=? AND state='ACTIVE' AND revision=?",
            arrayOf(token.assetId.toString(), token.revision.toString())
        ).use { it.moveToFirst() }
    }

    private fun areAssetsActive(db: SqlDb, firstId: Long, secondId: Long): Boolean {
        return db.rawQuery(
            "SELECT COUNT(*) FROM media_asset WHERE id IN (?, ?) AND state='ACTIVE'",
            arrayOf(firstId.toString(), secondId.toString())
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 2 }
    }

    private fun groupIdForAsset(
        db: SqlDb,
        assetId: Long,
        category: GroupCategory,
        kind: MediaKind
    ): Long? {
        return db.rawQuery(
            """
            SELECT i.group_id
            FROM similar_group_item i
            JOIN similar_group g ON g.id = i.group_id
            WHERE i.asset_id=? AND g.category=? AND g.type=?
            LIMIT 1
            """.trimIndent(),
            arrayOf(assetId.toString(), category.name, kind.name)
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    private fun candidateFingerprintsFrom(cursor: android.database.Cursor): List<CandidateFingerprint> {
        val result = mutableListOf<CandidateFingerprint>()
        while (cursor.moveToNext()) {
            result += CandidateFingerprint(
                assetId = cursor.getLong(0),
                asset = assetFromCursor(cursor, 1),
                hash = CombinedHash(
                    imageHash = cursor.getLong(13),
                    colorHash = ColorHashCodec.decode(cursor.getString(14))
                ),
                videoFingerprint = if (cursor.isNull(15) || cursor.isNull(16)) {
                    null
                } else {
                    VideoFingerprintCodec.decode(
                        hashes = cursor.getString(15),
                        colors = cursor.getString(16),
                        source = if (cursor.isNull(17)) null else cursor.getString(17)
                    )
                }
            )
        }
        return result
    }

    private fun insertGroupItem(groupId: Long, assetId: Long) {
        insertGroupItem(writableDatabase, groupId, assetId)
    }

    private fun insertGroupItem(db: SqlDb, groupId: Long, assetId: Long) {
        db.insertWithOnConflict(
            "similar_group_item",
            null,
            ContentValues().apply {
                put("group_id", groupId)
                put("asset_id", assetId)
            },
            SqlDb.CONFLICT_IGNORE
        )
    }

    private fun insertGroupItem(statement: SupportSQLiteStatement, groupId: Long, assetId: Long) {
        statement.clearBindings()
        statement.bindLong(1, groupId)
        statement.bindLong(2, assetId)
        statement.executeInsert()
    }

    private fun loadGroupAssets(
        groupId: Long,
        assetLimit: Int
    ): List<MediaAsset> {
        return loadPagedAssets(
            sqlWithoutLimit = """
            SELECT a.media_store_id, a.uri, a.type, a.name, a.width, a.height, a.duration,
                   a.size, a.created_at, a.updated_at, a.bucket, a.path_hint,
                   a.mime_type, a.is_favorite, a.is_edited, a.generation_added,
                   a.generation_modified, a.chat_source, f.quality_score, f.content_sha256
            FROM similar_group_item i
            JOIN media_asset a ON a.id = i.asset_id
            LEFT JOIN fingerprint f ON f.asset_id = a.id
            WHERE i.group_id=?
              AND a.state='ACTIVE'
            /*
             * 展示层按媒体时间倒序呈现扫描结果，与系统相册“最新在前”的浏览习惯一致。
             * 相似分组的锚点顺序仍由扫描/重建阶段决定，这里只影响 UI 读取顺序。
             */
            ORDER BY a.created_at DESC, a.date_added DESC, a.media_store_id DESC
            """.trimIndent(),
            args = listOf(groupId.toString()),
            maxRows = assetLimit
        )
    }

    private fun loadAssets(
        kinds: List<MediaKind>,
        excludedCategories: Set<GroupCategory>,
        assetLimit: Int,
        chatFilter: ChatFilter = ChatFilter.ANY
    ): List<MediaAsset> {
        val (whereSql, args) = assetWhereClause(kinds, excludedCategories, chatFilter)
        return loadPagedAssets(
            sqlWithoutLimit = """
            SELECT a.media_store_id, a.uri, a.type, a.name, a.width, a.height, a.duration,
                   a.size, a.created_at, a.updated_at, a.bucket, a.path_hint,
                   a.mime_type, a.is_favorite, a.is_edited, a.generation_added,
                   a.generation_modified, a.chat_source, f.quality_score, f.content_sha256
            FROM media_asset a
            LEFT JOIN fingerprint f ON f.asset_id = a.id
            WHERE $whereSql
            ORDER BY a.created_at DESC, a.date_added DESC, a.media_store_id DESC
            """.trimIndent(),
            args = args,
            maxRows = assetLimit
        )
    }

    /**
     * 分批读取完整资源集合，避免 Other 等大分类一次性塞满 CursorWindow。
     *
     * 这里不限制最终返回数量，只限制每次 CursorWindow 承载的行数；外部仍能拿到完整分类数据。
     * 排序 SQL 由调用方提供，分页只是按相同顺序连续取下一页。
     */
    private fun loadPagedAssets(
        sqlWithoutLimit: String,
        args: List<String>,
        maxRows: Int = Int.MAX_VALUE
    ): List<MediaAsset> {
        if (maxRows <= 0) return emptyList()
        val assets = mutableListOf<MediaAsset>()
        var offset = 0
        while (true) {
            val pageSize = minOf(ASSET_QUERY_PAGE_SIZE, maxRows - assets.size)
            val page = readableDatabase.rawQuery(
                """
                $sqlWithoutLimit
                LIMIT ? OFFSET ?
                """.trimIndent(),
                (args + listOf(pageSize.toString(), offset.toString())).toTypedArray()
            ).use { cursor ->
                val pageAssets = mutableListOf<MediaAsset>()
                while (cursor.moveToNext()) pageAssets += assetFromCursor(cursor, 0, true)
                pageAssets
            }
            assets += page
            if (page.size < pageSize || assets.size >= maxRows) break
            offset += page.size
        }
        return assets
    }

    private fun loadAssetStats(
        kinds: List<MediaKind>,
        excludedCategories: Set<GroupCategory>,
        chatFilter: ChatFilter = ChatFilter.ANY
    ): AssetStats {
        val (whereSql, args) = assetWhereClause(kinds, excludedCategories, chatFilter)
        return readableDatabase.rawQuery(
            """
            SELECT COUNT(*), COALESCE(SUM(a.size), 0)
            FROM media_asset a
            WHERE $whereSql
            """.trimIndent(),
            args.toTypedArray()
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                AssetStats(
                    count = cursor.getInt(0),
                    totalSize = cursor.getLong(1)
                )
            } else {
                AssetStats(0, 0L)
            }
        }
    }

    /**
     * Other 分类统计和资源列表共用同一段过滤条件，避免首页数量和详情列表口径不一致。
     */
    private fun inClause(
        column: String,
        values: List<String>,
        args: MutableList<String>
    ): String {
        if (values.isEmpty()) return ""
        val placeholders = values.joinToString(",") {
            args += it
            "?"
        }
        return "AND $column IN ($placeholders)"
    }

    private fun assetWhereClause(
        kinds: List<MediaKind>,
        excludedCategories: Set<GroupCategory>,
        chatFilter: ChatFilter = ChatFilter.ANY
    ): Pair<String, List<String>> {
        val args = mutableListOf<String>()
        val placeholders = kinds.joinToString(",") {
            args += it.name
            "?"
        }
        val excludeSql = if (excludedCategories.isNotEmpty()) {
            val categoryPlaceholders = excludedCategories.joinToString(",") {
                args += it.name
                "?"
            }
            """
            AND a.id NOT IN (
                SELECT i.asset_id
                FROM similar_group_item i
                JOIN similar_group g ON g.id = i.group_id
                WHERE g.category IN ($categoryPlaceholders)
            )
            """.trimIndent()
        } else {
            ""
        }
        val chatSql = when (chatFilter) {
            ChatFilter.ANY -> ""
            ChatFilter.CHAT_ONLY -> "AND a.chat_source IS NOT NULL"
            ChatFilter.NON_CHAT -> "AND a.chat_source IS NULL"
        }
        return """
            a.type IN ($placeholders)
              AND a.state='ACTIVE'
              $excludeSql
              $chatSql
        """.trimIndent() to args
    }

    private fun assetFromCursor(
        cursor: android.database.Cursor,
        offset: Int,
        extended: Boolean = false
    ): MediaAsset {
        return MediaAsset(
            id = cursor.getLong(offset),
            uri = Uri.parse(cursor.getString(offset + 1)),
            kind = MediaKind.valueOf(cursor.getString(offset + 2)),
            name = cursor.getString(offset + 3),
            width = cursor.getInt(offset + 4),
            height = cursor.getInt(offset + 5),
            duration = cursor.getLong(offset + 6),
            size = cursor.getLong(offset + 7),
            createdAt = Date(cursor.getLong(offset + 8)),
            updatedAt = Date(cursor.getLong(offset + 9)),
            bucket = cursor.getString(offset + 10) ?: "",
            pathHint = cursor.getString(offset + 11) ?: "",
            mimeType = if (extended) cursor.getString(offset + 12) ?: "" else "",
            isFavorite = extended && cursor.getInt(offset + 13) == 1,
            isEdited = extended && cursor.getInt(offset + 14) == 1,
            generationAdded = if (extended) cursor.getLong(offset + 15) else 0L,
            generationModified = if (extended) cursor.getLong(offset + 16) else 0L,
            chatSource = if (extended) cursor.getString(offset + 17) else null,
            qualityScore = if (extended) cursor.getDouble(offset + 18) else 0.0,
            contentSha256 = if (extended) cursor.getString(offset + 19) else null
        )
    }

    private fun titleFor(category: GroupCategory, kind: MediaKind): String {
        return when (category) {
            GroupCategory.DUPLICATE -> when (kind) {
                MediaKind.PHOTO -> "Duplicate Photos"
                MediaKind.SCREENSHOT -> "Duplicate Screenshots"
                MediaKind.VIDEO -> "Duplicate Videos"
                MediaKind.SCREEN_RECORDING -> "Duplicate Screen Recordings"
            }
            GroupCategory.SIMILAR -> when (kind) {
                MediaKind.PHOTO -> "Similar Photos"
                MediaKind.SCREENSHOT -> "Similar Screenshots"
                MediaKind.VIDEO -> "Similar Videos"
                MediaKind.SCREEN_RECORDING -> "Similar Screen Recordings"
            }
            GroupCategory.OTHER -> when (kind) {
                MediaKind.PHOTO -> "Other Photos"
                MediaKind.SCREENSHOT -> "Other Screenshots"
                MediaKind.VIDEO -> "Other Videos"
                MediaKind.SCREEN_RECORDING -> "Other Screen Recordings"
            }
        }
    }

    companion object {
        /*
         * v27 将图片指纹尺寸和视频指纹模式纳入 fingerprint_algorithm_version，支持 SDK
         * 请求侧配置 imageFingerprintSize/videoFingerprintMode；同时将 Duplicate SHA-256
         * 默认延后计算，避免扫描主链路读全文件。
         *
         * v26 在保持图片指纹输入 256 的前提下，Android 10+ 优先使用
         * ContentResolver.loadThumbnail(asset.uri, 256) 加载指纹 Bitmap，失败后再回退
         * MediaStore.Images.Thumbnails。输入来源变化可能改变 dHash/colorHash 结果，因此
         * 提升 fingerprint_algorithm_version，避免新旧指纹混用。
         *
         * v25 将图片扫描指纹输入从 512 统一压到 256，并让最终 Similar 分组重建
         * 优先复用扫描阶段写入的候选关系。
         *
         * 历史优化：
         * 1. 全量扫描只校验 MediaStore 完整性，不再强制重算未变化资源的指纹。
         * 2. 候选召回和分组重建只使用当前算法版本的 fingerprint，避免旧结果混入。
         * 3. 视频候选先按时长桶、宽高比桶收窄，再进入多帧精判。
         * 4. 新增索引降低 duplicateReference、视频候选召回和分组阶段的 SQL 成本。
         * 5. 视频指纹模式可配置；宿主产品可使用参考帧模式抽取 7 到 13 帧。
         * 6. 图片指纹优先使用 MediaStore.Images.Thumbnails，以复用系统缩略图缓存。
         * 7. 完整清晰度/曝光质量分不再阻塞首次扫描主链路。
         */
        private const val CANDIDATE_ID_CHUNK_SIZE = 800
        private const val ASSET_QUERY_PAGE_SIZE = 500
        private const val FINGERPRINT_ALGORITHM_VERSION = 27
        private const val DEFAULT_IMAGE_FINGERPRINT_SIZE = 256
        private const val VIDEO_ASPECT_BUCKET_TOLERANCE = 8
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        private fun fingerprintAlgorithmVersion(
            kind: MediaKind,
            imageFingerprintSize: Int,
            videoFingerprintMode: VideoFingerprintMode = VideoFingerprintMode.BALANCED
        ): Int {
            if (kind == MediaKind.VIDEO || kind == MediaKind.SCREEN_RECORDING) {
                return FINGERPRINT_ALGORITHM_VERSION * 100 + videoFingerprintMode.ordinal
            }
            /*
             * 图片 dHash/colorHash 会随指纹输入尺寸变化。把尺寸编码进算法版本，避免不同
             * 产品配置下复用旧 fingerprint。乘数留出后续基础算法版本增长空间。
             */
            return FINGERPRINT_ALGORITHM_VERSION * 10_000 + imageFingerprintSize.coerceIn(1, 9_999)
        }

        private fun videoDurationTolerance(durationMs: Long): Long {
            val durationBucket = HashBuckets.durationBucket(durationMs)
            return maxOf(2L, durationBucket / 10L)
        }
    }
}

data class CandidateFingerprint(
    val assetId: Long,
    val asset: MediaAsset,
    val hash: CombinedHash,
    val videoFingerprint: VideoFingerprint? = null
)

private data class AssetStats(
    val count: Int,
    val totalSize: Long
)

/**
 * Other Photos 需要进一步拆分普通图片和聊天图片。
 *
 * chat_source 在媒体枚举阶段由文件名、相册名和路径提示预计算并落库，
 * 这里直接走 SQL 过滤，避免首页为了统计数量加载全部 Other 图片。
 */
private enum class ChatFilter {
    ANY,
    CHAT_ONLY,
    NON_CHAT
}

data class HashIndexEntry(
    val assetId: Long,
    val hash: CombinedHash
) {
    val imageHash: Long
        get() = hash.imageHash
}

private data class AssetScanSnapshot(
    val state: String,
    val revision: Long,
    val sourceSignature: String?,
    val algorithmVersion: Int,
    val hasFingerprint: Boolean
)

private data class GroupingFingerprint(
    val assetId: Long,
    val hash: CombinedHash,
    val videoFingerprint: VideoFingerprint?
) {
    /**
     * 视频每一张有效帧都进入 BK-Tree，BK-Tree 仅用于召回，最终仍执行完整跨帧比较。
     */
    val indexHashes: List<Long>
        get() = videoFingerprint
            ?.frames
            ?.filter(CombinedHash::isValid)
            ?.map(CombinedHash::imageHash)
            ?: listOf(hash.imageHash)
}

/**
 * 扫描计算使用的乐观锁令牌。
 *
 * 用户删除或恢复资源时 revision 会递增，旧令牌无法提交指纹或修改分组。
 */
data class AssetScanToken(
    val assetId: Long,
    val revision: Long,
    val needsFingerprint: Boolean
)

object SourceSignature {
    /**
     * 用稳定的 MediaStore 元数据描述“内容是否变化”。
     *
     * 不把文件名、bucket 放入签名：这些字段只影响展示，不应该导致 dHash/colorHash
     * 或视频多帧指纹重算。收藏/编辑状态会影响 qualityScore 和 Best 排序，因此保留在
     * 签名中。generationModified 在 Android 11+ 更可靠；API 23-29 使用
     * updatedAt/size/宽高/时长兜底。
     */
    fun from(asset: MediaAsset): String {
        return listOf(
            asset.id,
            asset.kind.name,
            asset.size,
            asset.width,
            asset.height,
            asset.duration,
            asset.updatedAt.time,
            asset.generationModified,
            asset.mimeType,
            asset.isFavorite,
            asset.isEdited
        ).joinToString("|")
    }
}

object HashBuckets {
    fun hashPrefix(hash: Long): Int = ((hash ushr 48) and 0xFFFF).toInt()

    fun aspectBucket(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 0
        return ((width.toDouble() / height.toDouble()) * 100).toInt()
    }

    fun durationBucket(duration: Long): Long = duration / 1000L

    fun sizeBucket(size: Long): Long = size / 1_048_576L

    /**
     * 重复识别使用的稳定组合标识。
     *
     * 这里统一使用“宽x高_文件大小”的稳定格式，避免加入时间、hash 高位等易变字段后
     * 产生不稳定的分桶结果。
     */
    fun potentialIdentifier(asset: MediaAsset): String {
        return "${asset.width}x${asset.height}_${asset.size}"
    }
}

object ColorHashCodec {
    fun encode(hash: Array<DoubleArray>): String {
        return hash.joinToString(";") { row -> row.joinToString(",") }
    }

    fun decode(value: String): Array<DoubleArray> {
        if (value.isBlank()) return emptyArray()
        return value.split(";").map { row ->
            row.split(",").map { it.toDoubleOrNull() ?: 0.0 }.toDoubleArray()
        }.toTypedArray()
    }
}

object VideoFingerprintCodec {
    private const val INVALID_COLOR_MARKER = "~"

    fun encodeHashes(fingerprint: VideoFingerprint): String {
        return fingerprint.frames.joinToString(",") { it.imageHash.toString() }
    }

    fun encodeColors(fingerprint: VideoFingerprint): String {
        return fingerprint.frames.joinToString("|") { frame ->
            if (frame.colorHash.isEmpty()) {
                INVALID_COLOR_MARKER
            } else {
                ColorHashCodec.encode(frame.colorHash)
            }
        }
    }

    fun decode(hashes: String, colors: String, source: String? = null): VideoFingerprint? {
        val hashValues = hashes.split(",").mapNotNull { it.toLongOrNull() }
        val colorValues = colors.split("|").map { encoded ->
            if (encoded == INVALID_COLOR_MARKER) emptyArray() else ColorHashCodec.decode(encoded)
        }
        if (hashValues.isEmpty() || hashValues.size != colorValues.size) return null
        val frames = hashValues.indices.map { index ->
            CombinedHash(hashValues[index], colorValues[index])
        }
        val decodedSource = source
            ?.let { runCatching { VideoFingerprintSource.valueOf(it) }.getOrNull() }
            ?: if (frames.count(CombinedHash::isValid) <= 1) {
                VideoFingerprintSource.SYSTEM_THUMBNAIL
            } else {
                VideoFingerprintSource.MMR_FRAMES
            }
        return VideoFingerprint(
            frames = frames,
            source = decodedSource
        )
    }
}
