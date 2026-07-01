# 视频压缩 SDK 集成说明与双方案差异

> 适用对象：准备接入 `video-compress-core` 的业务方和研发同学。  
> 当前推荐：默认使用 `MEDIA3_TRANSFORMER`，Native 只作为备用。

## 1. SDK 能力范围

`video-compress-core` 提供：

- 视频资源读取。
- 视频压缩分桶。
- 三档压缩配置。
- 视频压缩执行。
- 进度回调。
- 成功/失败/取消回调。
- 写入系统 `MediaStore.Video`。
- 权限状态检查工具。

SDK 不负责：

- 权限弹窗触发。
- 免费次数。
- PRO 订阅。
- 删除原视频。
- 页面 UI。
- 业务埋点。
- 前台服务通知样式。

这些由接入方业务层处理。

## 2. Gradle 接入

项目内模块接入：

```kotlin
implementation(project(":video-compress-core"))
```

SDK 内部依赖：

```kotlin
implementation("androidx.media3:media3-transformer:1.3.1")
implementation("androidx.media3:media3-common:1.3.1")
implementation("androidx.media3:media3-effect:1.3.1")
```

## 3. 权限接入

Manifest 至少需要：

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />
```

SDK 只检查权限：

```kotlin
val accessLevel = VideoCompressPermissionChecker.accessLevel(context)
```

返回：

- `NONE`
- `PARTIAL_VIDEO`
- `FULL_VIDEO`
- `LEGACY_FULL`

如果没有权限，业务层自己调用系统权限申请。

## 4. 创建 SDK Client

默认创建方式：

```kotlin
val client = VideoCompressSdk.create(context)
```

默认使用：

```kotlin
VideoCompressEngineType.MEDIA3_TRANSFORMER
```

切换 Native：

```kotlin
val client = VideoCompressSdk.create(
    context,
    VideoCompressConfig(
        engineType = VideoCompressEngineType.NATIVE_CODEC
    )
)
```

## 5. 读取视频和构建分桶

```kotlin
val videos = client.loadVideos()
val buckets = client.buildBuckets(videos)
```

默认分桶：

- `Extreme Space`
- `Moderate Space`
- `Light Space`

分桶规则可以通过 `VideoCompressConfig.bucketRules` 调整。

## 6. 配置三档压缩

默认三档：

- Low Quality：节省空间最多。
- Medium Quality：平衡。
- High Quality：保留更多画质。

自定义：

```kotlin
VideoCompressConfig(
    options = listOf(
        VideoCompressOption("low", "Low", "Save more space", 70),
        VideoCompressOption("medium", "Medium", "Balanced", 50),
        VideoCompressOption("high", "High", "Better quality", 30)
    )
)
```

## 7. 执行压缩

### 7.1 单个任务

```kotlin
val task = client.compress(
    VideoCompressRequest(asset, option),
    object : VideoCompressObserver {
        override fun onStart(asset: CompressVideoAsset) {}
        override fun onProgress(progress: VideoCompressProgress) {}
        override fun onSuccess(result: VideoCompressResult) {}
        override fun onFailure(error: VideoCompressError) {}
        override fun onCancelled(assetId: Long) {}
    }
)
```

取消：

```kotlin
task.cancel()
```

### 7.2 队列任务

如果需要压缩多个视频，推荐使用 SDK 队列：

```kotlin
val queueTask = client.compressQueue(
    requests = listOf(
        VideoCompressRequest(asset1, option),
        VideoCompressRequest(asset2, option)
    ),
    observer = object : VideoCompressQueueObserver {
        override fun onQueueStart(totalCount: Int) {}
        override fun onItemStart(index: Int, totalCount: Int, asset: CompressVideoAsset) {}
        override fun onItemProgress(index: Int, totalCount: Int, progress: VideoCompressProgress) {}
        override fun onItemSuccess(index: Int, totalCount: Int, result: VideoCompressResult) {}
        override fun onItemFailure(
            index: Int,
            totalCount: Int,
            request: VideoCompressRequest,
            error: VideoCompressError
        ) {}
        override fun onQueueComplete(
            results: List<VideoCompressResult>,
            failures: List<VideoCompressQueueFailure>
        ) {}
        override fun onQueueCancelled(
            results: List<VideoCompressResult>,
            failures: List<VideoCompressQueueFailure>
        ) {}
    }
)
```

取消整个队列：

```kotlin
queueTask.cancel()
```

队列特点：

- 同一时间只压缩一个视频。
- Media3 和 Native 共用同一套队列。
- 单个任务失败后会记录失败信息，并继续处理后续任务。
- 队列完成后统一返回成功结果和失败列表。
- 适合驱动批量压缩页、前台服务和通知。

## 8. 长视频前台服务建议

长视频压缩建议由业务层使用前台服务承载。

原因：

- 用户切后台后压缩仍能继续。
- 可以显示系统通知进度。
- 可以通过通知提供取消入口。
- 业务方可以决定通知文案、图标、渠道和点击行为。

推荐边界：

- SDK：提供 `compressQueue()`、进度、成功、失败、取消。
- App/Demo：实现 `ForegroundService`、通知渠道、通知进度、取消按钮。

Demo 中的实现：

```text
com.example.similarscandemo.compress.VideoCompressForegroundService
```

该服务会：

- 启动前台通知。
- 调用 SDK `compressQueue()`。
- 将进度更新到通知。
- 通过应用内广播通知页面刷新。
- 支持通知栏取消。

## 9. 压缩结果在哪里

压缩结果会保存到系统媒体库：

```text
MediaStore.Video
```

Android 10+ 使用：

```text
RELATIVE_PATH = Movies
IS_PENDING = 1 -> 0
```

业务层可以用 `VideoCompressResult.outputUri` 直接播放或展示压缩后视频。

## 10. 两套方案差异

| 对比项 | MEDIA3_TRANSFORMER | NATIVE_CODEC |
| --- | --- | --- |
| 推荐级别 | 默认生产方案 | 备用方案 |
| 实现复杂度 | 低到中 | 高 |
| 稳定性 | 更好 | 依赖设备编码器表现 |
| H.264 输出 | 支持 | 支持 |
| 三档 bitrate | 支持 | 支持 |
| 分辨率降档 | 支持 | 当前不做 |
| 低收益压缩拦截 | 支持 | 当前不做 |
| 输出结果校验 | 支持 | 基础保存结果 |
| 音频处理 | Media3 内部处理 | 当前音频透传 |
| 进度回调 | 支持 | 支持 |
| 取消 | 支持 | 支持 |
| 队列压缩 | 支持 | 支持 |
| 前台服务 | 由业务层实现 | 由业务层实现 |
| 推荐使用场景 | 正式业务主链路 | Media3 失败后的兜底 |

## 11. 为什么优化重点放 Media3

Media3 更适合承载生产优化：

- 官方封装更完整。
- 设备兼容风险更低。
- 代码更少，维护成本更低。
- 分辨率降档、fallback、进度查询都更自然。

Native 虽然可控性更强，但复杂优化会增加：

- 黑屏风险。
- 花屏风险。
- 音视频不同步风险。
- 厂商编码器适配成本。
- 长视频资源释放问题。

所以当前策略：

```text
Media3 做生产优化，Native 保持基础备用能力。
```

## 12. 接入方业务建议

业务层建议处理：

- 每日免费次数。
- PRO 订阅。
- 压缩确认弹窗。
- 压缩完成后是否删除原视频。
- 删除原视频的系统授权流程。
- 长视频前台服务通知。
- 压缩历史记录。
- 多任务压缩入口和任务列表 UI。

SDK 保持纯能力层，不绑定具体产品业务。
