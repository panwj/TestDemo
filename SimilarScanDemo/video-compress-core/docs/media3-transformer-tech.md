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

一次压缩大致分为 6 步：

1. Demo 调用 `VideoCompressClient.compress()`。
2. SDK 根据用户选择的压缩档位生成压缩计划。
3. 如果视频太短、太小、码率太低或预计收益太小，直接返回“不建议压缩”错误。
4. Media3 Transformer 按计划转码到 app cache 临时文件。
5. SDK 校验临时文件是否真的有效且体积有收益。
6. 校验通过后，SDK 写入系统 `MediaStore.Video`，让压缩后的视频出现在系统相册/Movies 中。

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
- 码率，如果列表阶段没有读取到则为 0。

## 5. 三档压缩如何生效

SDK 默认提供三档：

| 档位 | 默认压缩比例 | 产品含义 |
| --- | --- | --- |
| Low Quality | 70% | 体积优先，节省空间最多 |
| Medium Quality | 50% | 体积和画质平衡 |
| High Quality | 30% | 尽量保留画质 |

这里的压缩比例不是简单裁剪文件，而是用于计算目标码率。

计算方式可以理解为：

```text
目标码率 = 原始码率 * (100 - 压缩比例) / 100
```

如果原始码率没有从系统取到，SDK 会用文件大小和视频时长估算：

```text
估算码率 = 文件大小 * 8 / 视频时长
```

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

### 6.2 动态压缩比例

默认档位只是基础比例，SDK 会根据视频实际情况做调整：

- 4K 或高分辨率视频会提高压缩强度。
- 高码率视频会提高压缩强度。
- 低码率视频会降低压缩强度，避免画质明显变差。
- 很短的视频会降低压缩强度。

### 6.3 分辨率降档

大分辨率视频会自动降档：

- 4K / 2K 大视频优先降到 1080p。
- 部分 1080p 以上视频可降到 720p。
- 普通低分辨率视频不做缩放。

实现方式使用 Media3 Effect：

```text
androidx.media3.effect.Presentation
```

### 6.4 输出结果校验

压缩完成后不会立刻保存，而是先校验：

- 临时文件是否存在。
- 临时文件大小是否大于 0。
- 输出文件是否确实比原文件小。

如果校验失败，会返回 `VideoCompressError.ValidationFailed`。

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

- `NotWorthCompressing`：不建议压缩。
- `SourceNotFound`：源文件不存在或无法读取。
- `EngineFailed`：Media3 转码失败。
- `SaveFailed`：写入系统媒体库失败。
- `ValidationFailed`：输出文件校验失败。
- `Cancelled`：用户取消。

## 11. 后续可继续优化

Media3 仍可以继续优化：

- 记录历史压缩结果，用真实数据修正预计节省空间。
- 根据设备性能动态选择更保守或更激进的参数。
- 对 HDR、HEVC、可变帧率视频补充专项策略。

当前已经完成：

- SDK 层压缩任务队列，Media3 与 Native 共用。
- Demo 层长视频前台服务和通知。
- 通知栏压缩进度。
- 通知栏取消入口。

