# Product-Level Similar Media Scan Design

> 历史文档：本文是早期产品级方案草稿，包含音频、旧视频描述和旧阈值口径。
> 当前实现请以 `docs/current_technical_solution.md` 为准。
>
> 历史方案说明：v15 已按 Cleanup 竞品扫描范围删除音频枚举、音频权限和音频相似逻辑。
> 本文中涉及 Audio/录音的章节仅保留为早期扩展设计，不属于当前 Demo 实现。

## 1. 背景与目标

本 Demo 实现的是一套可扩展到大规模本地媒体库的相似资源扫描方案，目标对齐清理类产品中的相似照片、重复照片、相似截图、相似视频、相似录屏和录音疑似重复能力。

设计目标：

- 支持 2 万到 10 万级媒体资源。
- 最终扫描完整个媒体库。
- 扫描过程中持续产出结果，而不是完成后一次性展示。
- 不一次性加载全部资源到内存。
- 支持指纹缓存、断点续扫和后续增量扫描。
- 保持方案本地化，不依赖云端识别服务。

当前 Demo 使用 Kotlin + XML，未使用 Compose；本地存储使用 `SQLiteOpenHelper`，正式项目可替换为 Room。

## 2. 支持范围

当前支持的资源类型：

| 类型 | 来源 | 相似策略 |
| --- | --- | --- |
| 普通照片 | `MediaStore.Images` | `dHash + colorHash` |
| 截图 | `MediaStore.Images` + 路径/文件名识别 | `dHash + colorHash` |
| 普通视频 | `MediaStore.Video` | 视频缩略图/封面帧 `dHash + colorHash` |
| 录屏 | `MediaStore.Video` + 路径/文件名识别 | 视频缩略图/封面帧 `dHash + colorHash` |
| 录音 | `MediaStore.Audio` | 时长桶 + 大小桶，后续可替换为音频 fingerprint |

截图识别依据：

```text
screenshot
screen shot
screenshots
截屏
截图
```

录屏识别依据：

```text
screenrecord
screen_record
screen recording
screen recorder
录屏
```

## 3. 整体架构

目录职责：

```text
model/
  数据模型：媒体资源、扫描结果、扫描阶段、指纹状态

permission/
  target 36 媒体权限处理

scanner/
  MediaStore 批量枚举
  缩略图加载
  扫描编排
  媒体类型识别

similarity/
  dHash
  colorHash
  CombinedHash
  分媒体类型阈值

database/
  SQLite 表结构
  指纹缓存
  候选桶查询
  相似组维护

ui/
  结果列表与缩略图展示

util/
  Cursor 扩展和格式化工具
```

核心链路：

```text
MainActivity
-> SimilarMediaScanner
-> MediaStoreRepository
-> MediaBitmapLoader
-> HashCalculator
-> ScanDatabase
-> SimilarGroupAdapter
```

## 4. 从打开 App 到扫描完成的流程

### 4.1 打开 App

1. 初始化页面。
2. 初始化 `SimilarMediaScanner`。
3. 读取 SQLite 中已存在的相似组。
4. 如果有缓存结果，立即展示。
5. 用户点击扫描按钮后检查权限。

体验目标：

```text
打开 App 后先展示旧结果
扫描启动后实时刷新进度
新结果边扫边出现
```

### 4.2 权限处理

权限工具：

```text
permission/MediaPermissionHelper.kt
```

权限策略：

Android 12 及以下：

```text
READ_EXTERNAL_STORAGE
```

Android 13：

```text
READ_MEDIA_IMAGES
READ_MEDIA_VIDEO
READ_MEDIA_AUDIO
```

Android 14+ / target 36：

```text
READ_MEDIA_IMAGES
READ_MEDIA_VIDEO
READ_MEDIA_AUDIO
READ_MEDIA_VISUAL_USER_SELECTED
```

说明：

- `READ_MEDIA_VISUAL_USER_SELECTED` 用于兼容用户只授权部分照片/视频的场景。
- 如果用户只授权部分媒体，扫描结果只覆盖可访问资源。

### 4.3 分批枚举资源

枚举入口：

```text
scanner/MediaStoreRepository.kt
```

核心方法：

```kotlin
forEachMediaBatch(batchSize: Int, onBatch: (List<MediaAsset>) -> Unit)
```

当前批大小：

```text
500
```

流程：

```text
查询 Images Cursor
-> 每 500 个图片资源回调一次
-> 查询 Videos Cursor
-> 每 500 个视频资源回调一次
-> 查询 Audio Cursor
-> 每 500 个音频资源回调一次
```

这样做的原因：

- 不一次性持有 10 万个 `MediaAsset` 对象。
- Cursor 读取和资源处理可以交替进行。
- 每批完成后可以及时落库并刷新 UI。

## 5. 指纹计算方案

### 5.1 视觉资源加载

缩略图加载位置：

```text
scanner/MediaBitmapLoader.kt
```

加载优先级：

```text
1. ContentResolver.loadThumbnail(uri, Size(thumbSize, thumbSize), null)
2. 视频资源 fallback 到 ThumbnailUtils.createVideoThumbnail
3. 图片资源 fallback 到 openInputStream + BitmapFactory
4. 使用 inSampleSize 降采样
```

默认指纹缩略图尺寸：

```text
1024 x 1024
```

UI 小缩略图尺寸：

```text
180 x 180
```

### 5.2 dHash

实现位置：

```text
similarity/HashCalculator.kt
```

算法：

```text
Bitmap
-> 转 ARGB_8888
-> 缩放到 9 x 8
-> 转灰度
-> 横向相邻像素比较
-> 生成 64-bit imageHash
```

优势：

- 计算快。
- 对缩放和压缩相对稳定。
- 适合本地批量扫描。

不足：

- 对旋转、裁剪、大幅滤镜不够鲁棒。
- 不理解语义相似。

### 5.3 colorHash

实现位置：

```text
similarity/HashCalculator.kt
```

结构：

```text
Double[8][3]
```

含义：

```text
8 个颜色桶
3 个通道：R / G / B
```

每个通道按 32 为步长分桶：

```text
0-31
32-63
64-95
96-127
128-159
160-191
192-223
224-255
```

用途：

- dHash 判断画面结构。
- colorHash 判断颜色分布。
- 二者结合降低误判。

### 5.4 CombinedHash

实现位置：

```text
similarity/CombinedHash.kt
```

结构：

```kotlin
CombinedHash(
    imageHash: Long,
    colorHash: Array<DoubleArray>
)
```

判断相似时：

```text
先计算 imageHash 汉明距离
如果距离极小，直接相似
如果距离中等，再计算 colorHash 距离
```

## 6. 相似阈值

实现位置：

```text
similarity/Threshold.kt
```

### 6.1 普通照片 / 视频 / 录屏

| dHash 汉明距离 | colorHash 距离要求 | 结果 |
| --- | ---: | --- |
| 0..4 | 不需要 | 相似 |
| 4..10 | 0..7 | 相似 |
| 10..17 | 0..5 | 相似 |
| >=18 | 不相似 | 不相似 |

代码中最大阈值是：

```text
maxImageDistanceExclusive = 18
```

因此距离等于 18 不通过。

### 6.2 截图

| dHash 汉明距离 | colorHash 距离要求 | 结果 |
| --- | ---: | --- |
| 0..2 | 不需要 | 相似 |
| 2..10 | 0..5 | 相似 |
| 10..15 | 0..2 | 相似 |
| >=16 | 不相似 | 不相似 |

竞品仅对截图使用更严格阈值。普通照片、视频和录屏共同使用上一节的
`0..4 / <18` 阈值。

### 6.3 录音

当前 Demo 使用基础策略：

```text
duration bucket + size bucket
```

这是早期产品可用方案，用于识别疑似重复录音。

后续可扩展：

```text
Chromaprint
MFCC
波形摘要
静音段特征
峰值特征
```

## 7. 本地数据库设计

实现位置：

```text
database/ScanDatabase.kt
```

### 7.1 media_asset

保存资源元数据。

字段：

```text
id
media_store_id
uri
type
name
width
height
duration
size
created_at
updated_at
bucket
path_hint
fingerprint_status
last_scanned_at
```

唯一约束：

```text
media_store_id + type
```

用途：

- 记录所有已发现资源。
- 标记资源指纹状态。
- 支持后续增量扫描。

### 7.2 fingerprint

保存指纹数据。

字段：

```text
asset_id
image_hash
color_hash
hash_prefix
aspect_bucket
duration_bucket
```

用途：

- 缓存 dHash/colorHash。
- 下次扫描避免重复计算。
- 支持候选桶检索。

### 7.3 similar_group

保存相似组。

字段：

```text
id
type
updated_at
```

### 7.4 similar_group_item

保存相似组成员。

字段：

```text
group_id
asset_id
```

用途：

- 一个 group 包含多个相似资源。
- UI 从这里加载结果。

## 8. 数据库索引

当前索引：

```sql
CREATE INDEX idx_asset_type_status
ON media_asset(type, fingerprint_status);

CREATE INDEX idx_asset_type_duration_size
ON media_asset(type, duration, size);

CREATE INDEX idx_fingerprint_candidate
ON fingerprint(hash_prefix, aspect_bucket, duration_bucket);

CREATE INDEX idx_group_item_asset
ON similar_group_item(asset_id);
```

索引用途：

- 快速查未完成指纹资源。
- 快速查同类型资源。
- 快速按候选桶召回相似候选。
- 快速判断资源所属相似组。

## 9. 候选桶匹配

直接对 10 万资源做两两比较不可行：

```text
100000 * 100000 / 2 = 50 亿次比较
```

因此当前实现使用候选桶：

```text
hashPrefix
aspectBucket
durationBucket
```

### 9.1 hashPrefix

```kotlin
(imageHash ushr 48) & 0xFFFF
```

即使用 dHash 高 16 bit 做粗召回。

查询时不仅查当前 prefix，也查相邻 prefix：

```text
prefix - 1
prefix
prefix + 1
```

### 9.2 aspectBucket

```text
(width / height) * 100
```

用于避免横图和竖图互相比较。

### 9.3 durationBucket

```text
duration / 1000
```

视频/录屏按秒级时长分桶。

### 9.4 精确过滤

候选桶只负责召回，最终仍使用：

```text
dHash 汉明距离
colorHash 距离
媒体类型阈值
```

## 10. 相似组维护

实现位置：

```text
database/ScanDatabase.kt
```

核心方法：

```kotlin
linkSimilarAssets(type, firstAssetId, secondAssetId)
```

逻辑：

```text
如果两个资源都没有 group
-> 创建新 group

如果其中一个已有 group
-> 把另一个加入该 group

如果两个资源属于不同 group
-> 合并两个 group
```

这相当于数据库层面的相似簇合并。

## 11. 扫描进度与实时展示

扫描进度模型：

```text
model/ScanProgress.kt
model/ScanStage.kt
```

阶段：

```text
IDLE
ENUMERATING
FINGERPRINTING
MATCHING
COMPLETED
FAILED
```

UI 交互：

```text
打开页面
-> loadCachedGroups()
-> 立即展示已有结果
-> 点击扫描
-> 每批处理完成后回调 ScanProgress
-> UI 更新进度文字和相似组列表
```

当前实现中：

```kotlin
scanner.scan { progress -> ... }
```

每批都会刷新：

```text
已扫描资源数
已发现相似组数
当前阶段文案
缓存相似组列表
```

## 12. 10 万资源支持策略

当前实现具备支持 10 万资源的关键基础：

### 12.1 不一次性加载所有资源

通过 Cursor 分批读取：

```text
batchSize = 500
```

内存只持有当前批次。

### 12.2 不一次性两两比较

通过候选桶查询：

```text
hashPrefix + aspectBucket + durationBucket
```

避免全量 O(n²)。

### 12.3 指纹落库

资源 hash 写入：

```text
fingerprint
```

下次扫描可以复用。

### 12.4 结果持续产出

每批完成后更新：

```text
similar_group
similar_group_item
```

UI 持续刷新。

### 12.5 可恢复

`fingerprint_status` 为后续断点续扫提供基础：

```text
PENDING
DONE
FAILED
SKIPPED
```

当前 Demo 已建立字段，后续可以继续增强为：

```text
只处理 fingerprint_status != DONE 的资源
```

## 13. 当前 Demo 与正式产品的差异

当前 Demo 已实现产品级架构骨架，但仍有一些可继续增强点。

### 13.1 后台执行

当前扫描仍由 Activity 触发的单线程 executor 执行。

正式产品建议：

```text
WorkManager
+ Foreground Service
+ 前台通知进度
```

这样 App 退后台后仍可继续扫描。

### 13.2 并发控制

当前使用单线程，简单稳定。

正式产品可使用：

```text
2 - 4 个 fingerprint worker
```

并根据：

```text
电量
温度
是否充电
是否前台
```

动态调整并发。

### 13.3 增量扫描

当前已具备落库能力，但增量策略还可以继续完善：

```text
根据 generation_added
generation_modified
date_modified
size
duration
```

判断资源是否变化。

### 13.4 候选召回

当前候选桶使用单一高 16 bit prefix。

更强方案：

```text
多段 LSH
BK-tree
Hamming Ball Query
```

用于提高召回率。

### 13.5 音频相似

当前音频只是基础疑似重复。

正式产品建议接入：

```text
Chromaprint
MFCC
音频波形摘要
```

## 14. 推荐后续演进

建议按优先级演进：

1. 接入 WorkManager + Foreground Service。
2. 增加真正的断点续扫查询，只处理未完成资源。
3. 增加删除同步：MediaStore 中不存在的资源从 DB 删除。
4. 增加 Paging，结果页按组分页加载。
5. 增加最佳保留项评分。
6. 增加多段 LSH，提高大图库召回率。
7. 增加音频 fingerprint。

## 15. 总结

该 Demo 当前实现的产品级核心方案是：

```text
权限处理
+ MediaStore 分批枚举
+ SQLite 元数据缓存
+ 缩略图/降采样加载
+ dHash + colorHash
+ 指纹落库
+ 候选桶召回
+ 精确阈值过滤
+ 相似组落库
+ UI 实时刷新
```

它不再是一次性扫描少量资源的 Demo，而是具备大规模媒体库扫描所需的关键工程结构。对于 10 万资源，关键不是一次性处理，而是批处理、缓存、候选召回和持续展示；当前实现已经按这个方向组织代码。
