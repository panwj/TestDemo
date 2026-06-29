# Similar Scan Core 核心技术方案

本文描述 `similar-scan-core` 当前源码中的核心扫描、指纹、候选召回、相似比较、视频识别和分组逻辑。本文以源码为准，面向后续作为 SDK 接入其他产品时的技术评审和实现对齐。

## 1. 能力边界

`similar-scan-core` 当前只处理 Android 本地图库中的图片和视频资源：

| MediaStore 来源 | 内部类型 | 主要输出分类 |
| --- | --- | --- |
| `MediaStore.Images` | `PHOTO` | Similar、Duplicates、Chat Photos、Other |
| `MediaStore.Images` | `SCREENSHOT` | Duplicates、Similar Screenshots、Other Screenshots |
| `MediaStore.Video` | `VIDEO` | Similar Videos、Other Videos |
| `MediaStore.Video` | `SCREEN_RECORDING` | Similar Screen Rec、Other Screen Rec |

当前不枚举音频，不做音频相似识别。SDK 内部模型位于 `com.clean.similarscan.internal.*`，接入方只应使用 `com.clean.similarscan.api.*`、`com.clean.similarscan.api.model.*` 和 `com.clean.similarscan.permission.*`。

## 2. 扫描主链路

核心编排入口是 `SimilarMediaScanner.scan()`：

```text
读取扫描 checkpoint
-> 判断 full / incremental 模式
-> 从 SQLite 预加载 PHOTO、SCREENSHOT 的 BK-Tree 和 CombinedHash 内存缓存
-> MediaStore 按批枚举图片和视频，批大小 500
-> media_asset upsert，生成本轮 AssetScanToken
-> source_signature 和算法版本未变化时复用旧 fingerprint
-> 图片/截图走 processVisual()
-> 视频/录屏走 processVideo()
-> 扫描中增量写入 Duplicate/Similar 候选组
-> full scan 且完整授权时清理本轮未见资源
-> 对变化类型 rebuildSimilarGroups()
-> cleanupInvalidGroups()
-> 保存 checkpoint
-> loadGroups() 返回结果
```

批大小只影响 MediaStore 读取节奏、进度回调和落库节奏，不是相似比较边界。已有旧指纹的资源会参与本轮新资源匹配；新资源提交指纹成功后也会立即加入当前扫描的候选索引。

## 3. MediaStore 枚举

资源读取由 `MediaStoreRepository` 完成。

图片查询：

```text
uri       = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
selection = _size > 0
sort      = date_added DESC
```

视频查询：

```text
uri       = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
selection = _size > 0
sort      = date_added DESC
```

API 30+ 增量扫描会追加：

```text
generation_modified > checkpoint.imageGeneration
generation_modified > checkpoint.videoGeneration
```

图片 projection 包括：

```text
_id
_display_name
_size
width
height
date_added
datetaken
date_modified
bucket_display_name
mime_type
relative_path        # API 29+
is_favorite          # API 30+
generation_added     # API 30+
generation_modified  # API 30+
```

视频 projection 额外包含：

```text
duration
```

`createdAt` 取值规则：

```text
DATE_TAKEN > 0 ? DATE_TAKEN : DATE_ADDED * 1000
```

MediaStore 查询异常不会被静默吞掉。原因是全量扫描如果只成功枚举部分资源，后续清理未出现记录会误删合法缓存。

## 4. 媒体二次分类

MediaStore 只能稳定区分图片集合和视频集合，截图、录屏、聊天图片属于 SDK 二次分类。

截图判断只使用 `DISPLAY_NAME`，不会拼接 bucket 或 relative_path：

```text
contains "screenshot"
contains "screen_shot"
contains "screen-shot"
startsWith "screenshot_"
startsWith "screen_"
```

录屏判断也只使用 `DISPLAY_NAME`：

```text
contains "screen_recording"
contains "screen-recording"
contains "screenrecord"
contains "screen_record"
contains "screen-record"
startsWith "screen_recording_"
startsWith "screenrecord_"
startsWith "screen_record_"
startsWith "recording_"
contains "recording"
contains "capture"
contains "mirror"
contains "cast"
```

聊天图片不是基础媒体类型，而是 `Other Photos` 的展示拆分。`MediaClassifier.chatSource()` 会在 name、bucket、relative_path 中识别：

```text
whatsapp
telegram
snapchat
messenger
facebook
```

## 5. 全量扫描、增量扫描和指纹复用

扫描模式由 `SimilarMediaScanner` 和 `ScanStateStore` 决定。

触发全量扫描的条件：

- 调用方设置 `SimilarScanRequest(forceFull = true)`。
- 数据库中没有 ACTIVE 资源。
- 当前不是完整图片 + 视频媒体授权。
- API 30+ `MediaStore.getVersion(context)` 变化。
- 距离上次完整扫描超过 24 小时。

否则使用 checkpoint 中的 generation 游标做增量扫描。

每个资源落库时会计算 `SourceSignature`：

```text
mediaStoreId
kind
size
width
height
duration
updatedAt
generationModified
mimeType
isFavorite
isEdited
```

如果数据库已有 fingerprint，且 `source_signature` 与当前一致，且 `fingerprint_algorithm_version` 与当前版本一致，则本轮复用旧指纹，不重新加载 Bitmap、不重新抽帧。

注意：文件名、bucket、path_hint 不进入 `SourceSignature`。这些字段只影响展示和分类，不应该触发 dHash/colorHash 或视频帧指纹重算。

## 6. 图片/截图指纹 Bitmap 来源

图片和截图的指纹输入由 `MediaBitmapLoader.loadFingerprintBitmapWithSource()` 完成，扫描链路传入的目标尺寸是：

```kotlin
FINGERPRINT_BITMAP_SIZE = 256
```

加载顺序：

```text
API 29+ ContentResolver.loadThumbnail(asset.uri, Size(256, 256), null)
-> legacy MediaStore.Images.Thumbnails.getThumbnail(..., MINI_KIND, null)
-> resolver.openInputStream(asset.uri) + BitmapFactory inSampleSize decode
-> 查询 MediaStore.MediaColumns.DATA
-> BitmapFactory.decodeFile(path, options)
-> normalizeFingerprintBitmap(bitmap, 256)
```

`normalizeFingerprintBitmap()` 只做等比缩放：

```text
maxSide = max(width, height)
if maxSide <= 256: 原样返回
scale = 256 / maxSide
targetWidth = width * scale
targetHeight = height * scale
Bitmap.createScaledBitmap(filter = true)
```

它不裁剪、不拉伸、不强制转成正方形。

这个 Bitmap 只用于扫描指纹。UI 预览图由 `loadBitmap()` 加载，图片 UI 默认请求 1024，视频 UI 默认取 0ms 关键帧或系统缩略图。接入方排查相似结果时，应区分“分析输入”和“界面封面”。

## 7. CombinedHash

图片、截图和每个视频帧最终都生成：

```kotlin
CombinedHash(
    imageHash = 64-bit dHash,
    colorHash = RGB 8x3 colorHash
)
```

有效指纹条件：

```kotlin
imageHash != -1L && colorHash.isNotEmpty()
```

### 7.1 dHash

当前只保留 Kotlin dHash 实现：`KotlinDHash.fromBitmap()`。

处理流程：

```text
输入 Bitmap
-> null、宽高非法、宽高超过 16384 时返回 -1
-> 非 ARGB_8888 时尝试 copy 为 ARGB_8888
-> 在原图上取 9 列 x 8 行采样网格
-> 每个采样点执行双线性灰度采样
-> 每行比较相邻采样点灰度
-> 8 行 x 8 次比较
-> 输出 64-bit Long
```

灰度公式：

```text
gray = 0.299 * R + 0.587 * G + 0.114 * B
```

位生成规则：

```text
row[x] > row[x + 1] -> 1
else                 -> 0
```

汉明距离：

```kotlin
Long.bitCount(firstHash xor secondHash)
```

### 7.2 colorHash

`HashCalculator.colorHash()` 使用 RGB 三通道直方图：

```text
R/G/B 每个通道统计 0..255
每 32 个值为一桶
每通道 8 桶
结果为 Array(8) { DoubleArray(3) }
每桶除以 pixels.size / 16.0
```

颜色距离：

```text
sum(abs(hashA[i][j] - hashB[i][j])) over 8 x 3
toLong()
```

## 8. 图片 BK-Tree 候选召回

照片和截图使用 BK-Tree 召回候选，避免全量两两比较。扫描开始时建立两个独立索引：

```text
PHOTO      -> HammingBkTree
SCREENSHOT -> HammingBkTree
```

PHOTO 不和 SCREENSHOT 混比。

索引数据来自 SQLite：

```sql
SELECT a.id, f.image_hash, f.color_hash
FROM fingerprint f
JOIN media_asset a ON a.id = f.asset_id
WHERE a.type = ?
  AND a.state = 'ACTIVE'
  AND a.fingerprint_algorithm_version = ?
  AND f.video_frame_hashes IS NULL
```

BK-Tree 节点只保存：

```text
hash: Long
assetIds: MutableList<Long>
children: MutableMap<Int, Node>
```

插入逻辑：

```text
root 为空 -> 新节点
distance = hammingDistance(newHash, current.hash)
distance == 0 -> assetId 加入当前节点
否则用 distance 作为边进入 child
child 不存在 -> 新建 child
```

查询逻辑：

```text
distance = hammingDistance(queryHash, node.hash)
if distance <= maxDistance: 当前节点 assetIds 是候选
只访问 child edge 位于 [distance - maxDistance, distance + maxDistance] 的子树
```

BK-Tree 只负责 dHash 召回，不直接决定相似。

## 9. 图片 CombinedHash 内存缓存

为避免 BK-Tree 召回后反复回 SQLite 读取 colorHash，当前扫描维护：

```kotlin
visualHashCache: MutableMap<MediaKind, MutableMap<Long, CombinedHash>>
```

构建 BK-Tree 时同步填充：

```kotlin
tree.add(assetId, imageHash)
cache[assetId] = CombinedHash(imageHash, colorHash)
```

新资源指纹成功提交后，也会同步加入：

```kotlin
visualIndexes.getValue(kind).add(assetId, imageHash)
visualHashCache.getValue(kind)[assetId] = hash
```

因此图片/截图候选精判路径是：

```text
BK-Tree 返回 candidateIds
-> 过滤自己和 Duplicate 候选
-> visualHashCache[candidateId] 取 CombinedHash
-> CombinedHash.isSimilarTo(candidateHash, kind)
```

这个内存缓存是当前图片扫描性能优化的关键，避免大图库下对候选 colorHash 做大量 SQL 查询。

## 10. 图片/截图相似判断

相似精判由 `CombinedHash.isSimilarTo(other, kind)` 完成。

通用逻辑：

```text
distance = bitCount(imageHash xor other.imageHash)
threshold = Threshold.forKind(kind)

if distance >= maxImageDistanceExclusive:
    return false

if distance in directImageDistance:
    return true

colorDistance = colorDistanceTo(other)
return any colorRange matches distance and colorDistance
```

普通照片阈值：

| dHash 汉明距离 | colorHash 条件 | 结果 |
| --- | ---: | --- |
| 0..4 | 不需要 | 相似 |
| 5..10 | <= 7 | 相似 |
| 11..17 | <= 5 | 相似 |
| >= 18 | - | 不相似 |

截图阈值：

| dHash 汉明距离 | colorHash 条件 | 结果 |
| --- | ---: | --- |
| 0..2 | 不需要 | 相似 |
| 3..10 | <= 5 | 相似 |
| 11..15 | <= 2 | 相似 |
| >= 16 | - | 不相似 |

源码中的 `LongRange` 边界是包含的，例如 `4..10`、`10..18`，但因为前置条件是 `distance >= maxImageDistanceExclusive` 直接拒绝，所以普通照片的 18、截图的 16 实际不会通过。

## 11. Duplicate 识别

Duplicate 只在图片和截图链路中处理，视频当前没有 Duplicate 分组。

主链路使用 `findDuplicateReferenceCandidates()`，SQL 条件如下：

```sql
WHERE a.type = ?
  AND a.state = 'ACTIVE'
  AND a.id != ?
  AND a.size = ?
  AND a.width = ?
  AND a.height = ?
  AND a.is_edited = ?
  AND f.image_hash = ?
```

也就是：

```text
类型相同
文件大小相同
宽高相同
isEdited 相同
dHash 完全相同
```

找到 duplicateReference 候选后，SDK 会按需计算并缓存 SHA-256：

```text
当前资源 content_sha256
候选资源 content_sha256
```

SHA-256 不是进入 Duplicate 的硬条件，而是字节级验证证据，用来区分“字节完全一致”和“竞品组合引用一致”。

Duplicate 优先级高于 Similar：

- 写入 Duplicate 时会把相关资源从 Similar 分类移除。
- 已在 Duplicate 的资源不会被后续 Similar 链路重新加入。
- `ProductCategoryBuilder` 也会在展示层再次排除重复计数。

## 12. 图片/截图处理流程

`processVisual()` 的实际步骤：

```text
loadFingerprintBitmapWithSource(asset, 256)
-> HashCalculator.buildHash(bitmap)
-> findDuplicateReferenceCandidates()
-> 有 Duplicate 候选时计算当前资源 SHA-256
-> BK-Tree query(imageHash, Threshold.maxCandidateDistance(kind))
-> 用 visualHashCache 做 dHash + colorHash 精判
-> markFingerprintDone(token, hash, asset, sha256, qualityScore)
-> linkDuplicateAssets()
-> linkSimilarAssets()
-> 当前资源加入 BK-Tree
-> 当前资源加入 visualHashCache
```

`markFingerprintDone()` 带 token/revision 乐观锁。如果用户在后台计算期间删除了资源，提交会失败，后续分组和索引更新都会停止。

质量分当前主链路使用 `MediaQualityAnalyzer.metadataScore(asset)`，只影响 Best 推荐排序，不参与相似或 Duplicate 判定。

## 13. 视频/录屏指纹输入

视频和录屏由 `VideoFingerprintCalculator.calculate()` 生成 `VideoFingerprint`。

当前源码的真实策略是：

```text
优先系统视频缩略图 -> 单帧指纹
系统缩略图失败 -> DATA 真实路径 + MediaMetadataRetriever 7 帧指纹
```

### 13.1 系统缩略图单帧路径

先调用 `systemVideoThumbnail(asset)`。

API 29+：

```kotlin
ContentResolver.loadThumbnail(asset.uri, Size(512, 512), null)
```

API 23-28：

```kotlin
MediaStore.Video.Thumbnails.getThumbnail(
    resolver,
    asset.id,
    MediaStore.Video.Thumbnails.MINI_KIND,
    null
)
```

如果成功：

```kotlin
VideoFingerprint(
    frames = listOf(HashCalculator.buildHash(thumbnail)),
    qualityScore = MediaQualityAnalyzer.metadataScore(asset)
)
```

这意味着命中系统缩略图的视频会保存单帧指纹。它能显著降低扫描成本，但视频相似能力会退化为单帧画面相似。

### 13.2 MMR 7 帧路径

系统视频缩略图失败后，SDK 查询 DATA 真实路径：

```text
query(asset.uri, MediaStore.MediaColumns.DATA)
```

如果路径为空、文件不存在或不可读，返回无效指纹。当前不会回退到 `content://` URI 或 `FileDescriptor`。

路径可用时：

```text
MediaMetadataRetriever.setDataSource(path)
-> 读取 METADATA_KEY_DURATION，失败则用 asset.duration
-> buildSampleTimes(durationMs)
-> 每个时间点 extractFrame()
-> 每帧 HashCalculator.buildHash()
```

当前 `buildSampleTimes()` 固定生成 7 个等距点：

```text
NORMAL_FRAME_COUNT = 7
interval = durationMs / 6
times = [0, interval, 2*interval, ... durationMs]
```

抽帧 API 分支：

| API | 方法 | 输出 |
| --- | --- | --- |
| 30+ | `getScaledFrameAtTime(timeUs, OPTION_CLOSEST_SYNC, 9, 8, BitmapParams ARGB_8888)` | 9x8 ARGB_8888 |
| 27-29 | `getScaledFrameAtTime(timeUs, OPTION_CLOSEST_SYNC, 9, 8)` | 9x8 |
| 23-26 | `getFrameAtTime(timeUs, OPTION_CLOSEST_SYNC)` 后 `Bitmap.createScaledBitmap(9, 8)` | 9x8 |

抽帧失败时保留无效占位：

```kotlin
CombinedHash(-1L, emptyArray())
```

比较时会跳过无效帧，但无效帧不会从列表中删除。

## 14. 视频候选召回

实时视频扫描不使用图片的 BK-Tree 索引。`processVideo()` 会先计算当前视频指纹，再从 SQLite 查候选：

```sql
SELECT ...
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
```

桶规则：

```kotlin
durationBucket = duration / 1000
aspectBucket = (width / height) * 100
```

容差：

```kotlin
durationTolerance = max(2, durationBucket / 10)
aspectTolerance = 8
```

候选召回只做粗筛，最终仍由 `VideoFingerprint.isSimilarTo()` 精判。

## 15. 视频/录屏相似判断

视频和录屏使用与截图相同的严格阈值：

| dHash 汉明距离 | colorHash 条件 | 结果 |
| --- | ---: | --- |
| 0..2 | 不需要 | 相似 |
| 3..10 | <= 5 | 相似 |
| 11..15 | <= 2 | 相似 |
| >= 16 | - | 不相似 |

`VideoFingerprint.isSimilarTo(other, kind)` 先检查双方是否至少有一个有效帧。无有效帧则不相似。

### 15.1 单帧比较

如果任一侧只有一个有效帧：

```text
任意有效帧对满足 CombinedHash.isSimilarTo(kind)
-> 视频相似
```

这个分支主要服务系统视频缩略图单帧路径。虽然是单帧，但仍使用 VIDEO 或 SCREEN_RECORDING 的严格阈值。

### 15.2 多帧比较

如果双方都有多帧：

```text
MIN_MATCHED_FRAME_COUNT = 2
MIN_MATCHED_FRAME_GAP = 2
```

匹配规则：

```text
遍历当前视频有效帧
-> 遍历候选视频未使用有效帧
-> 两帧 CombinedHash.isSimilarTo(kind) 通过
-> 候选帧被消费一次
-> 记录当前视频命中的 frameIndex
-> 命中数量 >= 2 且命中 frameIndex 与第一命中 index 间隔 >= 2
-> 判定视频相似
```

设计目的：

- 防止当前视频多个静态帧都命中候选视频同一张黑屏或片头。
- 防止只靠开头连续两帧相似就把整段视频合并。
- 降低录屏、静态 UI、重复转场导致的大组误合并。

## 16. Similar 分组重建

扫描中会增量写 Similar 候选关系，方便 UI 边扫边展示。扫描结束后调用：

```kotlin
rebuildSimilarGroups(changedKinds)
```

重建时会：

```text
读取已有 Similar 候选关系为邻接表
-> 删除对应类型旧 Similar 组
-> 读取该类型所有 ACTIVE、DONE、算法版本一致的 fingerprint
-> 排除 Duplicate 组中的资源
-> 按锚点顺序遍历
-> 锚点只收与自己直接相似的 remaining 候选
-> 写入新的 Similar 组
```

锚点排序：

| 类型 | 排序 |
| --- | --- |
| PHOTO / SCREENSHOT | `created_at ASC, media_store_id DESC` |
| VIDEO / SCREEN_RECORDING | `date_added DESC` |

分组不是相似关系的连通分量。示例：

```text
A 与 B 相似
B 与 C 相似
A 与 C 不相似
```

在当前锚点直连规则下，C 不会因为 B 被并入 A 所在组。

如果没有可复用的候选邻接表，重建阶段会回退 BK-Tree 召回。对视频来说，每个有效帧的 `imageHash` 都会进入临时 BK-Tree，但最终仍调用 `VideoFingerprint.isSimilarTo()` 精判。

## 17. 数据库结构

核心表：

```text
media_asset
fingerprint
similar_group
similar_group_item
```

`media_asset` 保存：

```text
media_store_id
uri
type
name
width / height / duration / size
created_at / updated_at / date_added
bucket / path_hint / mime_type
is_favorite / is_edited
generation_added / generation_modified
chat_source
state
revision
fingerprint_status
last_seen_scan
source_signature
fingerprint_algorithm_version
```

`fingerprint` 保存：

```text
asset_id
image_hash
color_hash
hash_prefix
aspect_bucket
duration_bucket
video_frame_hashes
video_frame_colors
content_sha256
quality_score
potential_identifier
```

`similar_group` 保存分组类型：

```text
category = SIMILAR / DUPLICATE / OTHER
type     = PHOTO / SCREENSHOT / VIDEO / SCREEN_RECORDING
```

`similar_group_item` 保存 group 与 asset 的关系。

## 18. 删除一致性

删除流程通过 `state + revision + AssetScanToken` 防止异步扫描结果复活用户删除的资源。

系统删除确认前：

```text
markDeletePending(uris)
-> state = DELETE_PENDING
-> revision = revision + 1
```

扫描计算完成提交指纹或分组时，会检查 token 中的 revision 是否仍有效：

```text
id = token.assetId
state = ACTIVE
revision = token.revision
```

如果用户在计算期间发起删除，旧 token 提交失败，SDK 不会写入 fingerprint、Similar 或 Duplicate。

用户确认删除后：

```text
finalizeDelete(uris)
-> 删除 media_asset
-> 外键级联清理 fingerprint 和 similar_group_item
-> 清理空组
```

用户取消删除：

```text
restoreDeletePending(uris)
-> state = ACTIVE
-> revision + 1
-> fingerprint_status = PENDING
```

App 冷启动时：

```text
recoverStaleDeletePending()
-> 恢复悬挂 DELETE_PENDING
-> 等后续扫描重新校验
```

## 19. 产品分类输出

`ProductCategoryBuilder` 将底层 group 转成固定首页分类：

```text
SIMILAR
DUPLICATES
SIMILAR_SCREENSHOTS
SIMILAR_VIDEOS
OTHER_SCREENSHOTS
CHAT_PHOTOS
SIMILAR_SCREEN_RECORDINGS
OTHER_SCREEN_RECORDINGS
OTHER_VIDEOS
OTHER
```

展示层会再次做 Duplicate/Similar 互斥防御：

```text
Duplicate 中出现的资源
-> 从 Similar 展示组中排除
-> Similar 组不足 2 个资源时不展示
```

Best 推荐排序：

```text
qualityScore
isFavorite
isEdited
width * height
size
createdAt
```

组内展示时 Best 放第一位，其余按媒体时间倒序。

## 20. 当前风险点

- 视频优先系统缩略图单帧。该策略速度更好，但命中系统缩略图时会退化成单帧相似，不等价于全量 7 帧视频识别。
- 图片指纹输入是最大边 256 的缩略图，不是原图。细节差异很小的图片可能受缩略图质量影响。
- 截图关键词 `startsWith("screen_")` 较宽，可能误归类部分非截图图片。
- 录屏关键词包含 `recording`、`capture`、`mirror`、`cast`，可能误归类部分普通视频。
- Duplicate 不要求 SHA-256 一致，SHA-256 仅为证据字段。这符合当前 duplicateReference 口径，但比字节级重复更宽。
- 外部系统相册删除主要依赖全量扫描对账，增量扫描期间旧记录可能暂时保留。
