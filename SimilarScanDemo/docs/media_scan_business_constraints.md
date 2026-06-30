# 媒体扫描业务约束与 Case

本文档记录 SimilarScanDemo 在不同输入条件下的业务处理约束。后续如果补充更多媒体扫描场景，例如系统相册删除、部分授权变更、媒体库异常、视频封面差异等，也统一追加到本文档。

## 1. 权限场景和处理 Case

### Case 1：Android 12 及以下，授予完整媒体权限

输入条件：

- `READ_EXTERNAL_STORAGE = granted`

业务状态：

- `MediaAccessLevel.LEGACY_FULL`
- 可扫描图片、截图、视频、录屏。
- 具备完整媒体访问能力。

处理规则：

- 允许全量扫描。
- 允许在全量扫描完成后清理 MediaStore 未返回的旧资源。
- 首页展示完整扫描结果。

### Case 2：Android 12 及以下，拒绝媒体权限

输入条件：

- `READ_EXTERNAL_STORAGE = denied`

业务状态：

- `MediaAccessLevel.NONE`

处理规则：

- 不启动扫描。
- 首页提示用户授权照片和视频访问。

### Case 3：Android 13，授予图片和视频权限

输入条件：

- `READ_MEDIA_IMAGES = granted`
- `READ_MEDIA_VIDEO = granted`

业务状态：

- `MediaAccessLevel.FULL_VISUAL`
- 可扫描图片、截图、视频、录屏。
- 具备完整媒体访问能力。

处理规则：

- 允许全量扫描。
- 允许清理 MediaStore 未返回的旧资源。
- 首页提示图库访问已授权。

### Case 4：Android 13，只授予图片权限

输入条件：

- `READ_MEDIA_IMAGES = granted`
- `READ_MEDIA_VIDEO = denied`

业务状态：

- `MediaAccessLevel.IMAGES_ONLY`
- 只可扫描图片和截图。
- 不具备完整媒体访问能力。

处理规则：

- 允许扫描图片和截图。
- 不扫描视频和录屏。
- 不允许把未返回的视频资源视为已删除。
- 首页提示“已授权照片访问，视频不会被扫描”。

### Case 5：Android 13，只授予视频权限

输入条件：

- `READ_MEDIA_IMAGES = denied`
- `READ_MEDIA_VIDEO = granted`

业务状态：

- `MediaAccessLevel.VIDEOS_ONLY`
- 只可扫描视频和录屏。
- 不具备完整媒体访问能力。

处理规则：

- 允许扫描视频和录屏。
- 不扫描图片和截图。
- 不允许把未返回的图片资源视为已删除。
- 首页提示“已授权视频访问，照片不会被扫描”。

### Case 6：Android 13，图片和视频权限都拒绝

输入条件：

- `READ_MEDIA_IMAGES = denied`
- `READ_MEDIA_VIDEO = denied`

业务状态：

- `MediaAccessLevel.NONE`

处理规则：

- 不启动扫描。
- 首页提示用户授权照片和视频访问。

### Case 7：Android 14+，授予全部图片和视频权限

输入条件：

- `READ_MEDIA_IMAGES = granted`
- `READ_MEDIA_VIDEO = granted`

业务状态：

- `MediaAccessLevel.FULL_VISUAL`
- 可扫描全部图片、截图、视频、录屏。
- 具备完整媒体访问能力。

处理规则：

- 允许全量扫描。
- 允许清理 MediaStore 未返回的旧资源。
- 新拍摄照片和新视频应能在后续扫描中被发现。

### Case 8：Android 14+，授予用户选择的媒体资源

输入条件：

- `READ_MEDIA_VISUAL_USER_SELECTED = granted`
- `READ_MEDIA_IMAGES / READ_MEDIA_VIDEO` 未同时完整授权。

业务状态：

- `MediaAccessLevel.PARTIAL_VISUAL`
- 只能扫描系统返回的用户已选择资源。
- 不具备完整媒体访问能力。

处理规则：

- 允许扫描当前可见的选中图片和视频。
- 不允许把未返回资源视为已删除。
- 刚拍的新照片或新视频可能不会自动进入扫描范围。
- 首页提示“仅扫描已选择的照片和视频”。

### Case 9：Android 14+，部分授权中只选择图片

输入条件：

- `READ_MEDIA_VISUAL_USER_SELECTED = granted`
- MediaStore 只返回用户选择的图片。

业务状态：

- `MediaAccessLevel.PARTIAL_VISUAL`

处理规则：

- 只扫描系统返回的选中图片。
- 视频分类可能为空，但不能把历史视频结果当作已删除。
- 首页保持部分授权提示。

### Case 10：Android 14+，部分授权中只选择视频

输入条件：

- `READ_MEDIA_VISUAL_USER_SELECTED = granted`
- MediaStore 只返回用户选择的视频。

业务状态：

- `MediaAccessLevel.PARTIAL_VISUAL`

处理规则：

- 只扫描系统返回的选中视频。
- 图片分类可能为空，但不能把历史图片结果当作已删除。
- 首页保持部分授权提示。

### Case 11：用户从部分授权升级为完整授权

输入条件：

- 之前为 `PARTIAL_VISUAL`、`IMAGES_ONLY` 或 `VIDEOS_ONLY`。
- 用户后续在系统权限页改为完整图片和视频访问。

业务状态：

- `MediaAccessLevel.FULL_VISUAL`

处理规则：

- 后续扫描可以补齐完整媒体库。
- 完整全量扫描完成后，才允许清理未出现旧资源。

### Case 12：用户从完整授权降级为部分授权或单类型授权

输入条件：

- 之前为 `FULL_VISUAL` 或 `LEGACY_FULL`。
- 后续降级为 `PARTIAL_VISUAL`、`IMAGES_ONLY` 或 `VIDEOS_ONLY`。

业务状态：

- 不再具备完整媒体访问能力。

处理规则：

- 只能扫描当前可见范围。
- 不允许把 MediaStore 当前不可见资源当作已删除。
- 首页需要提示当前结果可能不是完整图库结果。

### Case 13：用户连续拒绝权限或勾选不再提醒

输入条件：

- 用户已经发起过媒体权限请求。
- 当前没有任何图片或视频读取能力。
- 系统不再展示媒体权限弹窗。

业务状态：

- `MediaAccessLevel.NONE`
- `MediaPermissionHelper.shouldOpenAppSettings() = true`

处理规则：

- 扫描按钮显示为“Open Settings”。
- 再次点击扫描按钮时，不再调用系统权限弹窗，而是跳转系统 App 详情页。
- 用户在系统设置中授予图片或视频权限后，返回 App 自动继续权限链路并触发扫描。
- 用户返回 App 但仍未授权时，不启动扫描，保留授权提示。

## 2. 边界约束

- `hasPermission = canReadImages || canReadVideos`，只要有图片或视频任一读取能力即可启动扫描。
- `hasFullVisualAccess = true` 只在完整图片和完整视频都可读时成立。
- 只有 `hasFullVisualAccess = true` 的全量扫描，才允许执行旧资源清理。
- 单类型授权下，扫描结果只代表当前授权类型，不代表完整图库。
- Android 14+ 部分授权下，MediaStore 未返回资源不代表资源已删除。
- 通知权限不影响媒体可见范围，只影响前台服务通知能力。
- 媒体权限被永久拒绝时，Demo 层负责跳转系统设置；SDK 不直接持有 Activity，也不主动打开设置页。

## 3. 扫描中断与干扰场景 Case

本章节记录扫描任务运行期间，用户操作、系统媒体库变化、App 进程异常等干扰场景下的当前处理规则。

当前版本保持现有扫描策略：

- 扫描中系统媒体库发生变化时，不额外增加本轮结束后的自动补扫。
- App 强杀或崩溃后，不做自动恢复扫描。
- 用户再次进入 App 后，可以通过现有扫描入口重新发起扫描。
- 重新发起扫描时，数据库中已成功落库的资源和指纹可复用，未完成或状态为 `PENDING` 的资源会重新处理。

### Case 14：扫描过程中用户在 App 内删除资源

输入条件：

- 扫描任务正在执行。
- 用户在首页、分类详情或预览页选择删除已展示资源。

业务状态：

- 待删除资源进入 `DELETE_PENDING`。
- 对应资源的 `revision` 会递增。
- 正在计算中的旧扫描任务可能仍持有旧的资源快照。

处理规则：

- 删除操作优先于扫描结果写入。
- 扫描线程提交指纹、分组或状态时，需要携带扫描开始时拿到的 `revision`。
- 如果资源 `revision` 已变化，旧扫描结果提交失败，避免被删除资源重新出现在结果中。
- 分组写入前需要再次确认资源仍为可展示状态。
- 用户取消系统删除确认时，资源恢复为可扫描状态，等待后续扫描校准结果。

当前结论：

- 已处理。
- 该场景属于当前异步扫描和用户操作并行时的主要安全路径。

### Case 15：扫描过程中用户在系统相册删除资源

输入条件：

- 扫描任务正在执行。
- 用户切到系统相册或其他 App 删除图片、视频、截图或录屏。

业务状态：

- 如果删除发生在 MediaStore 枚举之前，本轮扫描可能读不到该资源。
- 如果删除发生在资源已经被本轮扫描标记为 seen 之后，本轮结束时可能暂时保留旧记录。
- 完整授权下，全量扫描完成后才允许清理本轮 MediaStore 未返回的旧资源。
- 部分授权或单类型授权下，不能把 MediaStore 未返回资源直接视为删除。

处理规则：

- 当前版本不在扫描中断本轮任务。
- 当前版本不在本轮扫描完成后自动追加一次补扫。
- 如果本轮没有及时清理，旧记录会在后续全量扫描或用户再次触发扫描时校准。
- 资源读取失败、缩略图获取失败或文件不可访问时，单个资源失败不应导致整个扫描崩溃。

当前结论：

- 部分处理。
- 数据安全优先，不会因为部分授权或短暂不可见误删记录。
- 实时一致性不是强保证，系统外部删除可能延迟到下一次扫描才完全反映。

### Case 16：扫描过程中系统相册新增资源

输入条件：

- 扫描任务正在执行。
- 用户拍摄新照片、新视频，或其他 App 写入新的图片、视频资源。

业务状态：

- 新资源如果已经进入当前 MediaStore 查询范围，本轮可能被扫描到。
- 新资源如果发生在当前查询游标之后，本轮可能不会被扫描到。
- API 30+ 下，后续增量扫描可基于 `GENERATION_MODIFIED` 发现新增或变更资源。

处理规则：

- 当前版本不在扫描中启动第二个并发扫描任务。
- 当前版本不记录“扫描中媒体库变化后必须补扫”的强制标记。
- 新增资源如果本轮没有出现，会在下一次手动扫描、后续自动触发扫描或下一次全量扫描中处理。

当前结论：

- 部分处理。
- 不会破坏当前扫描稳定性，但新增资源的展示不是本轮强实时保证。

### Case 17：扫描过程中 App 退到后台

输入条件：

- 用户启动扫描后将 App 切到后台。
- 媒体权限仍然有效。

业务状态：

- 扫描由前台服务承载。
- App 退后台后，扫描任务可以继续执行。
- UI 不可见时不做页面刷新。

处理规则：

- 前台服务继续扫描。
- 回到 App 后读取数据库中的最新结果刷新页面。
- 如果扫描已完成，首页展示最终分组结果。
- 如果扫描仍在进行，首页继续展示扫描中状态和已落库结果。

当前结论：

- 已处理。
- 后台期间系统媒体库发生变化时，当前版本不保证立即记录并补扫，仍以现有扫描入口和后续扫描校准为准。

### Case 18：扫描过程中 App 被强杀或进程崩溃

输入条件：

- 扫描任务正在执行。
- App 进程被系统杀死、用户强杀，或发生未捕获异常导致进程退出。

业务状态：

- 前台服务停止。
- 当前扫描任务中断。
- 已提交到 SQLite 的资源、指纹、状态和分组保留。
- 未提交完成的数据库事务由 SQLite 回滚。
- 扫描完成 checkpoint 不会推进到本轮未完成状态。

处理规则：

- 当前版本不自动重启扫描服务。
- 用户再次进入 App 后，首页先展示已缓存结果。
- 用户重新点击扫描后，会重新发起一次扫描任务。
- 新扫描会复用已完成且算法版本、资源签名仍有效的指纹。
- 上次中断时未完成、失败或仍为 `PENDING` 的资源会重新计算。

当前结论：

- 数据层具备恢复能力。
- 业务层不是精确断点续扫，也不是自动恢复扫描。
- 更准确的描述是：重新发起扫描任务，并复用已完成结果。

### Case 19：扫描过程中权限被系统撤销或用户修改授权范围

输入条件：

- 扫描任务正在执行。
- 用户进入系统设置修改媒体权限。
- 或系统因权限、存储状态变化导致资源读取失败。

业务状态：

- MediaStore 查询、缩略图读取或文件访问可能失败。
- 当前可见资源集合可能从完整图库变成部分图库，或从部分图库变成无权限。

处理规则：

- 单个资源读取失败时，不应导致整个 App 崩溃。
- 后续扫描重新读取当前权限状态。
- 如果不再具备任何图片或视频读取能力，Demo 不启动扫描，并展示授权提示。
- 如果只保留图片或视频单类型权限，后续扫描只覆盖当前授权类型。

当前结论：

- 部分处理。
- 当前版本以“下一次扫描读取最新权限状态”为准，不做扫描中实时权限切换重编排。

### Case 20：扫描过程中删除确认页中断

输入条件：

- 用户在 App 内发起删除。
- 系统删除确认页弹出后，用户切后台、杀进程或发生其他中断。

业务状态：

- 删除前资源已进入 `DELETE_PENDING`。
- App 可能没有收到删除确认结果。

处理规则：

- App 下次冷启动时检查是否存在遗留删除操作。
- 对于无法确认已经删除的资源，先恢复为可扫描状态。
- 后续扫描再根据 MediaStore 实际返回结果校准资源是否仍存在。

当前结论：

- 已处理。
- 该策略优先避免误删和错误隐藏资源。

## 4. 当前实现位置

- SDK 权限判断：`similar-scan-core/src/main/java/com/clean/similarscan/permission/SimilarScanPermissionChecker.kt`
- SDK 权限状态：`similar-scan-core/src/main/java/com/clean/similarscan/permission/MediaAccessLevel.kt`
- Demo 权限映射：`app/src/main/java/com/example/similarscandemo/permission/MediaPermissionHelper.kt`
- Demo 首页业务提示：`app/src/main/java/com/example/similarscandemo/MainActivity.kt`
- MediaStore 枚举：`similar-scan-core/src/main/java/com/clean/similarscan/internal/scanner/MediaStoreRepository.kt`
- 扫描编排：`similar-scan-core/src/main/java/com/clean/similarscan/internal/scanner/SimilarMediaScanner.kt`
- 前台扫描服务：`app/src/main/java/com/example/similarscandemo/service/MediaScanService.kt`
- 数据库状态与 revision 校验：`similar-scan-core/src/main/java/com/clean/similarscan/internal/database/ScanDatabase.kt`
