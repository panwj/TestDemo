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

## 3. 当前实现位置

- SDK 权限判断：`similar-scan-core/src/main/java/com/clean/similarscan/permission/SimilarScanPermissionChecker.kt`
- SDK 权限状态：`similar-scan-core/src/main/java/com/clean/similarscan/permission/MediaAccessLevel.kt`
- Demo 权限映射：`app/src/main/java/com/example/similarscandemo/permission/MediaPermissionHelper.kt`
- Demo 首页业务提示：`app/src/main/java/com/example/similarscandemo/MainActivity.kt`
- MediaStore 枚举：`similar-scan-core/src/main/java/com/clean/similarscan/internal/scanner/MediaStoreRepository.kt`
