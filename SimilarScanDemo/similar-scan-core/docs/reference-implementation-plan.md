# Similar Scan Reference Implementation Plan

本文面向其他本地媒体扫描项目，说明如何参考当前 demo 的相似扫描方案、首页更新策略和详情页数据方案进行落地。文档不依赖 demo UI，可用于已有 Android 项目、Compose 项目或自研扫描框架。

## 1. 目标

参考实现需要达成：

- 扫描在后台执行，不阻塞 Activity/Fragment 生命周期。
- 首页可以先展示缓存结果，再在扫描中阶段性展示新结果。
- 详情页从首页进入后，能看到与首页一致的阶段性分组。
- 扫描完成后统一切换到最终 DB 分组结果。
- 删除资源时，不让后台扫描把待删除资源重新写回 UI。
- 新增/外部删除媒体不要求本轮实时一致，但下次主动重扫或重新进入后能通过 MediaStore 对账更新。

## 2. 推荐架构

```text
UI 层
  Home
  Category Detail
  Preview

业务层
  Permission Flow
  Foreground Scan Service / Worker
  Progress State
  Delete Flow

SDK 层
  SimilarScanClient
  MediaStore Enumerator
  Fingerprint Compute
  Candidate Edge Store
  Progressive Snapshot
  Final DB Groups
```

核心原则：

- UI 不直接读 SDK internal 表。
- 扫描只通过 `SimilarScanClient.scan()` 触发。
- 首页和详情只读 `ProductCategory`、`SimilarGroup`、`MediaAsset` 这些 API DTO。
- 扫描中优先读 progressive snapshot；扫描完成后读 DB final result。

## 3. 扫描配置

推荐请求：

```kotlin
SimilarScanRequest(
    forceFull = false,
    imageFingerprintSize = 196,
    videoFingerprintMode = VideoFingerprintMode.ADAPTIVE_BALANCED,
    enableIntermediateGroupPublish = true,
    firstIntermediateGroupPublishIntervalMs = 60_000L,
    firstIntermediateGroupPublishMinAssets = 5_000,
    firstIntermediateGroupPublishMinEdges = 1,
    intermediateGroupPublishIntervalMs = 90_000L,
    intermediateGroupPublishMinAssets = 10_000,
    intermediateGroupPublishMinEdges = 20_000,
    maxIntermediateGroupPublishCount = 2
)
```

说明：

- `imageFingerprintSize = 196` 是当前 demo 性能优化值，适合先验证扫描耗时收益；如果产品对召回更敏感，可回到 256 对比。
- `ADAPTIVE_BALANCED` 适合系统视频缩略图在部分设备上慢失败的场景。
- DB 中间发布保留为低频兜底，不作为扫描中 UI 首显主路径。
- 快速首显和过程刷新依赖 progressive snapshot。

## 4. 扫描服务

推荐使用前台 Service 或等价后台任务：

```text
startForeground()
-> executor.execute
-> SimilarScanSdk.create(applicationContext)
-> client.scan(request, observer)
-> observer 更新通知和业务进度状态
-> success/failure 广播或写入状态容器
-> finally client.close()
```

注意事项：

- 同一进程同一时间只允许一个扫描任务写库。
- `scan()` 是同步阻塞方法，不能在主线程执行。
- Service 结束必须 `client.close()`。
- 通知权限拒绝不应阻断媒体扫描，只影响通知展示。
- 进度状态与结果刷新状态要分开。

## 5. 进度与结果刷新

`ScanProgress` 中最重要的字段：

- `processedCount`：已扫描/访问的媒体数量，用于进度。
- `discoveredGroupCount`：当前发布出来的分组数量，用于展示。
- `elapsedTimeText`：耗时文案。
- `resultUpdated`：是否可以重新读取结果。

推荐策略：

```text
resultUpdated = false
-> 只更新扫描数量、耗时、进度条

resultUpdated = true
-> 节流刷新首页/详情数据
```

不要在每个普通进度事件都读取分类数据，否则会造成 UI 闪烁和 SQLite 压力。

## 6. 首页数据方案

首页首次进入：

```text
loadProductCategories(previewAssetLimit = 0)
-> 只拿分类统计和分组摘要
-> 对首页真正展示的首组按需加载 2 张预览图
```

扫描中收到 `resultUpdated=true`：

```text
cached = loadProductCategories(previewAssetLimit = 0)
progressive = loadProgressiveProductCategories(previewAssetLimit = 2)
display = progressive 中 itemCount>0 的分类覆盖 cached
display = progressive 未覆盖分类继续展示 cached
```

扫描完成：

```text
loadProductCategories(previewAssetLimit = 0)
-> 按首页展示需要补首组预览
```

这样做的好处：

- 首页不会一开始等待完整 DB rebuild。
- snapshot 未覆盖分类不会突然清零。
- 首页只加载真正展示的预览图，避免每个 group 都读取 2 张图。
- 最终结果统一由 DB final result 决定。

## 7. 详情页数据方案

进入分类详情页：

```kotlin
val category = if (isScanning) {
    client.loadProgressiveProductCategory(type, previewAssetLimit = PAGE_SIZE)
        ?.takeIf { it.itemCount > 0 }
        ?: client.loadProductCategory(type, previewAssetLimit = PAGE_SIZE)
} else {
    client.loadProductCategory(type, previewAssetLimit = PAGE_SIZE)
}
```

停留在详情页内：

```text
监听扫描状态
-> ACTION_PROGRESS && resultUpdated=true 时 reload 当前分类
-> ACTION_COMPLETE 时 reload 最终 DB 分类
```

分组内分页：

```kotlin
client.loadSimilarGroupAssets(groupId, offset, limit)
```

注意：

- `groupId < 0` 表示扫描中的 snapshot 临时分组。
- `groupId > 0` 表示最终 DB 分组。
- 业务层不需要区分读取路径，SDK API 已兼容。
- 用户正在选择删除项时，刷新后应按 URI 与最新 active assets 求交集，避免选择已不存在资源。

## 8. 删除流程

系统删除确认前：

```kotlin
val markedUris = client.markDeletePending(selectedUris)
```

用户确认删除：

```kotlin
client.finalizeDelete(markedUris)
```

用户取消删除：

```kotlin
client.restoreDeletePending(markedUris)
```

冷启动恢复：

```kotlin
client.recoverStaleDeletePending()
```

关键原因：

- `markDeletePending()` 会把 DB 资源标记为删除中，并递增 revision。
- 后台扫描旧 token 无法继续提交指纹和分组，避免删除资源复活。
- SDK 会同步从 progressive snapshot 移除删除中的资源。

## 9. 边界 Case

| 场景 | 推荐处理 |
| --- | --- |
| 首次扫描没有立即出现分组 | 正常；需要先产生 Similar/Duplicate candidate edge 才能发布 snapshot |
| 扫描中设备新增媒体 | 不要求本轮实时一致；下次主动重扫或重新进入后通过 MediaStore 对账更新 |
| 扫描中外部相册删除媒体 | 不要求本轮实时一致；下次 full/incremental scan 校验 |
| App 内删除资源 | 必须使用 `markDeletePending/finalizeDelete/restoreDeletePending` |
| Android 14 部分授权 | 不要把 MediaStore 未返回资源视为已删除 |
| 低内存设备 | 降低首页预览数，避免高频刷新；必要时降低 snapshot 上限或只展示分类摘要 |
| 详情页分组刷新 | 只在 `resultUpdated=true` 或扫描完成时刷新 |
| 进程被杀 | 重启后展示 DB 缓存；snapshot 是进程内临时数据，不应持久化 |

## 10. 验证计划

基础验证：

- 500、2k、1w、3w、5w、10w 媒体库扫描。
- 首次扫描和二次扫描耗时对比。
- 图片多、视频多、截图多、重复图多四类图库。
- Android 12、13、14、15 权限模式。

体验验证：

- 首页首次出现分组时间。
- 扫描中首页分类数量是否阶段增长。
- 扫描中进入详情页是否能看到与首页一致的第一组。
- 停留详情页时是否能随 `resultUpdated` 更新。
- 扫描完成后最终数量是否稳定切换。

一致性验证：

- 扫描中 App 内删除资源。
- 删除确认、取消、进程被杀恢复。
- 扫描中外部相册新增/删除后，下次 retry 是否更新。
- snapshot 分组和 final DB 分组数量允许阶段差异，但最终必须以 DB 为准。

性能验证：

- `load_fingerprint_bitmap`
- `build_visual_fingerprint`
- `bk_tree_visual_query`
- `calculate_video_fingerprint`
- `rebuild_similar_groups`
- `intermediate_group_publish`

重点观察扫描总耗时和 `resultUpdated` 刷新次数，避免 UI 体验优化重新拉高 DB rebuild 成本。

## 11. 接入清单

- 接入 `similar-scan-core`。
- 实现媒体权限和通知权限流程。
- 实现单实例扫描 Service/Worker。
- 进度状态区分普通进度和 `resultUpdated`。
- 首页实现 snapshot + DB fallback。
- 详情页实现当前分类 snapshot + DB fallback。
- 分组详情使用 `loadSimilarGroupAssets()` 分页。
- 删除流程接入 pending/finalize/restore。
- 冷启动恢复 stale delete pending。
- 扫描完成后统一读取 final DB result。

完成以上步骤后，其他媒体扫描项目可以复用当前 demo 的扫描性能收益和阶段性展示体验，同时保留最终 DB 分组的一致性。
