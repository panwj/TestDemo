# Similar Scan Core 核心技术方案

本文描述 `similar-scan-core` 当前源码中的核心扫描、指纹、候选召回、相似比较、视频识别和分组逻辑。本文以源码为准，面向后续作为 SDK 接入其他产品时的技术评审和实现说明。

## 1. 能力边界

`similar-scan-core` 当前只处理 Android 本地图库中的图片和视频资源：

| MediaStore 来源 | 内部类型 | 主要输出分类 |
| --- | --- | --- |
| `MediaStore.Images` | `PHOTO` | Similar、Duplicates、Other |
| `MediaStore.Images` | `SCREENSHOT` | Duplicates、Similar Screenshots、Other Screenshots |
| `MediaStore.Video` | `VIDEO` | Similar Videos、Other Videos |
| `MediaStore.Video` | `SCREEN_RECORDING` | Similar Videos、Other Videos |

当前不枚举音频，不做音频相似识别。SDK 内部模型位于 `com.clean.similarscan.internal.*`，接入方只应使用 `com.clean.similarscan.api.*`、`com.clean.similarscan.api.model.*` 和 `com.clean.similarscan.permission.*`。

### 1.1 当前功能矩阵

| 功能 | 是否支持 | 主要入口/输出 |
| --- | --- | --- |
| 枚举系统图片 | 支持 | `MediaStore.Images` -> `PHOTO` 或 `SCREENSHOT` |
| 枚举系统视频 | 支持 | `MediaStore.Video` -> `VIDEO` 或 `SCREEN_RECORDING` |
| 枚举音频 | 不支持 | SDK 不请求音频权限，不读取音频媒体库 |
| 图片/截图相同识别 | 支持 | 输出 `DUPLICATES` |
| 普通图片相似识别 | 支持 | 输出 `SIMILAR` |
| 截图相似识别 | 支持 | 输出 `SIMILAR_SCREENSHOTS` |
| 视频/录屏相似识别 | 支持 | 输出 `SIMILAR_VIDEOS` |
| 未命中截图 | 支持 | 输出 `OTHER_SCREENSHOTS` |
| 未命中视频/录屏 | 支持 | 输出 `OTHER_VIDEOS` |
| 未命中普通照片 | 支持 | 输出 `OTHER` |
| 聊天图片来源识别 | 内部保留 | `MediaAsset.chatSource` 保留来源信息，但不再输出独立产品分类 |
| 删除一致性 | 支持 | `markDeletePending/finalizeDelete/restoreDeletePending` |
| 首页预览限流 | 支持 | `loadProductCategories(previewAssetLimit = n)` |
| 详情分页 | 支持 | `loadProductCategoryAssets` / `loadSimilarGroupAssets` |

### 1.2 媒体入库类型

SDK 入库时使用内部 `MediaKind` 保存资源类型：

```text
PHOTO             普通图片
SCREENSHOT        截图
VIDEO             普通视频
SCREEN_RECORDING  录屏
```

这四类是扫描识别层的稳定边界。图片、截图、视频、录屏可以使用不同阈值和候选召回策略；
即使产品展示层把录屏归并到视频分类，数据库中的 `media_asset.type` 和 `similar_group.type`
仍会保留 `SCREEN_RECORDING`，便于后续调整策略或做诊断。

`MediaStore` 本身只稳定提供图片集合和视频集合。截图、录屏、聊天来源都由 SDK 进行二次分类：

- `PHOTO / SCREENSHOT` 来自 `MediaStore.Images`。
- `VIDEO / SCREEN_RECORDING` 来自 `MediaStore.Video`。
- `chatSource` 是普通照片的附加来源标签，不改变 `MediaKind`。

## 2. 扫描主链路

核心编排入口是 `SimilarMediaScanner.scan()`：

```text
读取扫描 checkpoint
-> 判断 full / incremental 模式
-> 从 SQLite 预加载 PHOTO、SCREENSHOT 的 BK-Tree 和 CombinedHash 内存缓存
-> MediaStore 按图片批次和视频批次轮询枚举，批大小 500
-> media_asset upsert，生成本轮 AssetScanToken
-> source_signature 和算法版本未变化时复用旧 fingerprint
-> 图片/截图指纹计算提交到 imageComputeExecutor
-> 视频/录屏指纹计算提交到 videoComputeExecutor
-> 扫描线程串行提交计算结果、更新 BK-Tree/cache、写入 Duplicate/Similar candidate edge
-> 批次边界按节流策略阶段性 publish candidate edge 到 similar_group，供 UI 展示
-> full scan 且完整授权时清理本轮未见资源
-> 对变化类型基于 candidate edge 优先 rebuildSimilarGroups()
-> cleanupInvalidGroups()
-> 保存 checkpoint
-> loadGroups() 返回结果
```

批大小只影响 MediaStore 读取节奏、进度回调和落库节奏，不是相似比较边界。已有旧指纹的资源会参与本轮新资源匹配；新资源提交指纹成功后也会立即加入当前扫描的候选索引。

图片和视频使用独立计算线程池：

```text
IMAGE_COMPUTE_THREADS = 4
VIDEO_COMPUTE_THREADS = 2
```

计算任务只做 Bitmap 加载、dHash/colorHash 或视频抽帧，不直接写 SQLite，不直接修改 BK-Tree。提交阶段仍由扫描线程串行执行，保证数据库、内存索引和 Similar/Duplicate 关系的更新顺序可控。扫描中不再逐边维护 `similar_group`，而是写入 `similar_candidate_edge`；批次边界按时间和增量阈值阶段性物化分组给 UI 展示，扫描完成后再批量构建最终分组。`ScanMetrics` 中 `process_visual_compute`、`process_video_compute` 是各工作线程耗时累加，可能大于真实墙钟耗时；总墙钟耗时以扫描汇总行的 `elapsed` 为准。

扫描中 UI 首屏和过程刷新优先使用内存 `ProgressiveScanSnapshotStore`。snapshot 只保存已经通过核心算法确认的 Similar/Duplicate 边相关资产，不保存全量 MediaStore 资源，不参与 dHash/阈值/BK-Tree 判断，也不替代最终数据库分组。为了避免 10 万级媒体库且候选边极多时内存持续增长，snapshot 增加硬上限：

```text
MAX_SNAPSHOT_ASSETS = 150_000
MAX_SNAPSHOT_EDGES = 450_000
```

达到上限后，后续新发现的临时边不再继续加入 snapshot；这只影响扫描中临时展示的完整度，不影响 `similar_candidate_edge` 入库、最终 `rebuildSimilarGroups()` 和最终相似/相同结果。

DB 中间分组发布仍然保留，但定位为低频兜底：用于页面重建、snapshot 不可用或需要阶段性稳定入库结果时读取，不再作为快速首显主路径。

历史上为提升扫描中体验，DB 中间发布曾采用更激进的自适应策略：首次发布会根据媒体总数动态提前，最小可在 1 到 5 秒、20 到 200 个新增扫描资源、至少 1 条候选边后触发；后续发布在小图库上也会压低到 30 到 45 秒和较小资源/边数门槛。这套方案的优点是无需额外 snapshot 读取路径，DB 内可以较早出现阶段性分组，首页/详情可以直接从 DB 看到中间结果。问题也很明显：每次 DB publish 都会执行 `rebuildSimilarGroups(finalPass = false)`，成本远高于内存 snapshot；在 3 万到 5 万资源场景下容易增加扫描耗时，也可能导致首页预览图跟随 DB 阶段性重建出现闪烁或新旧结果跳变。

历史体验优先方案的首次 DB publish 分档：

| 估算媒体总数 | 最小等待时间 | 新增扫描资源门槛 | 新增候选边门槛 |
| --- | ---: | ---: | ---: |
| `<= 500` | 1 秒 | 20 | 1 |
| `<= 2,000` | 1.5 秒 | 40 | 1 |
| `<= 10,000` | 2 秒 | 80 | 1 |
| `<= 50,000` | 3 秒 | 100 | 1 |
| `> 50,000` | 5 秒 | 200 | 1 |

历史体验优先方案的后续 DB publish 分档：

| 估算媒体总数 | 最小间隔 | 距离上次新增资源门槛 | 距离上次新增候选边门槛 |
| --- | ---: | ---: | ---: |
| `<= 2,000` | 30 秒 | 500 | 1,000 |
| `<= 10,000` | 45 秒 | 2,500 | 5,000 |
| `<= 50,000` | 75 秒 | 10,000 | 20,000 |
| `> 50,000` | 120 秒 | 20,000 | 40,000 |

当前方案调整为：snapshot 负责“快”，DB 中间发布负责“稳”，最终 DB rebuild 负责“准”。因此 DB 中间发布只作为保守兜底，业务传入的 publish 参数被视为最低门槛，内部自适应策略只能让 DB publish 更保守，不能提前触发。

SDK/demo 当前 DB 中间发布默认配置为：

```text
enableIntermediateGroupPublish = true
firstIntermediateGroupPublishIntervalMs = 60_000
firstIntermediateGroupPublishMinAssets = 5000
firstIntermediateGroupPublishMinEdges = 1
intermediateGroupPublishIntervalMs = 90_000
intermediateGroupPublishMinAssets = 10000
intermediateGroupPublishMinEdges = 20000
maxIntermediateGroupPublishCount = 2
```

实际发布策略会先用 MediaStore `Cursor.count` 估算本轮媒体总数，再做自适应分档。请求参数表示业务允许的最低发布门槛，内部自适应门槛只会把 DB publish 做得更保守，不会抢在业务配置之前提前触发。中间 publish 不是每发现一组就刷新，而是在扫描线程提交候选边后，按“时间 + 新增资源数/候选边数 + 最多次数”节流触发 `rebuildSimilarGroups()`，把当前 `similar_candidate_edge` 阶段性物化到 `similar_group`。

demo 配置下首次 DB publish 规则：

| 估算媒体总数 | 最小等待时间 | 新增扫描资源门槛 | 新增候选边门槛 |
| --- | ---: | ---: | ---: |
| `<= 500` | 60 秒 | 5,000 | 1 |
| `<= 2,000` | 60 秒 | 5,000 | 1 |
| `<= 10,000` | 60 秒 | 5,000 | 1 |
| `<= 50,000` | 60 秒 | 5,000 | 1 |
| `> 50,000` | 60 秒 | 5,000 | 1 |

首次发布必须已经出现至少 1 条 Similar/Duplicate 候选边；如果当前扫描的前几百个资源互不相似，即使进度数量已经变化，也不会发布空分组。

demo 配置下后续 DB publish 规则：

| 估算媒体总数 | 最小间隔 | 距离上次新增资源门槛 | 距离上次新增候选边门槛 |
| --- | ---: | ---: | ---: |
| `<= 2,000` | 90 秒 | 10,000 | 20,000 |
| `<= 10,000` | 90 秒 | 10,000 | 20,000 |
| `<= 50,000` | 90 秒 | 10,000 | 20,000 |
| `> 50,000` | 120 秒 | 20,000 | 40,000 |

后续发布需要先满足最小时间间隔；时间满足后，新增资源数或新增候选边数满足任一门槛即可发布。

单次扫描最多中间 publish 次数：

| 估算媒体总数 | 最多中间 publish 次数 |
| --- | ---: |
| `<= 2,000` | 1 |
| `<= 10,000` | 2 |
| `<= 50,000` | 2 |
| `> 50,000` | 2 |

普通进度广播只更新扫描数量和耗时，只有 progressive snapshot 发布、DB 阶段性物化成功或最终扫描完成时，进度中的 `resultUpdated=true`，UI 才刷新首页分组，避免首页预览图随进度广播反复闪烁。


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

同时拥有图片和视频读取权限时，SDK 不再先扫完所有图片再扫视频，而是交替输出图片批次、视频批次。这样视频指纹任务可以在图片指纹任务仍在执行时进入视频线程池，避免 9000+ 图片把几十个视频完全排到最后。

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

聊天图片不是基础媒体类型，而是普通照片上的来源标签。`MediaClassifier.chatSource()` 会在 name、bucket、relative_path 中识别：

```text
whatsapp
telegram
snapchat
messenger
facebook
```

当前产品分类不再单独输出 `Chat Photos`。聊天来源只作为 `MediaAsset.chatSource` 暴露给业务层，
如果宿主产品需要恢复独立聊天图片入口，可以在业务层基于 `MediaAsset.chatSource` 自行二次过滤，
或在 SDK 产品分类层重新增加对应分类。

## 5. 全量扫描、增量扫描和指纹复用

扫描模式由 `SimilarMediaScanner` 和 `ScanStateStore` 决定。

重要语义：

- `forceFull = true` 表示强制全量枚举和媒体库对账，不表示强制重算全部指纹。
- 全量扫描会从 MediaStore 重新读取所有当前可访问的图片、视频资源。
- 每个资源都会执行 `media_asset` upsert，并刷新 `last_seen_scan`、基础元数据和扫描 token。
- 如果旧指纹仍可复用，本轮会跳过 Bitmap 解码、dHash/colorHash、质量分计算和视频抽帧。
- 只有新增资源、内容或关键元数据变化、算法版本变化、指纹尺寸变化、视频模式变化、旧指纹缺失或上次未完成的资源，才会重新计算指纹。

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

因此首次扫描和后续 `forceFull = true` 的耗时表现不同：

```text
首次扫描：
MediaStore 全量枚举
-> 所有资源首次入库
-> 所有资源计算图片/视频指纹

后续 forceFull 扫描：
MediaStore 全量枚举
-> 所有资源对账和刷新 last_seen_scan
-> 未变化资源复用旧 fingerprint
-> 只对新增、变化、算法版本不一致或旧指纹缺失的资源重算
```

这类扫描更准确地称为“全量校验媒体库集合 + 增量重算指纹”。它既能发现系统相册中已经删除的资源，
也能避免每次全量扫描都重新读取 9k/10w 张缩略图。

注意：文件名、bucket、path_hint 不进入 `SourceSignature`。这些字段只影响展示和分类，不应该触发 dHash/colorHash 或视频帧指纹重算。

## 6. 图片/截图指纹 Bitmap 来源

图片和截图的指纹输入由 `MediaBitmapLoader.loadFingerprintBitmapWithSource()` 完成。扫描链路默认目标尺寸是 256，接入方可以通过 `SimilarScanRequest.imageFingerprintSize` 配置，SDK 会将该值限制在 96..512。

```kotlin
DEFAULT_IMAGE_FINGERPRINT_SIZE = 256
```

加载顺序：

```text
API 29+ ContentResolver.loadThumbnail(asset.uri, Size(imageFingerprintSize, imageFingerprintSize), null)
-> resolver.openInputStream(asset.uri) + BitmapFactory inSampleSize decode
-> 查询 MediaStore.MediaColumns.DATA
-> BitmapFactory.decodeFile(path, options)
-> normalizeFingerprintBitmap(bitmap, imageFingerprintSize)
```

API 28 及以下会在自行解码前尝试旧版 `MediaStore.Images.Thumbnails.getThumbnail(..., MINI_KIND, null)`。
API 29+ 如果 `loadThumbnail()` 失败，会直接进入 URI/DATA 降采样解码，不再调用旧版
`MediaStore.Images.Thumbnails`。原因是新系统里旧接口通常仍会代理到 `loadThumbnail()`，
重复调用只会放大失败日志和耗时。

`normalizeFingerprintBitmap()` 只做等比缩放：

```text
maxSide = max(width, height)
if maxSide <= imageFingerprintSize: 原样返回
scale = imageFingerprintSize / maxSide
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

找到 duplicateReference 候选后，SDK 可以按需计算并缓存 SHA-256：

```text
当前资源 content_sha256
候选资源 content_sha256
```

SHA-256 不是进入 Duplicate 的硬条件，而是字节级验证证据，用来区分“字节完全一致”和“组合引用一致”。当前默认 `calculateDuplicateSha256DuringScan = false`，不会在扫描主链路中读全文件；接入方如需扫描时立即补证据，可以在 `SimilarScanRequest` 中开启。

Duplicate 优先级高于 Similar：

- 写入 Duplicate 时会把相关资源从 Similar 分类移除。
- 已在 Duplicate 的资源不会被后续 Similar 链路重新加入。
- `ProductCategoryBuilder` 也会在展示层再次排除重复计数。

## 12. 图片/截图处理流程

图片/截图计算和提交已拆成两段。计算阶段在线程池中执行：

```text
loadFingerprintBitmapWithSource(asset, imageFingerprintSize)
-> HashCalculator.buildHash(bitmap)
-> metadataScore(asset)
```

提交阶段在扫描线程中执行：

```text
findDuplicateReferenceCandidates()
-> 可选：有 Duplicate 候选且配置开启时计算 SHA-256
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

当前源码通过 `SimilarScanRequest.videoFingerprintMode` 控制视频指纹策略：

```text
FAST              -> 系统缩略图单帧优先，失败时 MMR 3 个时间点
BALANCED          -> 系统缩略图 + MMR 4 个时间点，SDK 默认策略
ACCURATE          -> MMR 7 个时间点，不把系统缩略图作为唯一依据
REFERENCE_COMPAT -> 参考帧：不使用系统缩略图，按 0..duration 抽取 7 到 13 帧
ADAPTIVE_BALANCED -> 先尝试系统缩略图；慢失败/低成功率时本轮跳过缩略图，直接用 BALANCED 4 帧 MMR
```

SDK 对其他产品的默认值仍是 `BALANCED`，因为它在速度、召回和误合并之间更稳。Demo 默认使用
`ADAPTIVE_BALANCED`，用于规避部分设备上系统视频缩略图慢失败的问题。宿主产品如需更高视频召回，
可显式传入 `VideoFingerprintMode.REFERENCE_COMPAT`。

### 13.1 系统缩略图路径

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

如果成功，`FAST` 模式会直接保存单帧指纹：

```kotlin
VideoFingerprint(
    frames = listOf(HashCalculator.buildHash(thumbnail)),
    qualityScore = MediaQualityAnalyzer.metadataScore(asset),
    source = SYSTEM_THUMBNAIL
)
```

`BALANCED` 模式不会只依赖单帧缩略图，而是把系统缩略图作为第一帧，再补充 MMR 时间点。这样比纯 MMR 快，同时避免视频结果完全退化为封面相似。

`REFERENCE_COMPAT` 不读取系统视频缩略图，避免“单帧封面”和“多帧相似”混用同一语义。

### 13.2 MMR 抽帧路径

需要 MMR 抽帧时，SDK 优先查询 DATA 真实路径：

```text
query(asset.uri, MediaStore.MediaColumns.DATA)
```

如果路径为空、文件不存在或不可读，会回退 `ContentResolver.openFileDescriptor(asset.uri, "r")`，再调用 `MediaMetadataRetriever.setDataSource(fileDescriptor)`。两者都失败时，才返回无效指纹或退回系统缩略图单帧。

路径可用时：

```text
MediaMetadataRetriever.setDataSource(path)
-> 读取 METADATA_KEY_DURATION，失败则用 asset.duration
-> buildSampleTimes(durationMs)
-> 每个时间点 extractFrame()
-> 每帧 HashCalculator.buildHash()
```

普通模式抽帧时间点避开绝对首尾，减少黑屏、片头、尾帧对结果的影响：

```text
FAST fallback: [10%, 50%, 90%]
BALANCED:      [12%, 38%, 64%, 88%]
ACCURATE:      [8%, 22%, 36%, 50%, 64%, 78%, 92%]
```

REFERENCE_COMPAT 模式按 `ExtractionRule(minInterval=2.0, maxInterval=10.0, frameCount=7, maxFrameCount=13)` 生成时间点：

```text
duration <= 0 -> [0]
7 帧等距间隔在 2s..10s 内 -> 0..duration 等距 7 帧
7 帧等距间隔小于 2s -> 每 2s 抽一帧，并包含 duration
7 帧等距间隔大于 10s -> 每 10s 抽一帧；如果超过 13 帧，则改为 0..duration 等距 13 帧
```

该规则的时间点生成方式为：等距函数包含 0 和 duration，固定间隔函数从 0 开始并补上 duration。

抽帧 API 分支：

| API | 方法 | 输出 |
| --- | --- | --- |
| 30+ | `getScaledFrameAtTime(timeUs, OPTION_CLOSEST_SYNC, 9, 8, BitmapParams ARGB_8888)` | 9x8 ARGB_8888 |
| 27-29 | `getScaledFrameAtTime(timeUs, OPTION_CLOSEST_SYNC, 9, 8)` | 9x8 |
| 23-26 | `getFrameAtTime(timeUs, OPTION_CLOSEST_SYNC)` 后 `Bitmap.createScaledBitmap(9, 8)` | 9x8 |

抽帧失败会保留无效占位：

```kotlin
CombinedHash(-1L, emptyArray())
```

普通模式会过滤低信息帧。判断基于 9x8 帧的亮度范围和平均相邻边缘差，纯黑、纯白、纯色或信息量极低的帧不会参与相似比较。比较时会跳过无效帧，但无效帧不会从列表中删除。

`REFERENCE_COMPAT` 不做低信息帧过滤，抽到的帧会原样进入帧序列，避免过滤黑屏/静态帧后改变帧位置和最小命中数。

## 14. 视频候选召回

实时视频扫描不使用图片的 BK-Tree 索引。普通视频模式会先计算当前视频指纹，再从 SQLite 查候选：

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

候选召回只做粗筛。扫描器还会先执行帧级 dHash 汉明距离预筛：如果两段视频没有任意有效帧对落在当前媒体类型的最大候选距离内，就不会进入完整多帧精判。这个预筛不会改变最终阈值口径，最终仍由 `VideoFingerprint.isSimilarTo()` 决定。

`REFERENCE_COMPAT` 为了提高相似视频召回，不使用时长桶和宽高比桶过滤，只限定：

```sql
WHERE a.type = ?
  AND a.state = 'ACTIVE'
  AND a.fingerprint_algorithm_version = ?
  AND a.id != ?
  AND f.video_frame_hashes IS NOT NULL
ORDER BY a.date_added DESC
```

也就是说参考帧模式采用“同类型、同算法版本宽召回 + 帧级预筛 + 多帧精判”。这会比普通模式召回更多候选，适合召回优先场景；其他产品如果更看重性能和误召回控制，应继续使用 `BALANCED` 或 `ACCURATE`。

## 15. 视频/录屏相似判断

视频和录屏使用与截图相同的严格阈值：

| dHash 汉明距离 | colorHash 条件 | 结果 |
| --- | ---: | --- |
| 0..2 | 不需要 | 相似 |
| 3..10 | <= 5 | 相似 |
| 11..15 | <= 2 | 相似 |
| >= 16 | - | 不相似 |

`VideoFingerprint.isSimilarTo(other, kind)` 先检查双方是否至少有一个有效帧。无有效帧则不相似。

### 15.1 单帧语义

当前 `VideoFingerprint` 会持久化来源：

```text
SYSTEM_THUMBNAIL
HYBRID_THUMBNAIL_AND_FRAMES
MMR_FRAMES
REFERENCE_FRAMES
```

只有双方都是 `SYSTEM_THUMBNAIL` 且都只有一个有效帧时，才允许单帧相似直接判定视频相似。只要任一侧是混合帧或 MMR 多帧，就进入多帧匹配规则，避免单帧封面结果与多帧结果混用同一语义。

`REFERENCE_FRAMES` 有独立语义：双方都来自参考帧模式时，使用参考帧命中规则，不套用普通多帧模式的“候选帧只能消费一次”和“命中位置间隔”约束。

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

### 15.3 参考帧比较

当双方 `source` 都是 `REFERENCE_FRAMES`：

```text
requiredMatches = max(1, min(2, validFrameCountA, validFrameCountB))
遍历 A 的每个有效帧
-> 遍历 B 的每个有效帧
-> 任意帧对 CombinedHash.isSimilarTo(kind) 通过就累计一次命中
-> 命中数达到 requiredMatches 即判定相似
```

该模式的视频阈值口径为：正常至少 2 帧命中；当任一侧只有 1 个有效帧时降为 1 帧命中。该模式不要求候选帧唯一消费，也不要求命中帧位置间隔，因此召回会强于普通多帧模式，误合并控制则弱一些。

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

关键查询索引：

```text
media_asset(media_store_id, type) unique
media_asset(type, state, fingerprint_algorithm_version)
media_asset(type, state, created_at, date_added, media_store_id)
similar_group(category, type)
similar_group_item(asset_id)
fingerprint(hash_prefix, aspect_bucket, duration_bucket)
fingerprint(image_hash)
fingerprint(duration_bucket, aspect_bucket)
```

其中 `media_asset(type, state, created_at, date_added, media_store_id)` 主要服务首页预览、
Other 类详情分页和按媒体时间倒序展示；`similar_group(category, type)` 主要服务产品分类
过滤、分组重建和 Other 排除已命中资源。

### 17.1 数据库读写边界

`ScanDatabase` 是 SDK 内部数据库访问层，应用业务层不应直接调用。对外只通过
`SimilarScanClient` 读取结果或提交删除状态。

当前数据库方法大体分为两类：

| 类型 | 典型方法 | 作用 |
| --- | --- | --- |
| 扫描写入 | `upsertAsset`、`upsertFingerprint`、`insertSimilarRelation`、`rebuildSimilarGroups` | 扫描、指纹、相似/相同候选和最终分组写入。 |
| 展示读取 | `loadGroups`、`loadProductCategoryAssets`、`loadGroupAssetsPage` | 首页、分类详情、分组详情读取。 |
| 删除状态 | `markDeletePending`、`finalizeDelete`、`restoreDeletePending`、`recoverStaleDeletePending` | 用户删除流程中的一致性保护。 |
| 维护清理 | `removeAssetsNotSeenInScan`、`cleanupInvalidGroups` | 全量扫描对账和空分组清理。 |

当前展示读取方法是合理的，原因：

- 首页可以通过 `previewAssetLimit` 只读取少量预览资源，避免一次性加载大分类全部资源。
- 分类和分组的真实数量、真实大小、最新媒体时间由 SQL 聚合获取，不依赖预览资源数量。
- 平铺类详情使用 `loadProductCategoryAssets(type, offset, limit)` 分页读取。
- 相似/相同分组详情使用 `loadGroupAssetsPage(groupId, offset, limit)` 分页读取。
- `loadProductCategoryAssets()` 只服务平铺分类；传入 grouped 分类时返回空列表，不抛异常，避免接入方误用导致线上崩溃。
- DB 写入路径和展示读取路径没有混在对外 API 中，业务层无法直接修改指纹或分组表。

仍需注意：

- `ScanDatabase` 当前同时承载写入、读取、删除状态和维护清理，内部职责较多；作为 SDK 内部类可以接受，
  但如果后续数据库逻辑继续膨胀，可以拆成 `ScanWriteStore`、`ScanReadStore`、`DeleteStateStore`。
- 不建议把 `ScanDatabase` 暴露给宿主业务层，否则会破坏扫描状态、删除状态和分组结果的一致性。
- 新增展示接口时优先从 `SimilarScanClient` 暴露稳定 DTO，不直接暴露 SQL、Cursor 或 Room Entity。

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
OTHER_VIDEOS
OTHER
```

### 19.1 ProductCategoryType 聚合规则

`ProductCategoryType` 是产品展示分类，不等同于数据库中的 `MediaKind`。当前聚合规则如下：

| ProductCategoryType | grouped | 底层来源 |
| --- | --- | --- |
| `SIMILAR` | true | `GroupCategory.SIMILAR + MediaKind.PHOTO` |
| `DUPLICATES` | true | `GroupCategory.DUPLICATE + PHOTO/SCREENSHOT` |
| `SIMILAR_SCREENSHOTS` | true | `GroupCategory.SIMILAR + SCREENSHOT` |
| `SIMILAR_VIDEOS` | true | `GroupCategory.SIMILAR + VIDEO/SCREEN_RECORDING` |
| `OTHER_SCREENSHOTS` | false | 未进入 Similar/Duplicate 的 `SCREENSHOT` |
| `OTHER_VIDEOS` | false | 未进入 Similar 的 `VIDEO/SCREEN_RECORDING` |
| `OTHER` | false | 未进入 Similar/Duplicate 的 `PHOTO`，包含聊天来源图片 |

说明：

- `grouped = true` 表示分类下包含多个真实相似/相同分组，详情页通常先展示分组，再按 `groupId` 分页读取组内资源。
- `grouped = false` 表示平铺类资源集合，详情页可直接按分类分页读取资源。
- 录屏仍以 `SCREEN_RECORDING` 入库和识别，但产品层归并到视频分类。
- 视频相似候选按视频族召回，`VIDEO` 与 `SCREEN_RECORDING` 可以互相进入候选；最终仍需通过多帧 `dHash + colorHash` 精判。
- 如果视频候选中任一方是录屏，精判阶段使用更严格的录屏阈值，降低静态 UI/录屏内容误合并。
- 聊天图片不再单独成为产品分类，`chatSource` 字段仍保留给业务层自定义使用。
- `ProductCategory.itemCount`、`ProductCategory.totalSize`、`SimilarGroup.totalAssetCount`、`SimilarGroup.totalSizeBytes` 都来自完整分组聚合，不受 `previewAssetLimit` 影响。
- `ProductCategory.assets` 和 `SimilarGroup.assets` 是当前接口返回的展示样本；首页预览场景中不能把它们当作完整资源列表。

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

- 视频 `FAST` 模式仍可能退化为系统缩略图单帧，适合速度优先场景；默认 `BALANCED` 已补充 MMR 时间点。
- `ADAPTIVE_BALANCED` 会在本轮扫描内关闭慢失败的系统缩略图路径，并通过
  `video_thumbnail_attempt/success/fail/slow/adaptive_skipped` 计数辅助定位设备差异。
- `REFERENCE_COMPAT` 会提高相似视频召回，但候选范围更宽、多帧比较更宽松，适合召回优先场景，不建议作为所有产品的默认模式。
- 图片和视频指纹计算并发后，metrics 中工作线程耗时是累加值，不等同于墙钟时间；对比性能时优先看 `scan=... elapsed=...`。
- 图片指纹输入默认是最大边 256 的缩略图，不是原图。接入方调小 `imageFingerprintSize` 会提升速度但可能影响细节召回；调大则需要接受更高解码和像素遍历成本。
- 截图关键词 `startsWith("screen_")` 较宽，可能误归类部分非截图图片。
- 录屏关键词包含 `recording`、`capture`、`mirror`、`cast`，可能误归类部分普通视频。
- Duplicate 不要求 SHA-256 一致，SHA-256 默认延后计算且仅为证据字段。这符合当前 duplicateReference 口径，但比字节级重复更宽。
- 外部系统相册删除主要依赖全量扫描对账，增量扫描期间旧记录可能暂时保留。
