# SimilarScanDemo v14 与 Cleanup 竞品核心扫描对齐审计

> 本文记录 v14 审计时点。v15 已完成音频代码移除、视频路径失败行为、固定间隔末帧、
> API 30+ 抽帧和图片 DATA fallback 对齐，并保留 API 23-29 产品兼容。最新结论见
> `docs/v15_alignment_changes_and_decisions.md`。

## 1. 结论

当前 Demo **没有与竞品完全一致**。

已经有反编译代码直接证据、且 Demo 实现基本一致的部分包括：

- MediaStore 图片、视频枚举范围和 `_size > 0` 条件。
- 截图、录屏文件名分类关键词。
- 图片指纹使用的 `colorHash`。
- 照片、截图、视频、录屏的相似阈值。
- 图片按创建时间升序、以最早资源为锚点的直接相似分组。
- 图片和截图的 `duplicateReference` 字段。
- 视频 7/13 帧分支、9x8 帧、跨帧比较和至少两次命中。

尚未完全一致、并且可能继续造成扫描数量差异的核心部分：

1. Demo 使用 Kotlin `DHash.kt`，竞品使用 `libdhash.so`。
2. 视频固定间隔采样的边界实现不同。
3. 视频文件路径不可用时，Demo 会回退 URI，竞品会返回无效指纹。
4. Android 30 以下的视频抽帧 API 和 Bitmap 配置不同。
5. 图片缩略图最终文件路径 fallback 不完整。
6. 同创建时间资源的分组顺序不能保证与竞品一致。
7. Demo 的增量重建策略与竞品的持久化文件夹合并策略不同。
8. Android 14 部分媒体授权是 Demo 扩展，不是竞品原方案。
9. Chat Photos 对 `facebook` 的识别比竞品更宽。
10. 音频代码未进入主扫描，且竞品相似扫描本身也不处理音频。

因此目前更准确的描述是：

> Demo 已复现竞品可见的分类模型、阈值和分组框架，但指纹输出及若干边界行为仍未
> 完成逐资源等价验证，不能声明扫描结果与竞品完全一致。

---

## 2. 审计依据

### Demo

- `scanner/MediaStoreRepository.kt`
- `scanner/MediaClassifier.kt`
- `scanner/MediaBitmapLoader.kt`
- `scanner/SimilarMediaScanner.kt`
- `similarity/KotlinDHash.kt`
- `similarity/HashCalculator.kt`
- `similarity/Threshold.kt`
- `similarity/VideoFingerprintCalculator.kt`
- `similarity/VideoFingerprint.kt`
- `database/ScanDatabase.kt`
- `scanner/ProductCategoryBuilder.kt`

### 竞品

- `Cleanup/sources/qh/k.java`
- `Cleanup/sources/qh/a.java`
- `Cleanup/sources/qh/b.java`
- `Cleanup/sources/qh/c.java`
- `Cleanup/sources/ph/b.java`
- `Cleanup/sources/ph/h.java`
- `Cleanup/sources/ph/e.java`
- `Cleanup/sources/ph/g.java`
- `Cleanup/sources/uh/a.java`
- `Cleanup/sources/uh/b.java`
- `Cleanup/sources/a4/a.java`
- `Cleanup/sources/a4/b.java`
- `Cleanup/sources/bn/k.java`
- `Cleanup/sources/rh/r.java`
- `Cleanup/sources/a/a.java`

对 JADX 无法正确还原的方法，同时使用 Apktool smali 复核。

---

## 3. Demo 当前完整方案

### 3.1 资源获取

主扫描只枚举：

```text
MediaStore.Images.Media.EXTERNAL_CONTENT_URI
MediaStore.Video.Media.EXTERNAL_CONTENT_URI
```

查询条件：

```text
_size > 0
ORDER BY date_added DESC
```

Android 11 及以上的增量扫描增加：

```text
generation_modified > 上次游标
```

每 500 条组成一批，逐批写入 SQLite，不一次性把全部媒体和 Bitmap 放入内存。

图片和视频会保存：

- MediaStore ID
- URI
- 文件名
- 宽高
- 文件大小
- 拍摄时间或添加时间
- 修改时间
- 收藏状态
- generation
- bucket、relative path、MIME 等扩展信息

创建时间规则：

```text
date_taken > 0 ? date_taken : date_added * 1000
```

### 3.2 媒体分类

基础分类来自 MediaStore 集合：

- Images -> PHOTO 或 SCREENSHOT
- Video -> VIDEO 或 SCREEN_RECORDING

截图只根据 `DISPLAY_NAME` 判断：

```text
screenshot
screen_shot
screen-shot
前缀 screenshot_
前缀 screen_
```

录屏只根据 `DISPLAY_NAME` 判断：

```text
screen_recording
screen-recording
screenrecord
screen_record
screen-record
前缀 screen_recording_
前缀 screenrecord_
前缀 screen_record_
前缀 recording_
recording
capture
mirror
cast
```

该关键词集合与竞品一致。

### 3.3 图片指纹输入

图片和截图优先执行：

```text
MediaStore.Images URI + media id
-> loadThumbnail(1024 x 1024)
```

失败后使用标准图片 URI 解码，并通过 `inSampleSize` 控制尺寸。

生成两部分指纹：

```text
imageHash = Kotlin dHash
colorHash = RGB 8 x 3 直方图
```

### 3.4 Kotlin dHash

当前仅保留项目根目录 `DHash.kt` 对应实现：

```text
ARGB_8888 Bitmap
-> 在原图上取 9 x 8 网格
-> 双线性插值灰度
-> 每行比较相邻 9 个采样值
-> 从左到右、从上到下写入 64 位 Long
```

灰度公式：

```text
0.299R + 0.587G + 0.114B
```

宽或高无效、尺寸超过 16384 时返回 `-1L`。

### 3.5 colorHash

流程：

```text
ARGB_8888 Bitmap
-> 分别统计 R/G/B 的 0..255 直方图
-> 每 32 个值合成一桶
-> 得到 Double[8][3]
-> 每桶除以 pixelCount / 16
```

颜色距离：

```text
所有 8 x 3 桶的绝对差求和
-> 转 Long，直接截断小数
```

该部分与竞品一致。

### 3.6 图片和截图相似判断

先计算：

```text
imageDistance = bitCount(hash1 XOR hash2)
```

普通照片：

```text
0..4                         -> 直接相似
4..10 且 colorDistance 0..7 -> 相似
10..17 且 colorDistance 0..5
>= 18                        -> 不相似
```

截图：

```text
0..2                         -> 直接相似
2..10 且 colorDistance 0..5 -> 相似
10..15 且 colorDistance 0..2
>= 16                        -> 不相似
```

代码中的区间包含右端点，但外层先排除 `>= 18` 或 `>= 16`，最终有效边界如上。

### 3.7 相同图片

竞品 `duplicateReference` 格式为：

```text
媒体类型_宽_高_imageHash_edited_文件大小
```

其中：

- 只对 IMAGE、SCREENSHOT 生成。
- `edited` 在当前竞品构造流程中固定为 `false`。
- colorHash 和 SHA-256 不参与 duplicateReference。

Demo SQL 使用相同字段等值查找：

- 类型
- 宽
- 高
- imageHash
- edited
- 文件大小

Demo 额外计算 SHA-256，仅作为诊断数据，不参与相同图片分类。

相同图片优先于相似图片，同一资源不会同时计入两个分类。

### 3.8 视频指纹

视频和录屏不使用图片缩略图指纹，而是：

```text
读取真实文件路径
-> MediaMetadataRetriever
-> 读取 METADATA_KEY_DURATION
-> 计算采样时间点
-> 时间点乘 1000 转为微秒
-> getScaledFrameAtTime(timeUs, option=2, 9, 8)
-> 每帧生成 dHash + colorHash
```

竞品参数：

```text
minInterval = 2
maxInterval = 10
frameCount = 7
maxFrameCount = 13
minMatchedFrameCount = 2
```

通常毫秒级视频时长会进入 13 帧等距分支。

### 3.9 视频相似判断

视频和录屏使用普通照片相同的单帧阈值。

两段视频比较时：

```text
左侧所有帧 x 右侧所有帧
```

不按帧序号对齐，也不按时间差对齐。

需要命中：

```text
max(min(2, 左帧数, 右帧数), 1)
```

竞品双重循环没有限制同一帧只能贡献一次命中，Demo 保持该行为。

竞品上层分别处理 VIDEO 和 SCREEN_RECORDING；虽然底层比较器允许两种视频类型，
正常扫描路径不会把普通视频和录屏放在同一个候选集合中。Demo 按类型分开是正确的。

### 3.10 候选召回

Demo 图片使用 BK-Tree 按 dHash 汉明距离召回：

```text
照片最大召回距离 17
截图最大召回距离 15
```

视频最终重建时，每个有效帧都加入 BK-Tree。

BK-Tree 只是性能优化，最终仍执行完整的 `CombinedHash` 或视频跨帧判断。
在 Hash 和类型相同的前提下，该优化不会改变理论结果。

### 3.11 最终分组

扫描完成后，Demo 对本轮涉及的媒体类型重建相似组：

```text
按 created_at ASC 排序
-> 取最早资源作为锚点
-> 收集所有与锚点直接相似的剩余资源
-> 形成一组
-> 从剩余集合移除整组
-> 继续下一个锚点
```

这不是相似关系的连通分量：

```text
A≈B，B≈C，A≉C
```

不会因为 B 把 A、B、C 合并成一组。

该核心分组函数与竞品 `a.a.E()` 一致。

### 3.12 Other 分类

Demo 从数据库动态计算：

- 未进入 Similar 或 Duplicate 的照片 -> Other Photos
- 未进入 Similar 或 Duplicate 的截图 -> Other Screenshots
- 未进入 Similar 的视频 -> Other Videos
- 未进入 Similar 的录屏 -> Other Screen Recordings

Chat Photos 从 Other Photos 中二次拆分。

---

## 4. 已确认一致的部分

| 模块 | 对齐状态 | 说明 |
| --- | --- | --- |
| 图片/视频 MediaStore 集合 | 一致 | 都查询 external Images、Video |
| `_size > 0` | 一致 | 零字节资源不进入扫描 |
| 初始排序 | 一致 | `date_added DESC` |
| 截图关键词 | 一致 | 已用反编译代码逐项核对 |
| 录屏关键词 | 一致 | 已用 `android.support...a.J()` 核对 |
| 创建时间选择 | 一致 | dateTaken 无效时使用 dateAdded |
| edited | 一致 | 当前流程固定 false |
| potentiallySimilarIdentifier | 一致 | `宽x高_文件大小` |
| colorHash | 一致 | 8x3、32 值一桶、除以像素数/16 |
| colorDistance | 一致 | 绝对差求和后转 Long |
| 照片阈值 | 一致 | qh.a |
| 截图阈值 | 一致 | qh.b |
| 视频/录屏阈值 | 一致 | qh.c，与照片参数相同 |
| duplicateReference 字段 | 一致 | 类型、宽高、imageHash、false、大小 |
| 视频常规 7/13 分支 | 基本一致 | 主分支一致，固定间隔边界有差异 |
| 视频帧尺寸 | 一致 | 9x8 |
| 视频帧选项 | API 30+ 一致 | option=2、ARGB_8888 |
| 视频最少命中数 | 一致 | 默认 2，不足两帧时至少 1 |
| 视频跨帧循环 | 一致 | 全帧笛卡尔比较 |
| 锚点分组模型 | 一致 | 最早资源锚点、只看直接相似 |
| Duplicate/Similar 互斥 | 目标一致 | Demo 显式保证互斥 |

---

## 5. 尚未完全一致的部分

### P0：Kotlin dHash 未证明与竞品 native 等价

竞品实际调用：

```text
libdhash.so
Java_com_storage_androidcleaner_flutter_services_scan_service_DHash_fromBitmap
```

Demo 按需求已删除 native so，只保留根目录 `DHash.kt` 的 Kotlin 复现。

当前只能确认：

- 输出格式都是 64 位 Long。
- 目标都是 9x8 dHash。
- Kotlin 实现看起来符合对 native 的逆向推断。

尚未确认：

- native 的缩放坐标是否完全相同。
- native 是否使用相同双线性插值。
- 灰度浮点精度和取整时机是否相同。
- 位写入顺序是否在所有样本上相同。
- 超大图、非 ARGB Bitmap、透明像素的行为是否相同。

Demo 还增加了 `MAX_DIMENSION = 16384`，竞品 Java 层没有该限制的直接证据。

**影响：最高。** imageHash 的一个 bit 不同，都可能改变相似阈值、Duplicate 和组边界。

**对齐方式：**

1. 保留竞品 APK 作为离线验证器，而不是重新把 so 放入正式 Demo。
2. 对同一批原始 Bitmap 同时导出 native Hash 和 Kotlin Hash。
3. 至少覆盖照片、截图、9x8 视频帧、透明 PNG、旋转图和超大图。
4. 只有逐项一致后才能声明 Hash 完全对齐。

### P0：当前 CSV 不是 v14 结果

目录中的 CSV 修改时间是：

```text
2026-06-23 17:01
```

它们早于 v14 删除 native、切换 Kotlin dHash 的最终代码，不能代表当前 Demo。

该 CSV 显示：

| 分类 | Demo CSV | 竞品 |
| --- | ---: | ---: |
| 总资源 | 214 | 214 |
| 相同截图 | 4 / 1 组 | 4 |
| 相似照片 | 3 / 1 组 | 3 |
| 相似截图 | 116 / 25 组 | 114 / 24 组 |
| 其他截图 | 55 | 57 |
| 相似视频 | 19 / 5 组 | 31 |
| 其他视频 | 12 | 0 |

214 条资源都有指纹且没有 FAILED，但视频结果仍明显不同。

必须用 v14 全量重扫后重新导出，才能判断新 Kotlin Hash 和多帧实现的真实结果。

### P1：视频固定间隔采样不完全一致

竞品固定间隔函数：

```text
从 0 开始，以 max(1, interval) 递增
只要 time < duration 就加入
最后强制追加 duration
```

Demo 当前实现：

```text
time <= duration
最多加入 7 或 13 个
不保证最后追加 duration
```

普通秒级视频通常进入 13 帧等距分支，因此当前测试中可能不触发；但短视频和异常
duration 会产生不同帧数和时间点。

**建议：** 完整复制竞品 `uh.b.b(duration, interval)`，不要传入 maxCount 截断。

### P1：视频路径失败策略不同

竞品：

```text
查询 DATA 真实路径
-> 路径为空、文件不存在或不可读
-> 返回一个无效帧指纹
```

Demo：

```text
查询 DATA
-> 失败时 setDataSource(context, contentUri)
```

Demo 的兼容回退更适合分区存储，但会使竞品扫描失败的视频在 Demo 中成功生成指纹，
从而改变 Similar Videos 和 Other Videos。

**产品选择：**

- 要求结果严格复现：删除 URI 回退。
- 要求现代 Android 可用性：保留回退，但必须承认不是完全一致。

建议保留产品兼容路径，同时在指纹表增加 `source=FILE_PATH/CONTENT_URI/FAILED`，
导出时可以识别由回退造成的差异。

### P1：API 30 以下抽帧不同

竞品直接使用带 `BitmapParams` 的五参数 `getScaledFrameAtTime`：

```text
preferredConfig = ARGB_8888
option = 2
width = 9
height = 8
```

Demo：

- API 30+：一致。
- API 27-29：使用不带 BitmapParams 的重载。
- API 23-26：先取原帧，再由 Android Bitmap 缩放到 9x8。

在 API 30 以下不能声明帧像素完全一致。

### P1：图片最终 fallback 少一层

竞品图片加载顺序：

```text
Images URI loadThumbnail
-> Images URI input stream + inSampleSize
-> DATA 文件路径 + BitmapFactory.decodeFile
```

Demo 图片加载：

```text
Images URI loadThumbnail
-> 实际 URI input stream + inSampleSize
```

Demo 没有图片 DATA 路径的最终 `decodeFile` fallback。

大多数本地图片不会受影响；但 ContentResolver 解码失败而文件路径可读时，竞品有指纹，
Demo 会标记 FAILED。

### P1：相同创建时间的分组顺序不完全一致

竞品：

```text
MediaStore date_added DESC 原列表
-> stable sort by createdAt ASC
```

创建时间相同时保留原列表顺序。

Demo：

```text
ORDER BY created_at ASC, media_store_id DESC
```

MediaStore ID 通常和添加顺序相关，但不是正式等价条件。锚点不同可能改变边界组。

**建议：** 数据库保存枚举序号或 `date_added`，重建时使用
`created_at ASC, date_added DESC, enumeration_order ASC`。

### P2：增量分组架构不同

竞品持久化旧 folder，并使用：

- potentiallySimilarIdentifier
- 已处理资源集合
- 文件夹类型
- 旧 folder 的最大 identifier

把新增资源合并进已有结果。

Demo 对发生变化的媒体类型读取数据库全部有效指纹，并重新执行确定性分组。

Demo 的方案更容易保证最终一致性，但增量时的组 ID、旧组边界和更新时间不可能与竞品完全相同。

如果产品只要求“全量最终结果一致”，该差异可以保留。
如果要求“每次增量后的 folder 结构也一致”，需要复现竞品 folder merge 状态机。

### P2：部分媒体授权不同

竞品 Manifest 只声明：

- READ_MEDIA_IMAGES
- READ_MEDIA_VIDEO
- READ_EXTERNAL_STORAGE（maxSdk 32）

Demo 额外声明：

- READ_MEDIA_VISUAL_USER_SELECTED

Android 14+ 用户选择部分媒体时，Demo 会扫描可见子集，并避免把未授权资源当作删除。

这是合理的产品级增强，但扫描资源集合可能与竞品不同。对比测试必须确认两边都是完整授权。

### P2：Chat Photos 有一个额外关键词

竞品聊天来源只有：

- whatsapp
- telegram
- snapchat
- messenger

Demo 将 `facebook` 也归为 MESSENGER。

这只影响 Chat Photos 与 Other 的拆分，不影响相似组。

### P3：音频不是当前相似扫描能力

Demo 存在 Audio 查询和“时长桶 + 大小桶”的疑似重复代码，但：

- Manifest 未声明 READ_MEDIA_AUDIO。
- 权限请求不包含 READ_MEDIA_AUDIO。
- `forEachMediaBatch()` 不调用 `forEachAudioBatch()`。
- ProductCategoryType 没有 Audio 分类。
- 竞品核心相似扫描枚举也只有四类：IMAGE、VIDEO、SCREENSHOT、SCREEN_RECORDING。

因此当前产品说明不应宣称音频已经参与相似扫描。

---

## 6. 分类数量守恒

Demo 当前数据库设计已经显式保证：

```text
Duplicate 与 Similar 互斥
Similar 与 Other 互斥
每种媒体类型独立分组
```

正确的守恒关系为：

```text
照片总数 = Similar Photos + Duplicate Photos + Other Photos + Chat Photos
截图总数 = Similar Screenshots + Duplicate Screenshots + Other Screenshots
视频总数 = Similar Videos + Other Videos
录屏总数 = Similar Screen Recordings + Other Screen Recordings
```

注意：表中的 Similar 数量应按去重后的资源 ID 统计，不应把每个组的展示卡片数量或
重复出现的资源直接相加。

当前旧 CSV 已满足：

```text
8 PHOTO = 3 Similar + 5 Other
175 SCREENSHOT = 4 Duplicate + 116 Similar + 55 Other
31 VIDEO = 19 Similar + 12 Other
总计 = 214
```

旧 CSV 数量已守恒，但与竞品组边界仍不同。

---

## 7. 建议对齐顺序

### 第一阶段：建立可证明的指纹对照

优先级最高，不先改阈值。

导出每个资源：

```text
mediaStoreId
kind
name
bitmapSource
bitmapWidth
bitmapHeight
imageHash
colorHash
fingerprintStatus
```

视频额外导出：

```text
duration
dataSourceType
frameIndex
sampleTimeMs
sampleTimeUs
frameValid
frameImageHash
frameColorHash
```

把 Kotlin 与竞品逐资源比较。先定位第一处不同，不用总数反推算法。

### 第二阶段：修正明确的代码差异

1. 视频 fixedInterval 完全复制竞品。
2. 图片增加 DATA 文件路径 decodeFile fallback。
3. 删除 Chat Photos 的 `facebook` 扩展，或标记为产品差异。
4. 明确是否保留视频 Content URI 回退。
5. 保存竞品稳定排序所需的 dateAdded 和枚举顺序。

### 第三阶段：重新全量验证

使用 v14 或后续版本：

1. 清除 Demo 数据。
2. 确认完整图片和视频权限。
3. 确认 MediaStore 总数与竞品相同。
4. 等扫描 COMPLETED 后导出四张表和逐帧诊断表。
5. 比较每个组的资源 ID 集合，而不仅比较组数。

### 第四阶段：再决定是否复现竞品增量 folder 状态机

如果全量结果已一致，但增量扫描后的 folder 边界不同，再处理竞品持久化 folder 合并。
在此之前不建议继续调整相似阈值。

---

## 8. 最终判断

### 可以称为已对齐

- 媒体基础枚举。
- 截图和录屏分类关键词。
- colorHash。
- 图片、截图、视频、录屏相似阈值。
- duplicateReference 字段模型。
- 视频主抽帧结构和跨帧命中规则。
- 锚点直接相似分组模型。

### 不能称为完全对齐

- dHash 最终输出。
- 视频所有边界时间点。
- 文件不可读时的行为。
- API 30 以下帧 Bitmap。
- 同时间资源的锚点顺序。
- 竞品增量 folder 合并状态。
- 最新代码的真机结果。

当前最大的不确定性不是阈值，而是：

```text
同一 Bitmap -> Kotlin imageHash 是否与竞品 native imageHash 完全相同
```

在该问题完成逐项验证前，即使其他代码全部相同，Demo 与竞品的相似、相同和分组结果
仍可能持续存在差异。
