# Native Codec 视频压缩方案技术说明

> 适用对象：第一次接触本 SDK 的研发同学。  
> 方案定位：备用方案，不作为默认生产方案。  
> 对应实现：`com.clean.videocompress.internal.engine.nativecodec.NativeCodecVideoCompressEngine`。

## 1. 这套方案是什么

Native Codec 方案是直接使用 Android 系统底层媒体 API 做视频压缩。

主要用到：

- `MediaExtractor`：从原视频中读取音视频轨道。
- `MediaCodec`：解码和编码视频。
- `MediaMuxer`：把编码后的视频和音频重新写成 MP4。

它比 Media3 更接近底层，可控性更高，但代码复杂度和机型兼容风险也更高。

## 2. 方案定位

当前 Native 方案定位为：

- Media3 失败时的备用尝试。
- 技术验证方案。
- 特定设备或特定格式的兜底方案。

不建议把 Native 作为默认方案，原因是：

- 各厂商编码器行为不完全一致。
- Surface 解码/编码链路更容易出现机型问题。
- 音视频同步、旋转角度、时间戳等细节需要大量真机验证。
- 后续维护成本高于 Media3。

## 3. 整体流程

Native 一次压缩流程：

1. 使用 `MediaExtractor` 打开原视频。
2. 找到视频轨道。
3. 创建 H.264 编码器。
4. 创建编码器输入 Surface。
5. 创建视频解码器，并把解码输出接到编码器 Surface。
6. 解码器读取原视频帧。
7. 编码器重新编码为 H.264。
8. 使用 `MediaMuxer` 写出 MP4。
9. 如果存在音频轨，音频轨透传写入输出文件。
10. 输出到 app cache 后，再保存到系统 `MediaStore.Video`。

## 4. 与 Media3 的关系

Native 和 Media3 使用同一个 SDK 对外接口：

```kotlin
VideoCompressClient.compress(request, observer)
```

也返回同样的：

- 开始回调。
- 进度回调。
- 成功回调。
- 失败回调。
- 取消回调。
- `VideoCompressResult`。

但内部实现完全独立，不共享引擎代码。

## 5. 三档压缩如何生效

Native 也会读取用户选择的 `VideoCompressOption`。

目标码率通过：

```text
目标码率 = 原始码率 * (100 - 压缩比例) / 100
```

然后配置到 H.264 编码器：

```kotlin
MediaFormat.KEY_BIT_RATE
```

因此 Low / Medium / High 三档会影响 Native 输出体积。

## 6. 当前 Native 输出参数

当前 Native 输出：

- 视频编码：H.264。
- MIME：`video/avc`。
- 颜色输入：Surface。
- 目标码率：由档位计算。
- 帧率：优先使用原视频帧率，没有则使用 30fps。
- 关键帧间隔：3 秒。
- 容器：MP4。
- 音频：原音频轨透传。

## 7. 进度回调

Native 根据编码输出帧的时间戳计算进度：

```text
进度 = 当前输出帧时间 / 视频总时长
```

进度上限在写入阶段前控制在 95% 左右。

保存到系统媒体库时，回调 `SAVING_TO_MEDIASTORE`。

保存完成后，回调 `COMPLETED`。

## 8. 取消处理

调用 `VideoCompressTask.cancel()` 后：

- Native 任务会停止继续读取和写入。
- 临时文件会被删除。
- SDK 会回调 `onCancelled(assetId)`。

## 9. 为什么 Native 不继续做复杂优化

Native 理论上也可以做很多优化，例如：

- 4K 降 1080p。
- 音频重新编码。
- HDR 转 SDR。
- 动态 profile/level。
- 更复杂的码率控制。

但这些优化会显著增加风险：

- 需要 OpenGL 或额外 Surface 渲染链路。
- 需要处理更多厂商编码器差异。
- 容易出现黑屏、花屏、音视频不同步。
- 真机验证成本很高。

因此当前策略是：

- Native 保持基础压缩能力。
- 不继续叠加复杂优化。
- 默认生产优化主要放在 Media3 上。

## 10. 需要重点真机验证的点

如果启用 Native，需要重点验证：

- 普通 H.264 MP4。
- HEVC 视频。
- 竖屏视频和旋转角度。
- 长视频压缩取消。
- 压缩结果能否播放。
- 输出时长是否接近原视频。
- 音频是否正常。
- 文件体积是否真正变小。

