# Similar Scan Core SDK

`similar-scan-core` 是可复用的 Android 本地图库扫描与相似识别 SDK module。该 module 提供照片、截图、视频、录屏扫描能力，可作为独立 SDK 接入宿主产品。

## 文档入口

- [核心技术方案](docs/core-technical-design.md)：扫描链路、照片缩略图指纹、dHash/colorHash、BK-Tree、内存缓存、Duplicate、Similar 分组、视频单帧/多帧逻辑、删除一致性。
- [SDK 集成指南](docs/integration-guide.md)：Gradle 依赖、权限申请、后台扫描、结果读取、缩略图加载、删除接入、宿主职责和排查清单。

## SDK 能力

SDK 当前支持：

- 枚举本机 `MediaStore.Images` 和 `MediaStore.Video`。
- 区分普通照片、截图、普通视频、录屏。
- 识别相同图片/截图。
- 识别相似图片、相似截图、相似视频、相似录屏。
- 输出 Similar、Duplicates、Similar Screenshots、Similar Videos、Other Screenshots、Chat Photos、Similar Screen Rec、Other Screen Rec、Other Videos、Other 等产品分类。
- SQLite 落库，支持缓存展示、断点续扫、增量扫描和资源未变化时复用旧指纹。
- 使用删除中状态和 revision token，避免异步扫描把待删除资源重新写回结果。
- 提供 UI 预览图加载接口，接入方无需访问内部 Bitmap 加载器。

SDK 当前不负责：

- 不弹系统权限框。
- 不实现前台通知和扫描 Service。
- 不提供成品 Activity/Fragment/UI。
- 不发起系统删除确认弹窗。
- 不上传媒体文件，不依赖网络。

这些能力由宿主产品实现。

## Module 结构

```text
similar-scan-core/
  src/main/java/com/clean/similarscan/
    api/                  # SDK 对外入口
    api/model/            # SDK 对外 DTO
    permission/           # 权限状态判断，不发起权限申请
    internal/database/    # SQLite 落库、分组、删除状态
    internal/scanner/     # MediaStore 枚举、分类、扫描编排、Bitmap 加载
    internal/similarity/  # dHash、colorHash、阈值、BK-Tree、视频指纹
    internal/model/       # 内部模型
    internal/util/        # 内部工具
```

接入方只应依赖：

```kotlin
com.clean.similarscan.api.*
com.clean.similarscan.api.model.*
com.clean.similarscan.permission.*
```

不要依赖：

```kotlin
com.clean.similarscan.internal.*
```

## 快速接入

Gradle：

```kotlin
dependencies {
    implementation(project(":similar-scan-core"))
}
```

Manifest 媒体权限：

```xml
<uses-permission
    android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />
```

权限判断：

```kotlin
if (!SimilarScanPermissionChecker.hasPermission(context)) {
    activity.requestPermissions(
        SimilarScanPermissionChecker.requiredPermissions(),
        REQUEST_MEDIA_PERMISSION
    )
}
```

创建 Client：

```kotlin
val client = SimilarScanSdk.create(context)
```

后台执行扫描：

```kotlin
val result = client.scan(
    request = SimilarScanRequest(forceFull = false),
    observer = SimilarScanObserver { progress ->
        // progress.stage
        // progress.processedCount
        // progress.discoveredGroupCount
        // progress.message
    }
)
```

读取首页分类：

```kotlin
val categories = client.loadProductCategories(previewAssetLimit = 2)
```

`previewAssetLimit` 只限制每个分组随结果返回的预览资源数量，不影响
`ProductCategory.itemCount`、`ProductCategory.totalSize`、`SimilarGroup.totalAssetCount`
和 `SimilarGroup.totalSizeBytes`。首页建议传入较小值，例如 2，避免 Other 等大分类
一次性加载大量资源。

进入分类详情时推荐只读取当前分类。平铺大分类可以先读取分类摘要，再分页读取资源：

```kotlin
val category = client.loadProductCategory(
    ProductCategoryType.OTHER,
    previewAssetLimit = 0
)

val firstPage = client.loadProductCategoryAssets(
    type = ProductCategoryType.OTHER,
    offset = 0,
    limit = 120
)
```

相似/相同分组下的资源可以按 groupId 分页读取：

```kotlin
val page = client.loadSimilarGroupAssets(
    groupId = group.id,
    offset = 0,
    limit = 60
)
```

分组排序使用数据库聚合出的 `latestAssetTimeMillis`，不会因为首页只返回少量预览资源而改变排序。

读取原始分组：

```kotlin
val groups = client.loadGroups()
```

加载 UI 预览图：

```kotlin
val bitmap = client.loadBitmap(asset, thumbSize = 1024)
```

释放资源：

```kotlin
client.close()
```

`scan()` 是同步阻塞方法，必须在后台线程、Worker 或前台 Service 中执行。

## 删除接入

宿主发起系统删除确认前：

```kotlin
val markedUris = client.markDeletePending(selectedUris)
```

用户确认删除后：

```kotlin
client.finalizeDelete(markedUris)
```

用户取消删除后：

```kotlin
client.restoreDeletePending(markedUris)
```

App 冷启动时可恢复悬挂删除状态：

```kotlin
client.recoverStaleDeletePending()
```

完整流程见 [SDK 集成指南](docs/integration-guide.md)。

## 当前实现要点

- 图片/截图扫描指纹默认使用最大边 256 的 Bitmap，可通过 `SimilarScanRequest.imageFingerprintSize` 配置，优先系统缩略图，失败后 URI/DATA 降采样解码。
- 图片指纹为 `CombinedHash = 64-bit dHash + RGB 8x3 colorHash`。
- 照片和截图分别维护 BK-Tree，BK-Tree 只做 dHash 候选召回。
- `assetId -> CombinedHash` 内存缓存用于候选精判，避免大量回库读取 colorHash。
- Duplicate 使用图片/截图的 duplicateReference 规则，SHA-256 是可延后缓存的字节级证据，不是进入 Duplicate 的硬条件；默认不阻塞扫描主链路。
- Similar 最终按锚点直连规则重建，不使用相似关系传递闭包。
- 视频/录屏支持 `FAST`、`BALANCED`、`ACCURATE`、`REFERENCE_COMPAT` 四档指纹模式；SDK 默认 `BALANCED`，宿主产品可按召回和性能目标选择 `REFERENCE_COMPAT`。
- `REFERENCE_COMPAT` 不混用系统视频缩略图，按参考帧规则抽取 7 到 13 个 9x8 帧，正常至少 2 帧命中，单帧有效时降到 1 帧命中。
- 视频候选在普通模式下按类型、时长桶、宽高比桶收窄；参考帧模式下按同类型、同算法版本宽召回，再做帧级汉明预筛和完整帧级 `dHash + colorHash` 精判。
- 全量扫描中图片和视频指纹计算使用独立线程池并发执行，SQLite 提交、BK-Tree 更新和分组写入仍在扫描线程串行完成，避免内部索引并发写入。

详细算法和风险点见 [核心技术方案](docs/core-technical-design.md)。
