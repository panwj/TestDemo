# SimilarScanDemo 当前技术方案

> 本文以当前代码为准，记录 SimilarScanDemo 最新扫描、指纹、相似判断、分组和 UI 刷新方案。
> 历史分析文档仅作为排查过程参考，不再代表最终实现。

## 1. 当前支持范围

Demo 当前只扫描图片和视频两类 MediaStore 资源，并在应用内二次分类：

| MediaStore 来源 | Demo 类型 | 结果分类 |
| --- | --- | --- |
| `MediaStore.Images` | `PHOTO` | Similar、Duplicates、Other |
| `MediaStore.Images` | `SCREENSHOT` | Duplicates、Similar Screenshots、Other Screenshots |
| `MediaStore.Video` | `VIDEO` | Similar Videos、Other Videos |
| `MediaStore.Video` | `SCREEN_RECORDING` | Similar Videos、Other Videos |

当前版本不请求音频权限，不枚举音频资源，也不做音频相似扫描。

## 2. 扫描入口与权限

入口位于：

```text
MainActivity
MediaScanService
SimilarMediaScanner
```

流程：

```text
用户授予媒体权限
-> 如 Android 13+ 需要通知权限，则只请求一次
-> MediaScanService 前台服务启动
-> SimilarMediaScanner 在后台线程执行扫描
-> MainActivity 通过包内广播接收进度
-> 首页异步节流刷新数据库结果
```

首次安装时媒体权限和通知权限会连续弹出。`MainActivity` 使用
`pendingScanAfterPermission`、`notificationRequestInFlight` 和
`notificationPermissionHandled` 串起权限链路，避免通知权限返回时界面误回到
`Rescan`，并确保用户拒绝通知权限后仍继续扫描。

## 3. MediaStore 资源获取

资源获取位于：

```text
scanner/MediaStoreRepository.kt
```

图片查询：

```text
MediaStore.Images.Media.EXTERNAL_CONTENT_URI
WHERE _size > 0
ORDER BY date_added DESC
```

视频查询：

```text
MediaStore.Video.Media.EXTERNAL_CONTENT_URI
WHERE _size > 0
ORDER BY date_added DESC
```

读取字段包括：

```text
_id
_display_name
_size
width / height
duration（视频）
date_added
datetaken
date_modified
bucket_display_name
mime_type
relative_path（API 29+）
is_favorite（API 30+）
generation_added / generation_modified（API 30+）
```

`createdAt` 使用：

```text
datetaken > 0 ? datetaken : date_added * 1000
```

截图和录屏不是系统标准类型，Demo 使用文件名关键词二次分类：

```text
Images -> PHOTO / SCREENSHOT
Video  -> VIDEO / SCREEN_RECORDING
```

## 4. 全量扫描与增量扫描

扫描状态位于：

```text
scanner/ScanStateStore.kt
```

触发全量扫描的条件：

- 用户主动 `forceFull`。
- 数据库中没有资源。
- 当前不是完整媒体授权。
- `MediaStore.getVersion()` 变化。
- 距离上次完整扫描超过 24 小时。

增量扫描使用 API 30+ 的 `GENERATION_MODIFIED`：

```text
Images: generation_modified > checkpoint.imageGeneration
Videos: generation_modified > checkpoint.videoGeneration
```

扫描中断时 checkpoint 不会推进，下次会从旧 checkpoint 继续。全量扫描完成且拥有完整媒体权限时，会调用
`removeAssetsNotSeenInScan()` 清理 MediaStore 中已经不存在的旧记录。

注意：当前删除同步主要依赖全量扫描或 App 内删除流程。若用户在系统相册删除资源，而下一次只触发增量扫描，旧记录可能暂时保留到下一次全量扫描。

## 5. 图片和截图指纹 Bitmap 获取

图片/截图指纹输入位于：

```text
scanner/MediaBitmapLoader.loadFingerprintBitmap()
```

该方法只用于 `PHOTO` 和 `SCREENSHOT` 的指纹计算，不用于视频指纹。

当前顺序：

```text
API 29+:
1. ContentResolver.loadThumbnail(
     MediaStore.Images.Media.EXTERNAL_CONTENT_URI + mediaStoreId,
     Size(256, 256),
     null
   )

所有版本 fallback:
2. resolver.openInputStream(asset.uri) + BitmapFactory inSampleSize
3. 查询 MediaStore.MediaColumns.DATA 后 BitmapFactory.decodeFile(path)
```

因此图片分析默认使用最大边 256 的系统缩略图或降采样 Bitmap。`SimilarScanRequest.imageFingerprintSize`
允许在 96 到 512 之间调整，当前 Demo 保持默认 256，以降低 MediaStore 缩略图读取、
缩放、像素遍历和 GC 成本。

UI 展示图片使用 `loadBitmap()`，默认请求 1024 以内的预览图，优先
`resolver.loadThumbnail(asset.uri)`，失败后走降采样解码。指纹输入和 UI 展示路径相近，
但尺寸和调用入口不是完全同一方法。

## 6. 视频和录屏指纹 Bitmap 获取

视频/录屏指纹位于：

```text
similarity/VideoFingerprintCalculator.kt
```

视频不会复用 `MediaBitmapLoader.loadFingerprintBitmap()`。当前实现优先使用 MediaStore `DATA`
真实路径，路径不可用时会回退 `content://` 文件描述符：

```text
查询 MediaStore.MediaColumns.DATA
-> path 可读：MediaMetadataRetriever.setDataSource(path)
-> path 为空 / 文件不存在 / 文件不可读：openFileDescriptor(asset.uri, "r")
-> 两者都失败：返回无效视频指纹；普通模式下如果系统缩略图有效，则退回系统缩略图单帧
```

Demo 扫描服务当前使用 `VideoFingerprintMode.BALANCED`。该模式会先尝试系统视频缩略图，
再补充少量 MMR 时间点，避免全部视频都走 7 到 13 帧参考帧抽取。

抽帧规则：

```text
系统视频缩略图有效时先生成第一帧 CombinedHash
读取 METADATA_KEY_DURATION，失败则使用 asset.duration
-> buildSampleTimes(durationMs)
-> 每个时间点使用 MediaMetadataRetriever 抽帧
-> 每帧生成 CombinedHash(dHash + colorHash)
```

不同模式中的采样参数：

```text
FAST:      系统缩略图优先；失败时使用少量兜底时间点
BALANCED:  系统缩略图 + 少量 MMR 时间点，当前 Demo 默认使用
ACCURATE:  更多 MMR 时间点
REFERENCE_COMPAT:
           不使用系统缩略图，按 MIN_INTERVAL=2s、MAX_INTERVAL=10s、
           NORMAL_FRAME_COUNT=7、MAX_FRAME_COUNT=13 生成参考帧
```

因此，只有显式切换到 `REFERENCE_COMPAT` 时，才会进入 7 到 13 帧参考帧策略。

不同 Android 版本抽帧方式：

| API | 抽帧方式 |
| --- | --- |
| 30+ | `getScaledFrameAtTime(timeUs, OPTION_CLOSEST_SYNC, 9, 8, BitmapParams ARGB_8888)` |
| 27-29 | `getScaledFrameAtTime(timeUs, OPTION_CLOSEST_SYNC, 9, 8)` |
| 23-26 | `getFrameAtTime(timeUs, OPTION_CLOSEST_SYNC)` 后缩放到 `9x8` |

抽帧失败时会保留无效帧：

```text
CombinedHash(-1L, emptyArray())
```

比较时跳过无效帧，但帧列表本身仍然存在。

## 7. UI 视频封面 Bitmap 获取

视频 UI 封面位于：

```text
scanner/MediaBitmapLoader.loadBitmap()
```

它只服务展示，不参与视频相似判断。

当前顺序：

```text
1. MediaMetadataRetriever + content Uri FileDescriptor
   getFrameAtTime(0L, OPTION_CLOSEST_SYNC)
2. 查询 DATA 后 ThumbnailUtils.createVideoThumbnail(path, MINI_KIND)
3. MediaStore.Video.Thumbnails.getThumbnail(id, MINI_KIND)
```

因此视频分析用 Bitmap 与 UI 封面 Bitmap 不一致：

- 分析：系统视频缩略图 + MMR 时间点，或在参考帧模式下使用 7 到 13 个 9x8 指纹帧。
- 展示：content Uri 文件描述符 + 0ms 同步关键帧，失败后 ThumbnailUtils/MediaStore 缩略图。

这是当前代码刻意区分的结果：分析路径用于对齐指纹，展示路径用于尽量贴近系统相册封面。

## 8. dHash

位置：

```text
similarity/KotlinDHash.kt
similarity/HashCalculator.kt
```

当前只保留 Kotlin dHash，不包含 native so、JNI 桥接或运行时 hash 后端切换。

算法：

```text
输入 Bitmap
-> 如非 ARGB_8888，则 copy 为 ARGB_8888
-> 在原图上按 9 列 x 8 行网格做双线性灰度采样
-> 每行比较相邻采样点灰度
-> 8 行 x 8 次比较
-> 得到 64-bit imageHash
```

灰度公式：

```text
0.299 * R + 0.587 * G + 0.114 * B
```

无效输入返回：

```text
-1L
```

## 9. colorHash

位置：

```text
similarity/HashCalculator.kt
```

算法：

```text
输入 Bitmap
-> 如非 ARGB_8888，则 copy 为 ARGB_8888
-> 统计 R/G/B 三个 0..255 直方图
-> 每 32 个值合并为 1 桶
-> 得到 Double[8][3]
-> 每个桶除以 pixelCount / 16
```

颜色距离：

```text
sum(abs(left[bucket][channel] - right[bucket][channel])).toLong()
```

## 10. 相似阈值

位置：

```text
similarity/Threshold.kt
similarity/CombinedHash.kt
```

### 10.1 普通照片

| dHash 汉明距离 | colorHash 距离要求 | 结果 |
| --- | ---: | --- |
| 0..4 | 不需要 | 相似 |
| 4..10 | 0..7 | 相似 |
| 10..17 | 0..5 | 相似 |
| >=18 | 不相似 | 不相似 |

代码中先判断：

```text
distance >= 18 -> false
```

因此距离等于 18 不通过。

### 10.2 截图、普通视频、录屏

当前三类统一使用严格阈值：

| dHash 汉明距离 | colorHash 距离要求 | 结果 |
| --- | ---: | --- |
| 0..2 | 不需要 | 相似 |
| 2..10 | 0..5 | 相似 |
| 10..15 | 0..2 | 相似 |
| >=16 | 不相似 | 不相似 |

同理，距离等于 16 不通过。

## 11. 重复图片

位置：

```text
scanner/SimilarMediaScanner.processVisual()
database/ScanDatabase.findDuplicateReferenceCandidates()
```

Duplicate 只用于图片/截图视觉资源。当前规则按竞品 duplicateReference 口径：

```text
媒体类型
宽度
高度
imageHash
isEdited
size
```

SHA-256 会按需计算并缓存到 fingerprint 表，用作“字节完全相同”的验证证据，但不是进入
Duplicates 的唯一条件。

Duplicate 与 Similar 互斥：

```text
Duplicate 优先
进入 Duplicate 后不会同时计入 Similar
```

## 12. 相似图片候选召回与精判

图片/截图扫描中，Demo 使用 BK-Tree 做 dHash 候选召回：

```text
当前库中同类型 imageHash
-> HammingBkTree.query(imageHash, Threshold.maxCandidateDistance(kind))
-> 回库读取候选 colorHash
-> CombinedHash.isSimilarTo(candidate, kind)
```

BK-Tree 只做候选召回，不改变最终判断。最终仍由 dHash 距离和 colorHash 距离共同决定。

扫描开始时会从 SQLite 已有指纹构建图片/截图 BK-Tree。新资源完成指纹后，会实时加入对应 BK-Tree，以便后续批次继续匹配。

## 13. 相似视频比较策略

视频/录屏候选来自数据库中同类型的已完成视频指纹：

```text
findVideoFingerprintCandidates(token.assetId, asset)
```

最终比较位于：

```text
VideoFingerprint.isSimilarTo(other, kind)
```

当前策略：

```text
left.frames 逐帧遍历
-> right.frames 逐帧遍历
-> 单帧 CombinedHash 使用当前 kind 阈值判断
-> 当前 left frame 找到一个相似 right frame 后 matchedCount + 1，并跳出 right 循环
-> matchedCount >= 2 判定两个视频相似
```

因此一段视频至少需要两个左侧有效帧各自命中候选视频中的有效帧，才进入 Similar Videos；录屏也归并到该分类展示。

## 14. 分组策略

扫描中会先增量写入分组，方便 UI 及时展示。扫描完成后调用：

```text
ScanDatabase.rebuildSimilarGroups(processedKinds)
```

完成阶段按类型重建 Similar 组：

```text
删除当前类型旧 Similar 组
-> 读取 ACTIVE + DONE + 非 Duplicate 的指纹
-> 构建 BK-Tree
-> 依次取锚点
-> 只把与锚点直接相似的资源放入同组
-> 从剩余集合移除整组
```

它不是连通分量合并：

```text
A≈B, B≈C, A≉C
```

这种情况下 C 不会因为 B 被并入 A 组。

锚点顺序：

| 类型 | 顺序 |
| --- | --- |
| PHOTO / SCREENSHOT | `created_at ASC, media_store_id DESC` |
| VIDEO / SCREEN_RECORDING | `date_added DESC` |

## 15. SQLite 数据源和并发控制

SQLite 是唯一可信数据源：

```text
media_asset
fingerprint
similar_group
similar_group_item
```

每个资源有：

```text
state
revision
fingerprint_status
last_seen_scan
generation_added
generation_modified
```

用户删除时：

```text
ACTIVE -> DELETE_PENDING
revision + 1
```

扫描任务提交指纹时必须携带扫描开始时拿到的 revision。若用户在计算期间删除资源，提交会失败，避免已删除资源重新出现在结果中。

## 16. 首页展示与性能优化

首页不直接持有扫描线程对象，也不在主线程做重计算。

当前刷新策略：

```text
MediaScanService 发送进度广播
-> MainActivity 更新文字
-> 800ms 节流请求结果刷新
-> 单线程后台读取 SQLite + ProductCategoryBuilder.build()
-> 主线程 submitList 更新已有 adapter
```

首页分类预览图使用：

```text
ui/ThumbLoader.kt
```

异步解码并使用 LruCache，避免 ListView 滑动时同步解码 bitmap。

Other 分类可能包含大量资源。当前首页不再为了展示预览一次性读取完整资源集合，而是由
业务层传入首页预览数量：

```kotlin
client.loadProductCategories(previewAssetLimit = 2)
```

该参数只限制每个分组返回的预览资源，不影响分类和分组的真实数量、真实大小。进入某个
分类详情页后，先按当前分类单独读取摘要，再分页读取资源：

```kotlin
client.loadProductCategory(categoryType, previewAssetLimit = 0)
client.loadProductCategoryAssets(categoryType, offset, limit)
```

相似/相同分组详情通过 `loadSimilarGroupAssets(groupId, offset, limit)` 追加横向资源。
分组排序使用 SQL 聚合出的 `latestAssetTimeMillis`，不会受首页预览资源数量影响。
SDK 数据库读取资源列表时按固定页大小分页查询，避免 Other 等大分类一次性塞满
CursorWindow。Demo 业务 UI 统一按媒体时间倒序展示：

```text
mediaTime = max(createdAt, dateAdded)
mediaTime DESC, dateAdded DESC, id DESC
```

首页默认仅展示每组前 2 个预览资源；分类数量使用聚合统计值，详情页列表使用当前分类的
完整资源集合。

## 17. 当前已知边界

- 系统相册外部删除资源后，若下一次只触发增量扫描，旧记录可能保留到下一次全量扫描。
- 视频指纹只接受 DATA 真实路径，路径不可读时会得到无效指纹。
- 视频指纹抽帧时间单位按当前代码记录为毫秒口径，后续如继续对齐竞品，应结合导出的逐帧时间和 hash 再确认。
