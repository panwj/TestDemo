# 异步扫描与用户操作一致性方案

## 1. 目标

扫描在前台服务中持续执行，首页、分类详情和大图预览实时展示已产生结果。同时允许
用户选择和删除已展示资源，不因扫描写库、删除确认或页面刷新产生崩溃、资源复活、
孤立指纹或过期页面。

相似和重复判断条件不在本次并发改造中变更，继续使用：

- Kotlin `DHash.kt` 双线性采样实现。
- RGB 8x3 colorHash。
- 当前代码中的分媒体类型阈值。
- 竞品 duplicateReference 重复归类规则。

## 2. 唯一数据源

SQLite 是唯一可信数据源。页面之间只传递：

```text
ProductCategoryType
groupId
assetUri
```

不再通过进程内 Map 传递完整 `ProductCategory` 或 `SimilarGroup` 快照。

首页、详情页和预览页在以下时机重新从数据库读取：

- 页面进入或恢复。
- 每个扫描批次完成。
- 扫描完成。
- 删除确认完成或取消。

## 3. 资源状态

`media_asset.state`：

| 状态 | 含义 |
| --- | --- |
| ACTIVE | 可扫描、展示和操作 |
| DELETE_PENDING | 正在等待 Android 系统删除确认 |
| DELETED | 已删除 |
| UNAVAILABLE | 暂时不可读取 |

所有展示查询、相似候选查询和分组写入只处理 `ACTIVE`。

## 4. 乐观版本控制

每个资源包含 `revision`。扫描开始处理资源时获取：

```text
AssetScanToken(assetId, revision)
```

Bitmap 解码、Hash 和质量评分在事务外执行。写入指纹前检查：

```sql
state = ACTIVE AND revision = token.revision
```

用户发起删除时：

```text
state -> DELETE_PENDING
revision -> revision + 1
```

因此正在运行的旧扫描令牌会失效，计算结果被安全丢弃。

## 5. 两阶段删除

### 阶段一：请求确认

```text
用户点击删除
-> 数据库标记 DELETE_PENDING
-> UI 立即隐藏资源
-> 调用 MediaStore.createDeleteRequest
```

### 阶段二：处理结果

用户确认：

```text
删除 media_asset
-> 外键级联删除 fingerprint、similar_group_item
-> 清理空组
-> 页面重新读取数据库
```

用户取消或系统请求失败：

```text
DELETE_PENDING -> ACTIVE
revision + 1
fingerprint_status -> PENDING
-> 页面恢复资源
```

待删除 URI 会写入 Activity saved state，页面重建后仍能正确处理系统回调。

删除操作同时记录进程会话 ID：

- 同一进程从扫描通知重新打开主页：不恢复仍在等待确认的资源。
- App 进程重启：识别为上个进程遗留操作并恢复，再由 MediaStore 扫描校准。
- 同一进程超过 10 分钟仍未收到结果：按悬挂操作恢复。

## 6. 外键约束

数据库启用：

```text
fingerprint.asset_id -> media_asset.id ON DELETE CASCADE
similar_group_item.asset_id -> media_asset.id ON DELETE CASCADE
similar_group_item.group_id -> similar_group.id ON DELETE CASCADE
```

避免并发删除后残留孤立指纹和孤立组成员。

## 7. 实时页面更新

详情页保存的选择状态是 URI 集合，而不是列表位置：

```text
selectedUris ∩ 当前 ACTIVE 资源
```

扫描新增结果会立即出现，但不会自动改变用户已经进行的选择。首次进入相似分类时才
应用竞品默认策略：保留 Best，选中组内其余资源。

预览页使用 `categoryType + groupId + currentUri` 定位资源。资源被删除或移组后：

- 当前 URI 仍存在：保持当前资源。
- 当前 URI 消失：显示相邻位置资源。
- 整组消失：安全关闭预览。

## 8. 并发结果

| 场景 | 处理结果 |
| --- | --- |
| 扫描计算中用户删除 | revision 变化，扫描提交失败 |
| 扫描准备写分组时用户删除 | 分组写入检查 ACTIVE，拒绝加入 |
| 删除确认取消 | 恢复 ACTIVE，等待增量扫描校准 |
| 删除确认时 Activity 重建 | saved state 恢复待删除 URI |
| 删除确认时进程终止 | 下次全新进入主页恢复悬挂状态 |
| BK-Tree 包含已删除 ID | 回库候选查询过滤 ACTIVE |
| 全量扫描结束清理 | DELETE_PENDING 不参与缺失资源清理 |

## 9. 当前执行模型

- `MediaScanService`：单扫描任务，避免多个扫描会话同时写入。
- 指纹计算：后台线程。
- SQLite 修改：短事务。
- UI：扫描批次广播后重新读取数据库。

该方案支持扫描持续运行时浏览、选择和删除结果，同时保证用户操作优先，最终页面以
数据库和 MediaStore 的最新状态为准。
