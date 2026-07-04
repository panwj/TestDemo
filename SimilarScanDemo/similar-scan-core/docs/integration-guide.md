# Similar Scan Core SDK 集成指南

本文面向接入 `similar-scan-core` 的宿主产品，说明 module 依赖、权限申请、扫描调度、结果展示、缩略图加载和删除一致性接入方式。

## 1. 接入边界

`similar-scan-core` 是 Android Library module，提供本地图片/视频扫描和相似识别能力。

SDK 负责：

- MediaStore 图片/视频枚举。
- 图片、截图、视频、录屏分类。
- 图片/截图 Duplicate 识别。
- 图片/截图 Similar 识别。
- 视频/录屏 Similar 识别。
- SQLite 本地缓存、断点续扫、增量扫描。
- Similar/Duplicate/Other 分组和产品分类输出。
- UI 预览 Bitmap 加载接口。
- 删除中状态和扫描异步提交一致性。

SDK 不负责：

- 不弹系统权限框。
- 不声明宿主业务 Activity、Service、通知样式。
- 不创建前台服务通知。
- 不处理系统删除确认弹窗。
- 不提供成品 UI。
- 不上传媒体文件，不依赖网络。

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

`internal` 包中的数据库表、指纹结构、阈值和索引实现可能随 SDK 版本变化。

## 2. Gradle 接入

开发期可采用工程内 module 依赖：

```kotlin
dependencies {
    implementation(project(":similar-scan-core"))
}
```

`settings.gradle.kts` 需要包含：

```kotlin
include(":similar-scan-core")
```

SDK module 当前配置：

```kotlin
android {
    namespace = "com.clean.similarscan"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }
}

kotlin {
    jvmToolchain(17)
}
```

宿主产品建议：

- `minSdk >= 23`。
- 编译环境支持 Java 17 / Kotlin Android。
- 如果后续发布为 AAR，接入方式可替换为 Maven 坐标，业务代码不需要改动。

## 3. Manifest 权限

宿主 App 需要声明媒体读取权限。

```xml
<uses-permission
    android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />
```

如果宿主使用前台服务执行扫描，还需要：

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

前台扫描服务示例：

```xml
<service
    android:name=".service.MediaScanService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

SDK 不会自动请求这些权限，也不会自动启动 Service。

## 4. 权限申请

SDK 提供权限判断工具：

```kotlin
SimilarScanPermissionChecker.requiredPermissions()
SimilarScanPermissionChecker.hasPermission(context)
SimilarScanPermissionChecker.hasFullVisualAccess(context)
SimilarScanPermissionChecker.accessLevel(context)
```

推荐申请方式：

```kotlin
if (!SimilarScanPermissionChecker.hasPermission(context)) {
    activity.requestPermissions(
        SimilarScanPermissionChecker.requiredPermissions(),
        REQUEST_MEDIA_PERMISSION
    )
}
```

权限模型：

| Android 版本 | 权限 |
| --- | --- |
| Android 12 及以下 | `READ_EXTERNAL_STORAGE` |
| Android 13 | `READ_MEDIA_IMAGES`、`READ_MEDIA_VIDEO` |
| Android 14+ | 以上媒体权限 + `READ_MEDIA_VISUAL_USER_SELECTED` 部分访问兼容 |

`hasPermission()` 表示至少可读取图片或视频。`hasFullVisualAccess()` 表示完整图片和视频访问。全量扫描清理未出现资源只应在完整访问下生效；部分授权下，系统没有返回的资源不等于已删除。

## 5. 创建 SDK Client

创建入口：

```kotlin
val client = SimilarScanSdk.create(context)
```

`context` 会转为 `applicationContext` 保存。`SimilarScanClient` 持有内部 `SQLiteOpenHelper`，使用完成后建议关闭：

```kotlin
client.close()
```

典型生命周期：

- 单次前台服务扫描：Service 内创建 client，扫描结束后 close。
- 页面读取缓存结果：Activity/Fragment 创建 client，`onDestroy()` close。
- 如果宿主有自己的 DI 容器，可以按进程单例管理，但要避免在主线程长时间执行 scan。

## 6. 执行扫描

`scan()` 是同步阻塞方法，接入方必须放到后台线程、Worker 或前台 Service 中执行。

```kotlin
val result = client.scan(
    request = SimilarScanRequest(
        forceFull = false,
        imageFingerprintSize = 256,
        calculateDuplicateSha256DuringScan = false,
        videoFingerprintMode = VideoFingerprintMode.BALANCED
    ),
    observer = SimilarScanObserver { progress ->
        // progress.stage
        // progress.processedCount
        // progress.discoveredGroupCount
        // progress.message
    }
)
```

强制全量扫描：

```kotlin
client.scan(SimilarScanRequest(forceFull = true), observer)
```

`forceFull = true` 的含义是“强制全量枚举 MediaStore 并做媒体库对账”，不是“强制重算全部指纹”。
SDK 会继续复用未变化资源的旧 fingerprint。只有新增、内容或关键元数据变化、算法版本变化、
图片指纹尺寸变化、视频指纹模式变化、旧指纹缺失或上次未完成的资源，才会重新计算图片/视频指纹。

常用参数：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `forceFull` | `false` | 是否强制全量枚举和媒体库对账；不会强制重算全部可复用指纹 |
| `imageFingerprintSize` | `256` | 图片/截图指纹 Bitmap 最大边，SDK 会限制在 96..512 |
| `calculateDuplicateSha256DuringScan` | `false` | 是否在扫描主链路中立即补算 Duplicate 候选的 SHA-256 |
| `videoFingerprintMode` | `BALANCED` | 视频指纹模式：`FAST`、`BALANCED`、`ACCURATE`、`REFERENCE_COMPAT` |

视频模式建议：

| 模式 | 适用场景 | 特点 |
| --- | --- | --- |
| `FAST` | 速度优先、视频量大、可接受封面相似风险 | 系统缩略图单帧优先，失败时少量 MMR 帧 |
| `BALANCED` | 默认推荐 | 系统缩略图 + 多个 MMR 时间点，避免完全单帧化 |
| `ACCURATE` | 准确率优先、视频量可控 | 更多 MMR 时间点，不把系统缩略图作为唯一依据 |
| `REFERENCE_COMPAT` | 参考帧加强模式 | 不使用系统缩略图，按 7 到 13 帧规则抽帧，候选召回更宽，正常至少 2 帧命中 |

需要更高视频召回时，宿主产品可以显式传入 `VideoFingerprintMode.REFERENCE_COMPAT`；常规场景建议保留默认 `BALANCED`，或按自身业务选择 `FAST` / `ACCURATE`。

扫描阶段：

```kotlin
IDLE
ENUMERATING
FINGERPRINTING
MATCHING
COMPLETED
FAILED
```

推荐调度：

```text
用户授权
-> 宿主启动前台服务或后台任务
-> 后台线程调用 client.scan()
-> observer 回调进度
-> 宿主通过通知、广播、Flow、LiveData 或其他状态容器通知 UI
-> UI 节流读取 client.loadProductCategories(previewAssetLimit = 2)
```

宿主应用可以使用前台 Service、WorkManager 或自有任务体系承载扫描。

## 7. 扫描频率建议

推荐触发场景：

- 用户首次授权后执行一次扫描。
- 用户手动点击重新扫描。
- App 前台观察到 MediaStore Images 或 Video 变化后，做防抖增量扫描。
- App 冷启动或回到首页时先展示缓存结果，再按需触发增量扫描。

建议防抖：

```text
MediaStore ContentObserver onChange
-> 延迟 1~3 秒
-> 如果没有扫描进行中，再启动扫描
```

不建议：

- 每个 MediaStore 变化事件立即启动扫描。
- 在主线程直接调用 `scan()`。
- 多个扫描任务并发写同一个 SDK 数据库。

## 8. 读取结果

SDK 提供两类结果接口。

### 8.1 产品分类结果

推荐首页使用：

```kotlin
val categories = client.loadProductCategories(previewAssetLimit = 2)
```

返回类型：

```kotlin
List<ProductCategory>
```

`ProductCategory` 包含：

```kotlin
val type: ProductCategoryType
val groups: List<SimilarGroup>
val itemCount: Int
val assets: List<MediaAsset>
val totalSize: Long
```

当前分类顺序：

```text
SIMILAR
DUPLICATES
SIMILAR_SCREENSHOTS
SIMILAR_VIDEOS
OTHER_SCREENSHOTS
OTHER_VIDEOS
OTHER
```

`itemCount` 是分类总资源数，`totalSize` 是分类总大小。`assets` 会对 `(kind, id)` 去重。

`previewAssetLimit` 只限制每个底层分组随结果返回的预览资源数量，不影响分类和分组的
真实数量、真实大小。首页、Tab 页这类只需要缩略预览的场景建议传入较小值，例如 2；
这样每个分类仍能展示完整数量，但不会因为 Other、Other Screenshots 等大分类一次性
读取几千张资源导致列表卡顿或 CursorWindow 压力。

详情页推荐按当前入口只读取一个分类。对于 Other、Other Videos 等平铺大类，
先读取分类摘要，再分页加载资源：

```kotlin
val category = client.loadProductCategory(
    ProductCategoryType.OTHER,
    previewAssetLimit = 0
)

val page = client.loadProductCategoryAssets(
    type = ProductCategoryType.OTHER,
    offset = 0,
    limit = 120
)
```

注意：`loadProductCategoryAssets()` 只服务 `grouped = false` 的平铺分类，例如
`OTHER`、`OTHER_VIDEOS`、`OTHER_SCREENSHOTS`。如果传入 `SIMILAR`、`DUPLICATES`
这类分组分类，SDK 会保持宽容并返回空列表，不会抛异常。分组分类必须先读取
`ProductCategory.groups`，再按 `groupId` 分页加载组内资源。

对于 Similar、Duplicates 等分组类详情，可以按 groupId 分页加载某个分组下的资源：

```kotlin
val page = client.loadSimilarGroupAssets(
    groupId = group.id,
    offset = group.assets.size,
    limit = 60
)
```

`SimilarGroup.latestAssetTimeMillis` 来自数据库对完整分组的 `MAX(created_at/date_added)`
聚合，首页排序不依赖 `previewAssetLimit` 返回的少量预览资源。

### 8.2 原始分组

用于诊断、详情页或宿主自定义分类：

```kotlin
val groups = client.loadGroups(groupLimit = Int.MAX_VALUE)
```

`SimilarGroup` 包含：

```kotlin
val id: Long
val title: String
val subtitle: String
val category: GroupCategory
val kind: MediaKind
val assets: List<MediaAsset>
val totalAssetCount: Int
val totalSizeBytes: Long
val latestAssetTimeMillis: Long
```

如果宿主直接使用 `loadGroups()`，需要自己处理产品分类顺序、Duplicate/Similar 互斥、Other 分类展示等。普通首页优先使用 `loadProductCategories()`。

### 8.3 业务层可实现的典型场景

SDK 对外 API 已经覆盖大部分图库清理业务场景，宿主产品不需要直接访问数据库表。

| 业务场景 | 推荐 API | 说明 |
| --- | --- | --- |
| 首页展示分类卡片 | `loadProductCategories(previewAssetLimit = 2)` | 返回固定产品分类、真实数量、真实大小和少量预览图。 |
| 首页扫描中实时刷新 | `loadProductCategories(previewAssetLimit = n)` | 可配合节流刷新；预览数量不影响真实统计值。 |
| 进入单个分类详情 | `loadProductCategory(type, previewAssetLimit = 0)` | 先读取摘要，避免为了一个分类加载全部分类资源。 |
| 平铺分类分页 | `loadProductCategoryAssets(type, offset, limit)` | 适用于 `OTHER_SCREENSHOTS`、`OTHER_VIDEOS`、`OTHER`。 |
| 相似/相同分组详情 | `loadSimilarGroupAssets(groupId, offset, limit)` | 适用于 `SIMILAR`、`DUPLICATES`、`SIMILAR_SCREENSHOTS`、`SIMILAR_VIDEOS` 下的单个分组。 |
| 大图预览左右切换 | `loadSimilarGroupAssets` 或 `loadProductCategoryAssets` | 先加载当前页，接近边界时继续分页。 |
| 删除前保护扫描结果 | `markDeletePending(uris)` | 系统删除确认前调用，避免扫描任务复活待删除资源。 |
| 删除确认后清理缓存 | `finalizeDelete(uris)` | 用户确认系统删除后调用。 |
| 删除取消后恢复资源 | `restoreDeletePending(uris)` | 用户取消系统删除后调用，等待后续扫描重新校验。 |
| 冷启动恢复悬挂状态 | `recoverStaleDeletePending()` | 处理 App 被杀后遗留的删除中状态。 |
| UI 缩略图加载 | `loadBitmap(asset, thumbSize)` | 只用于展示预览，不影响扫描指纹 Bitmap。 |

当前 `ProductCategoryType` 是展示分类，和底层入库类型不是一一对应：

| ProductCategoryType | 底层资源 |
| --- | --- |
| `SIMILAR` | 普通照片相似组 |
| `DUPLICATES` | 普通照片和截图的重复组 |
| `SIMILAR_SCREENSHOTS` | 截图相似组 |
| `SIMILAR_VIDEOS` | 普通视频和录屏相似组 |
| `OTHER_SCREENSHOTS` | 未命中的截图 |
| `OTHER_VIDEOS` | 未命中的普通视频和录屏 |
| `OTHER` | 未命中的普通照片，包含聊天来源图片 |

`MediaAsset.kind` 仍会暴露底层媒体类型：

```text
PHOTO
SCREENSHOT
VIDEO
SCREEN_RECORDING
```

因此宿主业务如果需要在某个产品分类内部继续细分，例如在 `OTHER_VIDEOS` 中把录屏单独筛出，
可以基于 `MediaAsset.kind == SCREEN_RECORDING` 自行过滤展示；这类二次展示不会影响 SDK 的扫描和分组结果。

## 9. UI 缩略图加载

扫描指纹 Bitmap 是 SDK 内部逻辑，接入方不要调用内部 `MediaBitmapLoader`。

UI 预览图使用：

```kotlin
val bitmap = client.loadBitmap(asset, thumbSize = 1024)
```

或单独创建 loader：

```kotlin
val loader = SimilarScanSdk.createImageLoader(context)
val bitmap = loader.loadBitmap(asset, 1024)
loader.close()
```

注意：

- `loadBitmap()` 可能执行解码或抽帧，建议放到后台线程。
- 列表中应自行做 ViewHolder 绑定校验，避免异步加载结果错位。
- 图片 UI 缩略图和扫描指纹 Bitmap 不是同一个输入。
- 视频 UI 封面和视频相似分析帧也不是同一个输入。

## 10. 删除接入

宿主负责调用 Android 系统删除确认。SDK 负责删除前后的本地扫描状态一致性。

推荐流程：

```kotlin
val selectedUris: List<String> = selectedAssets.map { it.uri.toString() }
val markedUris = client.markDeletePending(selectedUris)
```

`markDeletePending()` 会：

```text
state = DELETE_PENDING
revision + 1
```

这样后台扫描中旧 token 无法再提交这些资源的指纹或分组。

然后宿主发起系统删除确认。例如 Android 11+ 可使用：

```kotlin
MediaStore.createDeleteRequest(contentResolver, uris)
```

用户确认删除后：

```kotlin
client.finalizeDelete(markedUris)
```

用户取消删除后：

```kotlin
client.restoreDeletePending(markedUris)
```

App 冷启动时，如果上次删除确认期间进程被杀，可以执行：

```kotlin
client.recoverStaleDeletePending()
```

建议宿主持久化本次删除操作状态，确认回调结束后清理。宿主应用可用自有持久化组件完成这部分状态管理。

## 11. 前台服务参考结构

SDK 不内置 Service。宿主可参考以下结构：

```kotlin
class MediaScanService : Service() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Preparing..."))
        val forceFull = intent?.getBooleanExtra("force_full", false) == true

        executor.execute {
            val client = SimilarScanSdk.create(applicationContext)
            try {
                val result = client.scan(
                    SimilarScanRequest(
                        forceFull = forceFull,
                        videoFingerprintMode = VideoFingerprintMode.BALANCED
                    ),
                    SimilarScanObserver { progress ->
                        // update notification
                        // publish progress to UI
                    }
                )
                // publish complete result
            } catch (t: Throwable) {
                // publish failure
            } finally {
                client.close()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
}
```

如果宿主需要更高视频召回，可以改为：

```kotlin
SimilarScanRequest(
    forceFull = forceFull,
    videoFingerprintMode = VideoFingerprintMode.REFERENCE_COMPAT
)
```

宿主需要自己保证：

- 同一时间只有一个扫描任务写库。
- Service 生命周期结束时关闭 client。
- 通知权限拒绝不阻断媒体扫描。
- 进度回调不要高频刷新复杂 UI，建议节流读取缓存结果。

## 12. Activity 首页参考流程

推荐首页行为：

```text
onCreate
-> 创建 client
-> recoverStaleDeletePending()
-> loadProductCategories(previewAssetLimit = 2) 展示缓存
-> 如果没有权限，展示授权入口

用户点击扫描
-> 检查媒体权限
-> 必要时请求通知权限
-> 启动前台扫描服务

收到扫描进度
-> 更新状态文案
-> 节流调用 loadProductCategories(previewAssetLimit = 2)

收到完成
-> 隐藏进度
-> 再次 loadProductCategories(previewAssetLimit = 2)

onDestroy
-> client.close()
```

如果宿主用 Compose、Flow、WorkManager 或自研任务框架，只需要保持同样的数据流：扫描在后台执行，UI 从 SDK 缓存读取结果。

## 13. 结果展示建议

首页建议展示 `ProductCategoryType`：

```kotlin
when (category.type) {
    ProductCategoryType.SIMILAR -> ...
    ProductCategoryType.DUPLICATES -> ...
    ProductCategoryType.SIMILAR_SCREENSHOTS -> ...
    ProductCategoryType.SIMILAR_VIDEOS -> ...
    ProductCategoryType.OTHER_SCREENSHOTS -> ...
    ProductCategoryType.OTHER_VIDEOS -> ...
    ProductCategoryType.OTHER -> ...
}
```

分组类分类：

```text
SIMILAR
DUPLICATES
SIMILAR_SCREENSHOTS
SIMILAR_VIDEOS
```

非分组类分类：

```text
OTHER_SCREENSHOTS
OTHER_VIDEOS
OTHER
```

`Other` 类可能资源很多。首页建议通过 `previewAssetLimit` 只读取少量预览资源，同时用
`itemCount` 和 `totalSize` 展示真实数量与大小。用户点击分类后，详情页先通过
`loadProductCategory(categoryType, previewAssetLimit = 0)` 获取摘要，再用
`loadProductCategoryAssets(categoryType, offset, limit)` 逐页追加资源。

## 14. 线程与性能注意事项

- `scan()` 必须后台执行。
- `loadBitmap()` 建议后台执行。
- `loadProductCategories()` 会读 SQLite，建议避免在主线程高频调用。
- 进度回调可能在扫描线程触发，宿主更新 UI 前需要切主线程。
- 大图库初次冷扫耗时较长，建议使用前台服务和持续通知。
- 增量扫描会复用未变化资源的旧指纹，二次扫描成本显著降低。
- SDK 内部会并发计算图片和视频指纹，但数据库提交、BK-Tree 更新和分组写入仍串行执行；宿主仍然只应启动一个扫描任务。

当前主要耗时通常在：

```text
图片指纹 Bitmap 加载
BK-Tree 候选查询
SQLite 指纹写入
Duplicate 候选 SHA-256 按需或延后计算
视频多帧抽帧
```

视频路径默认 `BALANCED`，会在系统缩略图之外补充 MMR 时间点；如果切到 `FAST`，识别效果仍会受单帧策略影响。`REFERENCE_COMPAT` 会提高相似视频召回，但候选范围和帧匹配规则更宽，性能和误合并风险要按产品目标评估。

## 15. 多产品接入建议

如果同一公司内多个产品接入 SDK：

- 保持 `similar-scan-core` 单独 module 或 AAR 发布。
- 宿主产品只依赖 API 包，不访问 internal 包。
- 权限、通知、删除确认、UI 文案由宿主产品本地化。
- SDK 数据库名当前固定为 `similar_scan.db`，同一个 App 内不要同时接入多个互不兼容版本。
- 如果未来需要不同产品共存多套配置，应先扩展 `SimilarScanRequest` 或 SDK 初始化参数，而不是修改 internal 常量。

## 16. 调试与排查

常见问题：

| 问题 | 排查方向 |
| --- | --- |
| 扫描不到新照片 | 是否 Android 14 部分授权；是否只触发增量；MediaStore 是否可见 |
| Similar 数量偏少 | 图片缩略图是否加载失败；算法版本是否一致；资源是否进入 Duplicate |
| Similar 数量偏多 | 截图/录屏分类是否误判；阈值是否过宽；是否误用连通分量理解分组 |
| 视频相似不稳定 | 当前 videoFingerprintMode；低信息帧是否过多；DATA/FileDescriptor 是否可读；有效帧数量是否不足 |
| 删除后又出现 | 是否调用 markDeletePending；系统确认后是否调用 finalizeDelete；是否有并发扫描 |
| 首页计数重复 | 是否直接拼 loadGroups；建议使用 loadProductCategories |

需要更细粒度诊断时，可以临时查看 SDK 内部数据库表，但不要把 internal 表结构作为产品代码依赖。

## 17. 最小接入清单

接入一个新产品至少需要完成：

- 添加 `implementation(project(":similar-scan-core"))` 或 AAR 依赖。
- Manifest 声明媒体权限。
- Activity 中申请媒体权限。
- 后台线程、前台 Service 或 Worker 中调用 `client.scan()`。
- 首页读取 `client.loadProductCategories(previewAssetLimit = 2)`，只拿少量预览资源和完整计数。
- 分类详情读取 `client.loadProductCategory(categoryType)`，只加载当前分类的完整资源。
- 列表或详情使用 `client.loadBitmap()` 加载预览图。
- 删除前调用 `markDeletePending()`，删除确认后调用 `finalizeDelete()` 或 `restoreDeletePending()`。
- 生命周期结束时调用 `client.close()`。

完成以上步骤后，宿主产品即可获得本地相似媒体扫描能力。
