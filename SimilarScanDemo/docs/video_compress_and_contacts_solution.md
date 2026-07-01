# 视频压缩与联系人整理实现方案

> 状态：当前 Demo 实现说明。  
> 范围：新增视频压缩 SDK、Demo 压缩业务页、Demo 联系人整理页。  
> 约束：现有相似扫描 SDK 不参与本次改造，不调整扫描、识别、分组、阈值、数据库和已有 API。

## 1. 模块边界

### 1.1 保持不变的模块

`similar-scan-core` 保持原有职责：

- 媒体资源扫描。
- 图片/视频指纹计算。
- 相似/相同识别。
- 分组与产品分类构建。
- 相似扫描结果读取和预览加载。

本次没有修改相似识别核心逻辑。

### 1.2 新增视频压缩 SDK

新增模块：`video-compress-core`。

包名：`com.clean.videocompress.*`。

SDK 职责：

- 检查视频读取权限状态。
- 读取系统视频媒体资源。
- 根据配置生成视频压缩分桶。
- 提供三档可配置压缩选项。
- 执行视频压缩。
- 回调开始、进度、成功、失败、取消。
- 将压缩后视频保存到系统 `MediaStore.Video`。

SDK 不负责：

- 免费次数。
- PRO 状态。
- 订阅弹窗。
- 删除原视频。
- 页面展示。
- 业务埋点。

### 1.3 Demo 业务职责

Demo 新增职责：

- 底部三入口：Photo Swiping、Compress、Contacts。
- 视频压缩首页、分桶详情、单视频压缩页。
- 每日免费压缩 2 次额度。
- 超限后进入订阅页。
- 压缩完成后让用户选择保留或删除原视频。
- 联系人读取、分类和展示。

## 2. 视频压缩 SDK 设计

### 2.1 对外入口

```kotlin
val client = VideoCompressSdk.create(context)
```

核心接口：

```kotlin
interface VideoCompressClient {
    fun loadVideos(): List<CompressVideoAsset>
    fun buildBuckets(videos: List<CompressVideoAsset>): List<VideoBucket>
    fun compress(request: VideoCompressRequest, observer: VideoCompressObserver): VideoCompressTask
}
```

压缩回调：

```kotlin
interface VideoCompressObserver {
    fun onStart(asset: CompressVideoAsset)
    fun onProgress(progress: VideoCompressProgress)
    fun onSuccess(result: VideoCompressResult)
    fun onFailure(error: VideoCompressError)
    fun onCancelled(assetId: Long)
}
```

## 3. 权限方案

SDK 提供 `VideoCompressPermissionChecker`：

- `NONE`：没有视频读取权限。
- `PARTIAL_VIDEO`：Android 14+ 只授权了部分视频。
- `FULL_VIDEO`：Android 13+ 完整视频权限。
- `LEGACY_FULL`：Android 12 及以下通过 `READ_EXTERNAL_STORAGE` 获得权限。

权限申请由 Demo 的 `VideoPermissionHelper` 触发。

这样设计可以保证 SDK 可被其他产品复用，不强绑定某个页面或权限弹窗流程。

## 4. 视频读取方案

视频资源来自 `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`。

读取字段：

- `_id`
- `displayName`
- `size`
- `duration`
- `width`
- `height`
- `dateAdded`
- `dateModified`
- `bitrate`

排序：

```text
DATE_ADDED DESC
```

筛选：

```text
SIZE > 0
```

码率优先通过 `MediaMetadataRetriever.METADATA_KEY_BITRATE` 获取；如果为空，压缩时会基于文件大小和时长估算。

## 5. 分桶方案

SDK 默认提供三类分桶：

- `Extreme Space`：预计可节省空间较大。
- `Moderate Space`：中等节省空间。
- `Light Space`：轻量优化。

默认规则在 `VideoCompressBucketRule.defaults()` 中配置，后续可以调整阈值、标题、描述和颜色。

估算逻辑：

```text
estimatedSaving = videoSize * mediumCompressionRate
```

当前默认以 Medium 档作为首页预计节省空间的基准。

## 6. 三档压缩配置

默认三档：

| 档位 | key | 默认压缩比例 | 含义 |
| --- | --- | --- | --- |
| Low Quality | low | 70% | 优先节省空间 |
| Medium Quality | medium | 50% | 平衡体积和清晰度 |
| High Quality | high | 30% | 保留更多画质 |

`compressionRatePercent` 表示预计减少的码率比例。

目标码率计算：

```text
sourceBitrate = metadataBitrate ?: fileSize * 8 / duration
targetBitrate = sourceBitrate * (100 - compressionRatePercent) / 100
```

最低码率保护：

```text
targetBitrate >= 350_000
```

## 7. 压缩引擎

### 7.1 默认引擎：Media3 Transformer

默认使用 `androidx.media3:media3-transformer:1.3.1`。

实现类：

```text
com.clean.videocompress.internal.engine.media3.Media3VideoCompressEngine
```

关键点：

- 使用 `Transformer` 执行转码。
- 输出视频 mime type 为 `video/avc`。
- 使用 `DefaultEncoderFactory` 配置目标 `VideoEncoderSettings`。
- 根据用户选择的档位设置目标 bitrate。
- 设置关键帧间隔为 3 秒。
- 通过 `Transformer.getProgress()` 定时查询压缩进度。
- 完成后保存到系统 `MediaStore.Video`。

### 7.2 备用引擎：Native Codec

备用实现类：

```text
com.clean.videocompress.internal.engine.nativecodec.NativeCodecVideoCompressEngine
```

当前代码路径保持独立，便于后续扩展为完整 `MediaCodec + MediaMuxer` 原生转码方案。

当前实现完成：

- 独立任务队列。
- 原生 `MediaExtractor` / `MediaMuxer` 流程。
- 独立进度回调。
- 独立成功/失败/取消处理。
- 结果保存到 `MediaStore.Video`。

后续如果要将备用引擎升级为完整原生压缩，只需要在 `nativecodec` 包内补充解码、缩放、编码流程，不影响 Media3 默认引擎。

## 8. 结果保存方案

压缩输出先写入 app cache：

```text
cacheDir/video_compress/
```

完成后由 `VideoStoreWriter` 保存到系统媒体库：

- `DISPLAY_NAME = compressed_xxx.mp4`
- `MIME_TYPE = video/mp4`
- Android 10+ 写入 `RELATIVE_PATH = Movies`
- Android 10+ 写入前 `IS_PENDING = 1`
- 写入成功后 `IS_PENDING = 0`
- 写入失败会删除已创建的 MediaStore 记录

## 9. Demo 压缩业务

### 9.1 页面

新增页面：

- `VideoCompressActivity`：压缩首页。
- `VideoBucketActivity`：分桶详情。
- `VideoCompressDetailActivity`：单视频压缩页。

### 9.2 免费次数

免费次数由 Demo 的 `VideoCompressionQuotaStore` 管理：

- 每日免费 2 次。
- 按自然日重置。
- 超限后进入 `SubscriptionActivity`。

该逻辑不进入 SDK。

### 9.3 压缩完成后操作

压缩完成后 Demo 弹窗让用户选择：

- `Keep Original`
- `Delete Original`

删除原视频属于业务层操作，后续可继续补充 Android 11+ 系统删除授权确认流程。

## 10. 联系人整理 Demo

联系人模块完全在 Demo 内实现，不做 SDK。

包结构：

```text
com.example.similarscandemo.contacts
com.example.similarscandemo.contacts.model
com.example.similarscandemo.contacts.repository
```

当前支持分类：

- `Duplicate Contacts`：相同电话号码。
- `Same Name`：相同姓名。
- `Incomplete Contacts`：缺少姓名或号码。

权限：

- `READ_CONTACTS`
- `WRITE_CONTACTS`

当前版本先完成读取和分类展示，后续可继续补充合并、删除、冲突确认和操作恢复。

## 11. 编译验证

已执行：

```bash
./gradlew :app:assembleDebug
```

结果：

```text
BUILD SUCCESSFUL
```
