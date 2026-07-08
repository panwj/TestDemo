# 相似扫描库性能问题分析

## 1. 问题结论

当前 demo 的相似扫描耗时增长不只是图片 dHash/colorHash 计算慢，而是多段成本叠加后在大资源库上被放大：

1. 首扫或算法版本变化时，所有资源都会 `upsertAsset -> prepareAssetForRescan -> 指纹计算 -> 串行提交 -> 分组写入`，SQLite 小事务数量随资源数和相似边数量一起增长。
2. 旧版 demo 扫描服务曾强制使用 `VideoFingerprintMode.REFERENCE_COMPAT`，每个视频抽 7 到 13 帧，并且候选查询更宽；当前已改为 `ADAPTIVE_BALANCED`，用于规避系统缩略图慢失败和参考帧全量抽取两类极端成本。
3. 扫描完成阶段会执行 `rebuildSimilarGroups(changedKinds)`，对变化过的类型读取全部已完成指纹并重建相似分组；当 `changedKinds` 包含 PHOTO/SCREENSHOT/VIDEO 时，尾段会随全库规模增长。
4. 全量扫描虽然可复用旧 fingerprint，但每个资源仍会执行一次 `media_asset` 查询和更新；大库下即使 `fingerprinted` 很少，`visited` 很大也有稳定耗时。

用用户给出的实测数据看：

| 资源数 | 当前 demo | 竞品 | 判断 |
| --- | ---: | ---: | --- |
| 1w | 2min | 2min | 基础计算能力接近，可接受 |
| 3w | 15min | 3min | demo 出现明显非线性放大 |
| 5w | 35min | 20min | 大库尾段/视频/事务成本叠加明显 |

3w 从 2min 放大到 15min，说明瓶颈不是单纯 O(n) 的图片指纹计算；更像是“视频多帧 + 串行 SQLite + 分组重建 + 结果加载”共同造成的阶梯式放大。

## 2. 源码证据

### 2.1 demo 已改为自适应视频模式

位置：`app/src/main/java/com/example/similarscandemo/service/MediaScanService.kt`

```kotlin
SimilarScanRequest(
    forceFull = forceFull,
    videoFingerprintMode = VideoFingerprintMode.ADAPTIVE_BALANCED,
    enableMetricsLog = enableMetricsLog
)
```

SDK 默认值仍是 `BALANCED`，demo 覆盖为 `ADAPTIVE_BALANCED`。

`ADAPTIVE_BALANCED` 的策略：

- 优先尝试系统视频缩略图；
- 连续失败、成功率过低或单次/平均耗时过高时，本轮扫描关闭系统缩略图路径；
- 关闭后直接走 BALANCED 的 4 个 MMR 时间点；
- 不再让每个视频重复进入高概率慢失败的缩略图路径。

位置：`similar-scan-core/src/main/java/com/clean/similarscan/internal/similarity/VideoFingerprintCalculator.kt`

该模式保留 `BALANCED` 的低帧数成本，同时避免部分设备上 `ContentResolver.loadThumbnail()` 慢失败导致
`BALANCED` 反而比 `REFERENCE_COMPAT` 更慢。

### 2.2 图片计算已并发，但提交和数据库更新仍串行

位置：`similar-scan-core/src/main/java/com/clean/similarscan/internal/scanner/SimilarMediaScanner.kt`

```kotlin
private const val IMAGE_COMPUTE_THREADS = 4
private const val VIDEO_COMPUTE_THREADS = 2
```

计算任务并发，但注释也说明：

```text
数据库提交、BK-Tree 更新和分组写入仍串行执行
```

提交链路包含：

- `findDuplicateReferenceCandidates`
- `bk_tree_visual_query`
- `filter_visual_candidates_in_memory`
- `markFingerprintDone`
- `linkDuplicateAssets`
- `linkSimilarAssets`
- 更新 BK-Tree 和内存 hash cache

这意味着单张图片的 bitmap/hash 计算可以并发，但每个结果仍要回到扫描线程逐个落库、逐条写相似关系。

### 2.3 每个重算资源都会清理组，代价偏高

位置：`similar-scan-core/src/main/java/com/clean/similarscan/internal/database/ScanDatabase.kt`

`prepareAssetForRescan()` 对每个需要重算的资源执行：

```sql
DELETE FROM similar_group_item WHERE asset_id=?
DELETE FROM similar_group
WHERE id NOT IN (
    SELECT DISTINCT group_id FROM similar_group_item
)
```

这个逻辑在小库上没问题，但在首扫、算法版本变化、恢复删除状态、或大量资源重新计算时，会变成“每个资源一次组清理”。5w 资源下会产生非常多小事务和重复扫描 group 表。

### 2.4 每条相似关系写入都是事务级操作

位置：`ScanDatabase.linkAssets()`

每发现一条 Duplicate/Similar 边，都会：

- `beginTransaction()`
- 检查两资源是否 active；
- 查询两个资源已有 group；
- 可能删除 Similar 中的 Duplicate 资源；
- 插入 group 和 group_item；
- 更新 group 时间；
- `endTransaction()`

如果某些相册有大量近似截图、连拍、转发图，边数量会明显增加，写库成本会超过纯 hash 计算。

### 2.5 完成阶段会重建变化类型的相似组

位置：`SimilarMediaScanner.scan()`

```kotlin
database.rebuildSimilarGroups(changedKinds, imageFingerprintSize, videoFingerprintMode)
```

位置：`ScanDatabase.rebuildSimilarGroups()`

重建时会：

- 读取当前类型或视频族全部 DONE 指纹；
- 删除旧 Similar 分组；
- 按锚点顺序重新构建分组；
- 如果没有可复用候选图，回退 BK-Tree 召回；
- 视频还要执行多帧 `isSimilarTo()` 精判。

因此只要本轮 `changedKinds` 包含 PHOTO/SCREENSHOT/VIDEO，就不是只处理新增资源，而是会触发该类型的全量分组收敛。

### 2.6 全量扫描仍会逐资源 upsert

位置：`SimilarMediaScanner.scan()` 与 `ScanDatabase.upsertAsset()`

文档中已说明 `forceFull=true` 不是强制重算全部指纹，但每个资源仍会：

- 查询现有 asset id；
- 查询 state/revision/source_signature/fingerprint；
- 更新 `media_asset` 基础字段、`last_seen_scan`、`last_scanned_at`；
- 判断是否复用指纹。

所以后续全量校验可以省掉 bitmap/hash，但省不掉 MediaStore 枚举和数据库对账。

## 3. 最可能的耗时构成

建议把耗时分为 5 段看，不要只看总时间：

1. `MediaStore` 枚举和逐资源 `upsert_asset`
2. 图片缩略图读取：`load_fingerprint_bitmap`
3. 图片 hash：`calculate_image_hash`
4. 视频抽帧：`calculate_video_fingerprint`
5. 数据库提交和分组：`process_visual`、`process_video`、`rebuild_similar_groups`、`load_groups`

当前 `ScanMetrics` 已经会输出这些指标，关键日志 tag 是：

```text
SimilarScanMetrics
```

完成日志示例：

```text
scan=full elapsed=... visited=... fingerprinted=... reused=...
metric.upsert_asset=...
metric.load_fingerprint_bitmap=...
metric.calculate_image_hash=...
metric.calculate_video_fingerprint=...
metric.prepare_asset_for_rescan=...
metric.process_visual=...
metric.process_video=...
metric.rebuild_similar_groups=...
metric.intermediate_group_publish=...
metric.load_groups=...
metric.link_duplicate_assets=...
metric.link_similar_assets=...
count.intermediate_group_publish_count=...
count.intermediate_group_publish_first=...
count.intermediate_group_publish_first_elapsed_ms=...
count.intermediate_group_publish_first_assets=...
count.intermediate_group_publish_first_edges=...
count.intermediate_group_publish_subsequent=...
count.intermediate_group_publish_assets=...
count.intermediate_group_publish_edges=...
count.duplicate_edges=...
count.similar_edges=...
count.visual_bk_query=...
count.visual_bk_candidates=...
count.visual_bk_empty_result=...
count.visual_bk_nodes=...
metric.rebuild_load_duplicate_edges=...
metric.rebuild_delete_duplicate_groups=...
metric.rebuild_load_duplicate_fingerprints=...
metric.rebuild_build_duplicate_groups=...
metric.rebuild_load_similar_edges=...
metric.rebuild_load_existing_similar_groups=...
metric.rebuild_delete_similar_groups=...
metric.rebuild_load_similar_fingerprints=...
metric.rebuild_build_similar_groups_from_edges=...
count.rebuild_duplicate_excluded_assets=...
metric.rebuild_fallback_build_bk_tree=...
metric.rebuild_fallback_build_similar_groups=...
count.video_thumbnail_attempt=...
count.video_thumbnail_success=...
count.video_thumbnail_fail=...
count.video_thumbnail_slow=...
count.video_thumbnail_adaptive_skipped=...
```

阶段性分组发布采用“首次快、后续自适应”的策略。扫描过程中先写 `similar_candidate_edge`，中间 publish 只是在满足节流条件时，把当前候选边阶段性物化到 `similar_group` 给 UI 读取，不改变最终完成阶段的确定性重建规则。

首次 publish：

| 估算媒体总数 | 最小等待时间 | 新增扫描资源门槛 | 新增候选边门槛 |
| --- | ---: | ---: | ---: |
| `<= 500` | 1 秒 | 20 | 1 |
| `<= 2,000` | 1.5 秒 | 40 | 1 |
| `<= 10,000` | 2 秒 | 80 | 1 |
| `<= 50,000` | 3 秒 | 100 | 1 |
| `> 50,000` | 5 秒 | 200 | 1 |

首次发布必须已经产生至少 1 条候选边；如果前段资源没有 Similar/Duplicate 关系，首页会继续只显示扫描进度，不发布空分组。

后续 publish：

| 估算媒体总数 | 最小间隔 | 距离上次新增资源门槛 | 距离上次新增候选边门槛 |
| --- | ---: | ---: | ---: |
| `<= 2,000` | 30 秒 | 500 | 1,000 |
| `<= 10,000` | 45 秒 | 2,500 | 5,000 |
| `<= 50,000` | 75 秒 | 10,000 | 20,000 |
| `> 50,000` | 120 秒 | 20,000 | 40,000 |

后续发布需要先满足最小时间间隔；时间满足后，新增资源数或新增候选边数满足任一门槛即可发布。

最多中间 publish 次数：

| 估算媒体总数 | 最多中间 publish 次数 |
| --- | ---: |
| `<= 2,000` | 1 |
| `<= 10,000` | 2 |
| `<= 50,000` | 2 |
| `> 50,000` | 3 |

普通进度广播只更新数量和耗时，只有分组确实发布时才刷新首页结果列表。验证时重点看 `count.intermediate_group_publish_count`、`count.intermediate_group_publish_first_elapsed_ms`、`count.intermediate_group_publish_first_assets` 和 `count.intermediate_group_publish_first_edges`。

必须同时记录 `visited / fingerprinted / reused`。如果 3w、5w 的 `fingerprinted` 很高，说明不是复用场景，重点优化指纹和提交；如果 `reused` 很高但仍很慢，重点优化 MediaStore 对账、upsert 和最终重建。

## 4. 优化优先级

### P0：用 ADAPTIVE_BALANCED 做默认扫描，并保留三模式对照

当前 demo 已默认使用 `ADAPTIVE_BALANCED`。后续性能测试建议同时保留三组对照：

- A：`ADAPTIVE_BALANCED`
- B：`BALANCED`
- C：`REFERENCE_COMPAT`

如果 `BALANCED` 在某些设备上比 `REFERENCE_COMPAT` 更慢，同时 `video_thumbnail_fail/slow` 很高，
说明系统缩略图路径是慢失败瓶颈；此时以 `ADAPTIVE_BALANCED` 作为默认扫描模式更合适。

建议产品策略：

- 首次首页扫描使用 `ADAPTIVE_BALANCED`、`BALANCED` 或 `FAST`；
- 进入“相似视频”详情后，对疑似候选做 `REFERENCE_COMPAT` 补算；
- 充电/Wi-Fi/空闲时后台补全高精度视频指纹。

### P0：输出分阶段耗时表，确认真实瓶颈

每次测试保存：

| 字段 | 说明 |
| --- | --- |
| asset 总数 | MediaStore 可见资源数 |
| 图片数/截图数/视频数/录屏数 | 视频比例必须单独看 |
| visited | 本轮枚举资源数 |
| fingerprinted | 本轮重算资源数 |
| reused | 复用指纹资源数 |
| elapsed | 总墙钟时间 |
| calculate_video_fingerprint | 视频抽帧累计耗时 |
| load_fingerprint_bitmap | 图片缩略图读取累计耗时 |
| process_visual/process_video | 串行提交耗时 |
| rebuild_similar_groups | 完成阶段耗时 |

没有这张表前，不建议继续盲目改阈值或线程数。

### P1：批量化数据库写入和组关系写入

旧链路中 `markFingerprintDone()`、`linkAssets()`、`prepareAssetForRescan()` 都是大量小事务；当前已先将 `linkAssets()` 主路径改为 candidate edge 批量写入。

建议：

1. 每批次收集计算结果，统一事务提交 fingerprint。
2. Similar/Duplicate 边先写入轻量 `similar_candidate_edge(category, type, first_asset_id, second_asset_id, updated_at)`。
3. 扫描结束统一按 edge 表重建 group，减少每条边都查 group/插 group 的成本。
4. `prepareAssetForRescan()` 只删除当前 asset 的 group_item，不要每个资源都执行空组清理；空组清理移动到批次末或扫描末。

预期收益：大库首扫和算法版本升级时收益明显，尤其是相似边较多的截图/连拍库。

### P1：重建分组增量化

当前完成阶段按 `changedKinds` 重建整个类型或视频族。可以改成：

- 如果变化资源比例很小，只重建受影响 group；
- 如果变化比例超过阈值，再走全量重建；
- 对 video family 单独设置更高阈值，避免少量视频变化导致全视频族多帧精判。

建议阈值：

```text
changedCount / totalKindCount < 5% -> 增量重建
changedCount / totalKindCount >= 5% -> 全量重建
```

### P1：视频候选改为两阶段

当前 `REFERENCE_COMPAT` 下 `findCompetitorVideoFingerprintCandidates()` 只按视频族和算法版本召回，没有时长桶、宽高比桶过滤。

建议两阶段：

1. 快速候选：系统缩略图 hash + 时长桶 + 宽高比桶。
2. 精判候选：只对候选集合补算 7 到 13 帧。

这样能更接近竞品“先快出结果，再补精度”的体验。

### P2：减少全量对账写库

`upsertAsset()` 在可复用资源上仍会更新多列。可以优化为：

- source signature 未变时，只更新 `last_seen_scan`、`last_scanned_at`；
- 其他展示字段变化但不影响指纹时，延后批量更新；
- 使用预编译 statement 或批量事务处理 500 条 batch。

### P2：首页结果加载限流

扫描结束 `loadGroups(DEFAULT_GROUP_LIMIT)` 默认 `Int.MAX_VALUE`，大库可能加载过多 group 和 assets。demo UI 实际只需要首页预览，可以：

- scan result 只返回 summary；
- 首页通过 `loadProductCategories(previewAssetLimit = n)` 分页加载；
- 详情页再按 group/page 拉取。

## 5. 建议验证流程

1. 准备同一设备、同一媒体库，分别测试 1w、3w、5w。
2. 清空 demo 数据库，测试首扫。
3. 不清库再次扫描，测试复用场景。
4. 分别测试 `BALANCED` 和 `REFERENCE_COMPAT`。
5. 保存完整 `SimilarScanMetrics` 日志。
6. 用以下判断定位瓶颈：

| 现象 | 优先看 |
| --- | --- |
| `calculate_video_fingerprint` 很高 | 视频模式、视频数量、MMR 抽帧 |
| `load_fingerprint_bitmap` 很高 | 系统 thumbnail/URI decode、图片尺寸 |
| `process_visual/process_video` 很高 | 串行提交、linkAssets、小事务 |
| `prepare_asset_for_rescan` 很高 | 每资源组清理 |
| `rebuild_similar_groups` 很高 | 完成阶段全量重建 |
| `reused` 很高但总时间仍高 | upsert、MediaStore 对账、loadGroups |

## 6. 当前建议排序

短期先做：

1. demo 默认使用 `ADAPTIVE_BALANCED`，保留 `BALANCED` / `REFERENCE_COMPAT` 开关用于速度和准确率对照。
2. 将 `ScanMetrics` 日志整理成测试表，补充图片/视频数量和 `video_thumbnail_*` 计数。
3. 把 `prepareAssetForRescan()` 的空组清理移动到批次末或扫描末。

中期再做：

1. 指纹结果批量事务提交。
2. `rebuildSimilarGroups()` 继续做增量重建。
3. 视频两阶段扫描，高精度多帧只对候选或详情触发。

这套方向对 3w 资源从 15min 拉回到竞品 3min 附近最关键；5w 场景再靠视频两阶段和批量写库继续压低。

## 7. 本轮已落地的执行路径优化

本轮优化不改变相似阈值、不改变 dHash/colorHash 判断、不改变视频帧命中规则，只调整执行和落库路径：

1. 新增 `similar_candidate_edge` 表，扫描中 `linkDuplicateAssets/linkSimilarAssets` 只记录边，不再逐边查组、建组、更新组。
2. `rebuildSimilarGroups()` 优先基于 candidate edge 批量构建 Duplicate/Similar 分组；edge 缺失时保留原 BK-Tree fallback。
3. 新增 `link_duplicate_assets/link_similar_assets` 耗时和 `duplicate_edges/similar_edges` 计数。
4. 新增 `visual_bk_query/visual_bk_candidates/visual_bk_empty_result/visual_bk_nodes` 计数，用于判断 BK-Tree 召回是否过宽或树遍历是否退化。
5. `bk_tree_visual_query` 避免每张图创建额外排除 Set，复用已有 mutable set。
6. `HammingBkTree.queryInto()` 复用内部 `ArrayDeque` 并返回访问节点数，不改变候选距离和召回规则。
7. `rebuildSimilarGroups()` 拆分 edge 加载、fingerprint 加载、删除旧组、edge 建组和 fallback 建组等子阶段 metrics。
8. Similar rebuild 阶段不再通过 SQL `NOT EXISTS + JOIN similar_group` 排除 Duplicate；本轮有 Duplicate edge 时，改为加载后用 duplicate asset id 内存集合过滤，结果口径保持一致。
9. `SegmentedHammingIndex` 曾作为图片扫描热路径候选召回实验方案，但 2.9w 数据下候选扩大约 87 倍，已回退到 BK-Tree。
