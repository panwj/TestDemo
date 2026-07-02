# Media3 Transformer 视频压缩方案技术说明

> 适用对象：第一次接触本 SDK 的研发同学。  
> 方案定位：默认生产方案。  
> 对应实现：`com.clean.videocompress.internal.engine.media3.Media3VideoCompressEngine`。

## 1. 这套方案解决什么问题

视频压缩的目标是：把用户设备中的大视频转成体积更小的新视频，同时尽量保持可接受的清晰度。

Media3 Transformer 是 Android 官方 Media3 组件提供的视频转码能力。它内部封装了很多底层细节，例如解码、编码、格式转换、进度查询和设备兼容处理，因此比直接手写 `MediaCodec` 更适合做默认生产方案。

## 2. 为什么默认使用 Media3

选择 Media3 的原因：

- 官方维护，长期兼容 Android 新版本。
- 自带 Transformer 转码能力，代码量比原生 `MediaCodec` 少。
- 支持设置目标编码格式和编码参数。
- 支持查询转码进度。
- 支持 fallback，设备编码器不满足要求时可以降级处理。
- 更适合作为稳定生产路径。

## 3. 整体流程

一次压缩大致分为 8 步：

1. Demo 调用 `VideoCompressClient.compress()`。
2. SDK 读取视频轨道 `MediaFormat`，识别 HEVC、HDR、真实码率、帧率、宽高和旋转信息。
3. SDK 根据用户选择的压缩档位生成压缩计划。
4. 如果视频是 HDR、太短、太小、码率太低或预计收益太小，直接返回“不建议压缩”错误。
5. SDK 检查 app cache 和媒体库存储空间是否足够。
6. Media3 Transformer 按计划转码到 app cache 临时文件。
7. SDK 校验临时文件是否真的有效且体积有收益。
8. 校验通过后，SDK 写入系统 `MediaStore.Video`，让压缩后的视频出现在系统相册/Movies 中。

## 4. 输入数据

压缩请求对象是 `VideoCompressRequest`：

- `asset`：要压缩的视频。
- `option`：用户选择的压缩档位。

视频资源对象 `CompressVideoAsset` 包含：

- 视频 URI。
- 文件名。
- 文件大小。
- 时长。
- 宽高。
- 添加时间、修改时间。
- 码率。列表阶段为了速度通常不读取真实码率，真正压缩当前视频时会再读取一次 `MediaFormat`。

## 5. 三档压缩如何生效

SDK 默认提供三档：

| 档位 | 默认压缩比例 | 产品含义 |
| --- | --- | --- |
| Low Quality | 70% | 体积优先，节省空间最多 |
| Medium Quality | 50% | 体积和画质平衡 |
| High Quality | 30% | 尽量保留画质 |

这里的压缩比例不是简单裁剪文件，而是用于计算目标码率。当前策略不是单纯按比例压缩，而是会结合真实 metadata 做保护。

计算方式可以理解为：

```text
基础目标码率 = 原始码率 * (100 - 压缩比例) / 100
```

原始码率优先来自压缩前读取的 `MediaFormat.KEY_BIT_RATE`。如果读取不到，SDK 会用文件大小和视频时长估算：

```text
估算码率 = 文件大小 * 8 / 视频时长
```

最终目标码率还会叠加质量保护：

```text
最终目标码率 = clamp(基础目标码率, 分辨率/帧率质量下限, 原始码率 * 0.92)
```

这样可以避免低分辨率、低码率或高帧率视频被过度压缩，也可以避免目标码率过高导致压缩收益太低。

## 6. Media3 推荐优化

当前 Media3 方案已经加入了生产推荐优化，代码在：

```text
com.clean.videocompress.internal.policy.Media3CompressionPolicy
```

### 6.1 不建议压缩判断

以下视频会被 SDK 拦截，不继续压缩：

- 视频时长小于 3 秒。
- 视频文件小于 3 MB。
- 原始码率已经很低。
- 预计节省空间小于 1 MB。

这样可以避免用户花时间压缩，但结果几乎没有节省空间。

### 6.2 压缩前真实 metadata

SDK 不会在列表页批量打开每一个视频读取详细信息，因为那会拖慢 Compress 首页和分组详情。只有用户真正开始压缩某一个视频时，才会读取当前视频的真实 metadata：

- `MediaFormat.KEY_BIT_RATE`：真实码率。
- `MediaFormat.KEY_FRAME_RATE`：帧率。
- `MediaFormat.KEY_WIDTH / KEY_HEIGHT`：视频轨道宽高。
- `rotation-degrees`：旋转角度，用于得到更准确的展示宽高。
- `MediaFormat.KEY_MIME`：用于识别 HEVC。
- `MediaFormat.KEY_COLOR_TRANSFER / KEY_COLOR_STANDARD`：用于识别 HDR。

这些信息会参与压缩计划生成，但不会影响视频列表展示速度。

### 6.3 动态压缩比例

默认档位只是基础比例，SDK 会根据视频实际情况做调整：

- 4K 或高分辨率视频会提高压缩强度。
- 高码率视频会提高压缩强度。
- 低码率视频会降低压缩强度，避免画质明显变差。
- 高帧率视频会降低压缩强度，保留更多码率。
- HEVC 转 H.264 时会降低压缩强度，因为 H.264 通常需要更高码率才能接近 HEVC 画质。
- 很短的视频会降低压缩强度。

### 6.4 分辨率和帧率质量保护

动态比例之后，SDK 会再计算目标码率下限：

- 4K 来源：最低保护 6 Mbps，即使降到 1080p 也保留更高码率，避免细节损失过大。
- 1080p：最低保护 4.5 Mbps。
- 720p：最低保护 2.5 Mbps。
- 540p：最低保护 1.4 Mbps。
- 低于 540p：最低保护 800 Kbps，避免小视频输出过大。
- 45fps / 60fps 高帧率：提高码率下限。
- HEVC 来源：提高码率下限，降低转 H.264 后的画质损失。

如果质量下限高于“原始码率 * 0.92”，SDK 会以原始码率上限为准，避免输出文件接近或超过原文件大小。

### 6.5 分辨率降档

大分辨率视频会自动降档：

- 4K / 2K 大视频优先降到 1080p。
- 部分 1080p 以上视频可降到 720p。
- 普通低分辨率视频不做缩放。

实现方式使用 Media3 Effect：

```text
androidx.media3.effect.Presentation
```

### 6.6 输出结果校验

压缩完成后不会立刻保存，而是先校验：

- 临时文件是否存在。
- 临时文件大小是否大于 0。
- 输出文件是否确实比原文件小。

如果校验失败，会返回 `VideoCompressError.ValidationFailed`。

### 6.7 磁盘空间预检查

压缩开始前会检查两个位置：

- app cache：用于保存 Media3 输出的临时压缩文件。
- 系统媒体库所在存储：用于保存最终压缩结果。

如果空间不足，SDK 会返回：

```text
VideoCompressError.InsufficientStorage
code = INSUFFICIENT_STORAGE
```

这样可以避免用户等待长时间转码后，最后因为空间不足保存失败。

### 6.8 HEVC 处理策略

HEVC 视频允许进入 Media3 默认压缩流程。当前策略是：

- 压缩前用 `VideoFormatInspector` 读取视频轨道 MIME。
- 如果源视频是 `video/hevc` 或 `video/h265`，记录为 HEVC 来源。
- Media3 输出仍固定为 H.264，也就是 `video/avc`。
- 如果 HEVC 转 H.264 失败，SDK 返回 `VideoCompressError.EngineFailed`，错误信息会明确包含 `HEVC to H.264 compression failed`，方便业务层和测试人员定位。

这样做的原因是：H.264 在系统相册、播放器、分享链路中的兼容性更好；HEVC 输入交给 Media3 处理，比业务层手写解码和编码链路更稳定。

### 6.9 HDR 处理策略

HDR 视频默认不压缩。当前策略是：

- 压缩前读取 `MediaFormat.KEY_COLOR_TRANSFER` 和 `MediaFormat.KEY_COLOR_STANDARD`。
- 如果识别到 ST2084、HLG 或 BT.2020，认为视频属于 HDR 或 HDR 高概率素材。
- SDK 直接返回 `VideoCompressError.NotWorthCompressing`。
- 返回原因是：`HDR video is not compressed by default to avoid color distortion.`

这样做的原因是：普通 H.264 SDR 输出很容易导致 HDR 视频偏色、发灰、过曝或亮度异常。当前 SDK 没有默认做 HDR to SDR 色彩映射，因此先拦截，避免生成用户不可接受的视频。

## 7. Media3 引擎关键参数

当前 Media3 输出配置：

- 视频编码：H.264。
- MIME：`video/avc`。
- 目标码率：由压缩策略动态计算。
- 关键帧间隔：3 秒。
- 允许 fallback：开启。
- 输出路径：app cache 临时文件。
- 最终保存：系统 `MediaStore.Video`。

## 8. 进度回调

Media3 的进度通过 `Transformer.getProgress()` 查询。

SDK 每隔约 300ms 查询一次，并回调：

```kotlin
VideoCompressProgress(
    stage = TRANSCODING,
    percent = ...
)
```

保存到系统相册时，会回调：

```kotlin
stage = SAVING_TO_MEDIASTORE
```

完成后回调：

```kotlin
stage = COMPLETED
percent = 100
```

## 9. 成功结果

成功后返回 `VideoCompressResult`：

- `sourceAsset`：原视频。
- `outputUri`：压缩后视频的系统 URI。
- `outputSizeBytes`：压缩后大小。
- `savedBytes`：节省空间。
- `elapsedMs`：耗时。

压缩后视频会出现在系统相册/Movies 中。

## 10. 失败结果

常见失败：

- `PERMISSION_DENIED`：缺少读取视频或保存结果所需权限。
- `NOT_WORTH_COMPRESSING`：不建议压缩。
- `INSUFFICIENT_STORAGE`：临时目录或媒体库存储空间不足。
- `SOURCE_NOT_FOUND`：源文件不存在或无法读取。
- `ENGINE_FAILED`：Media3 转码失败。
- `SAVE_FAILED`：写入系统媒体库失败。
- `VALIDATION_FAILED`：输出文件校验失败。
- `CANCELLED`：用户取消。
- `SDK_CLOSED`：client 已释放后仍被调用。

## 11. 长视频前台服务

SDK 提供单任务压缩、队列压缩和进度回调，长视频压缩建议由业务层放到前台服务中执行。

队列是单任务顺序队列：

- 同一时间只运行一个视频压缩任务。
- SDK 不提供并发压缩入口。
- 已经一次性选择好的批量任务，可以直接使用 `compressQueue()`。
- 如果业务需要“压缩中继续追加任务”，建议业务层在 `ForegroundService` 中维护可追加队列，并逐个调用 SDK `compress()`。
- 这样可以避免多个任务同时抢占硬件编码器，降低黑屏、编码器初始化失败、内存抖动和系统杀进程风险。

Demo 中已经接入前台服务：

```text
com.example.similarscandemo.compress.VideoCompressForegroundService
```

该服务会：

- 启动前台通知，降低切后台后被系统中断的概率。
- 维护一个可追加顺序队列：如果当前已有视频正在压缩，后续选择的视频会进入等待队列。
- 每次只调用 SDK 单任务 `compress()`，当前任务结束后再启动下一条。
- 将 Media3 进度同步到通知栏和页面。
- 通知栏进度条表示整个队列总进度。
- 通知栏文案中的 `Current` 表示当前单个视频进度，`Queue` 表示整个队列总进度。
- 失败时把可读错误原因回传给页面，例如 HEVC 转码失败、HDR 被拦截、输出校验失败。
- 支持通知栏取消。

## 12. 后续可继续优化

Media3 后续仍可以继续优化：

- 对可变帧率视频补充专项策略。

当前已经完成：

- SDK 层压缩任务队列，Media3 与 Native 共用。
- SDK 层磁盘空间预检查。
- SDK 层稳定错误码。
- SDK 层 client 释放接口。
- Demo 层长视频前台服务和通知。
- Demo 层前台服务可追加压缩队列。
- 通知栏总队列进度。
- 通知栏当前单任务进度文案。
- 通知栏取消入口。
- HEVC 输入允许通过 Media3 转成 H.264 输出。
- HDR 输入默认拦截，不做压缩。
