# Similar Scan Core SDK

`similar-scan-core` 是可复用的 Android 图库扫描与相似识别 SDK module。当前 Demo 通过该 module 实现照片、截图、视频、录屏资源扫描，相似/相同识别，扫描结果落库，断点续扫，增量扫描，以及删除状态一致性处理。

## 1. SDK 功能

SDK 当前支持：

- 枚举本机 MediaStore 图片和视频资源。
- 区分普通照片、截图、普通视频、录屏。
- 识别相同图片/截图。
- 识别相似图片。
- 识别相似截图。
- 识别相似视频和相似录屏。
- 输出 Other Screenshots、Other Videos、Other Screen Recordings、Other 等产品分类。
- 扫描结果实时落库，支持边扫边展示。
- 支持完整扫描和增量扫描。
- 支持断点续扫，资源未变化时复用旧指纹。
- 支持用户删除过程中的 DELETE_PENDING 状态，避免后台扫描把待删资源重新加入结果。
- 支持 UI 缩略图加载接口，接入方无需直接依赖内部 Bitmap 加载器。

SDK 不负责：

- 不弹系统权限框。
- 不实现前台通知。
- 不实现 Activity/Fragment UI。
- 不直接执行系统删除确认弹窗。

这些能力属于宿主 App。Demo 中由 `app` module 自己实现。

## 2. Module 结构

```text
similar-scan-core/
  src/main/java/com/clean/similarscan/
    api/                  # SDK 对外入口
    api/model/            # SDK 对外 DTO
    permission/           # 权限状态判断，不发起权限申请
    internal/database/    # SQLite 落库与删除状态
    internal/scanner/     # MediaStore 枚举、分类、扫描编排、Bitmap 加载
    internal/similarity/  # dHash、colorHash、视频指纹、阈值、BK-Tree
    internal/model/       # SDK 内部模型，不对 app 直接暴露
    internal/util/        # 内部工具
```

对外接入只应依赖：

```kotlin
com.clean.similarscan.api.*
com.clean.similarscan.api.model.*
com.clean.similarscan.permission.*
```

不要依赖：

```kotlin
com.clean.similarscan.internal.*
```

## 3. Gradle 接入

当前 Demo 使用工程 module 依赖：

```kotlin
dependencies {
    implementation(project(":similar-scan-core"))
}
```

SDK module 配置：

```kotlin
android {
    namespace = "com.clean.similarscan"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }
}
```

## 4. 权限职责

SDK 只提供权限状态判断和所需权限列表：

```kotlin
SimilarScanPermissionChecker.requiredPermissions()
SimilarScanPermissionChecker.hasPermission(context)
SimilarScanPermissionChecker.hasFullVisualAccess(context)
SimilarScanPermissionChecker.accessLevel(context)
```

宿主 App 负责申请权限：

```kotlin
activity.requestPermissions(
    SimilarScanPermissionChecker.requiredPermissions(),
    REQUEST_CODE
)
```

需要在宿主 App Manifest 中声明：

```xml
<uses-permission
    android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />
```

Android 14+ 如果用户只授予部分照片访问，SDK 只能扫描系统返回的可见资源。刚拍照片没有出现在结果中时，优先检查是否为部分授权。

## 5. 基本用法

创建扫描客户端：

```kotlin
val client = SimilarScanSdk.create(context)
```

开始扫描：

```kotlin
val result = client.scan(
    request = SimilarScanRequest(forceFull = false),
    observer = SimilarScanObserver { progress ->
        // progress.processedCount
        // progress.discoveredGroupCount
        // progress.message
    }
)
```

读取首页分类：

```kotlin
val categories = client.loadProductCategories()
```

读取原始分组：

```kotlin
val groups = client.loadGroups()
```

加载预览图：

```kotlin
val imageLoader = SimilarScanSdk.createImageLoader(context)
val bitmap = imageLoader.loadBitmap(asset, 1024)
```

释放资源：

```kotlin
client.close()
imageLoader.close()
```

## 6. 删除一致性

删除流程建议：

```kotlin
val markedUris = client.markDeletePending(selectedUris)
```

然后宿主 App 发起系统删除确认。

用户确认删除：

```kotlin
client.finalizeDelete(markedUris)
```

用户取消删除：

```kotlin
client.restoreDeletePending(markedUris)
```

App 冷启动时可恢复悬挂删除状态：

```kotlin
client.recoverStaleDeletePending()
```

这样可以保证后台扫描异步运行时，用户正在删除的资源不会被重新写回相似结果。

## 7. 扫描流程

整体流程：

```text
权限检查
  -> MediaStore 分批枚举
  -> 媒体类型分类
  -> media_asset 落库
  -> 判断是否可复用旧指纹
  -> 图片/视频指纹计算
  -> 相同识别
  -> 相似候选召回
  -> 相似精判
  -> 增量写入分组
  -> 扫描完成后按锚点规则重建分组
  -> 输出产品分类
```

扫描批大小当前为 `500`。批大小只影响 MediaStore 读取节奏和进度回调频率，不是相似比较边界。

## 8. 资源读取策略

SDK 读取 MediaStore 图片和视频：

- 图片集合：`MediaStore.Images.Media.EXTERNAL_CONTENT_URI`
- 视频集合：`MediaStore.Video.Media.EXTERNAL_CONTENT_URI`
- 排序：按 `DATE_ADDED DESC`
- API 30+ 使用 generation 信息辅助增量扫描。
- API 30 以下使用 legacy 路径，仍保留最低 API 23 支持。

资源分类基于：

- MediaStore 类型。
- DISPLAY_NAME。
- MIME_TYPE。
- pathHint / bucket。
- 截图、录屏常见文件名和目录规则。

当前支持的内部媒体类型：

- `PHOTO`
- `SCREENSHOT`
- `VIDEO`
- `SCREEN_RECORDING`

## 9. 图片指纹方案

图片和截图使用组合指纹：

```text
CombinedHash = dHash + colorHash
```

Bitmap 输入策略：

1. API 29+ 优先使用 `ContentResolver.loadThumbnail(asset.uri, Size(256, 256), null)`。
2. 失败或低版本回退 `MediaStore.Images.Thumbnails.getThumbnail(...)`。
3. 再失败回退 URI decode。
4. 最后回退 DATA 文件路径 decode。
5. 指纹 Bitmap 统一等比归一到最大边 `256`。

dHash：

- Kotlin 实现来自竞品 native 反汇编方案。
- 使用 9x8 采样。
- 灰度公式：`0.299R + 0.587G + 0.114B`。
- 生成 64 bit Long。

colorHash：

- RGB 三通道直方图。
- 每个通道按 32 分桶，共 8 桶。
- 最终为 `8 x 3` 数组。
- 除数使用 `pixels.size / 16.0`，与竞品反编译实现保持一致。

质量分：

- 首次扫描主链路使用轻量 metadataScore。
- 质量分仅用于 Best 排序，不参与相似/相同判断。

## 10. 视频指纹方案

视频和录屏使用视频帧组合指纹。

当前策略：

- 优先使用系统视频缩略图生成单帧指纹。
- 系统缩略图失败时，使用 MediaMetadataRetriever 抽取多帧。
- 多帧数量和间隔由 `VideoFingerprintCalculator` 控制。
- 每帧计算 `CombinedHash`。
- 视频相似判断不是单帧相等，而是跨帧多次命中精判。

视频候选召回会先按：

- 类型。
- duration bucket。
- aspect bucket。
- 指纹算法版本。

过滤后再进入多帧相似判断。

## 11. 阈值策略

普通照片阈值：

```text
dHash 0..4     -> 直接相似
dHash 4..10    -> colorHash 0..7
dHash 10..18   -> colorHash 0..5
dHash >= 18    -> 不相似
```

截图、视频、录屏使用严格阈值：

```text
dHash 0..2     -> 直接相似
dHash 2..10    -> colorHash 0..5
dHash 10..16   -> colorHash 0..2
dHash >= 16    -> 不相似
```

严格阈值用于降低截图、录屏和静止视频画面误合并。

## 12. 候选召回和分组

图片候选召回：

- 使用 BK-Tree。
- 索引 key 为 64 bit dHash。
- 查询距离为当前类型阈值上限减一。
- 召回后再用 `dHash + colorHash` 精判。

相同识别：

- 使用竞品式 duplicateReference。
- 条件包括媒体类型、宽高、感知 hash、编辑状态、文件大小等。
- SHA-256 保留为诊断证据，不作为唯一重复条件。

分组规则：

- 扫描中实时写入 Similar/Duplicate 分组，便于 UI 及时展示。
- 扫描完成后按竞品锚点规则重建 Similar 分组。
- 锚点规则不是连通分量：`A≈B`、`B≈C` 不代表 `A≈C` 必然同组。
- 图片锚点顺序按创建时间升序。
- 视频锚点顺序更接近 MediaStore `date_added DESC`。

## 13. 增量扫描和复用

SDK 会为每个资源记录：

- MediaStore id。
- type。
- uri。
- width / height / duration / size。
- dateAdded / createdAt / updatedAt。
- generationAdded / generationModified。
- sourceSignature。
- fingerprint_algorithm_version。
- fingerprint_status。

如果资源的关键元数据和算法版本未变化，则复用旧指纹，不重新解码 Bitmap 或抽帧。

如果资源被删除或不再可见：

- 完整扫描结束后会清理本轮未见资源。
- 增量扫描依赖 MediaStore generation 和下次完整校验。

## 14. 对外模型边界

SDK 对外只暴露：

```kotlin
com.clean.similarscan.api.model.MediaAsset
com.clean.similarscan.api.model.SimilarGroup
com.clean.similarscan.api.model.ProductCategory
com.clean.similarscan.api.model.ScanProgress
com.clean.similarscan.api.model.ScanResult
```

内部数据库模型位于：

```kotlin
com.clean.similarscan.internal.model
```

接入方不应使用 internal 包。这样后续 SDK 可以调整数据库字段、指纹结构、候选索引，而不影响接入方代码。

## 15. 当前性能基线

在 Demo 真机约 9k 资源数据上，当前优化后的冷扫描约 160s 级别。主要耗时集中在：

- `load_fingerprint_bitmap`
- `bk_tree_visual_query`
- `mark_visual_fingerprint_done`
- `sha256_current_asset`

视频路径当前耗时较低，主要瓶颈已经转移到图片缩略图加载和图片相似候选召回。

后续可继续优化：

- 图片 Bitmap 预取或受控并发。
- BK-Tree 分桶索引。
- 指纹状态批量写库。
- SHA-256 延迟计算。

这些优化应在保持相似结果准确性的前提下逐项验证。
