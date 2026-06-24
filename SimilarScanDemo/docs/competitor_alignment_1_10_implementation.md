# 竞品对齐 1～10 点实现说明

## 1. 改造范围

本轮完成可在 Demo 内落地的 10 项产品级优化。真实订阅计费和多品牌真机结果验收不在本轮范围内；订阅页仍是可区分 UI，不发起支付。

## 2. 已实现项目

### 2.1 完整媒体授权与部分授权保护

- target/compile SDK 保持 36。
- Android 13+ 分别使用图片、视频权限。
- Android 14+ 兼容用户选择部分照片。
- 部分授权时扫描当前可见资源，但不会把不可见资源误判为已删除。
- 用户从部分授权升级为完整授权后，下一次扫描会补齐整个媒体库。

### 2.2 竞品分类规则

首页保持竞品录屏中的 10 个纵向分类：

- Similar
- Duplicates
- Similar Screenshots
- Similar Videos
- Other Screenshots
- Chat Photos
- Similar Screen Rec
- Other Screen Rec
- Other Videos
- Other

截图、录屏和聊天图片会结合文件名、相册名、相对路径和常见厂商/应用关键词识别。

### 2.3 完全重复图片

当前版本按竞品 `duplicateReference` 归类重复图片：

```text
媒体类型 + 宽度 + 高度 + imageHash + 编辑状态 + 文件大小
```

SHA-256 继续按需计算并缓存，用于区分字节完全相同与竞品组合引用相同。重复候选进入
Duplicates，不再同时进入 Similar。

### 2.4 相似图片召回

候选召回使用：

```text
同媒体类型
+ BK-Tree 汉明距离召回
```

候选召回后再使用竞品阈值的 `dHash + colorHash` 精判。BK-Tree 不使用时间和宽高比
硬过滤，能够完整返回阈值范围内的 64 位 hash，同时避免全库两两比较。

### 2.5 Best 推荐保留

每个相似组在 64x64 缩略图上计算本地质量分，综合：

- 边缘能量/清晰度
- 平均曝光
- 高光和暗部裁切比例
- 分辨率
- 收藏状态
- 编辑状态

质量最高项标记为 Best 并默认保留，其他项默认选中等待清理。

### 2.6 相似视频与录屏

- 使用 `MediaMetadataRetriever` 按竞品规则抽取 7～13 帧。
- 每帧缩放为 9x8 ARGB_8888，再生成 `dHash + colorHash` 组合指纹。
- 在两段视频的全部帧之间交叉比较，至少命中两次。
- 不因文件超过 400MB 而跳过扫描。

### 2.7 真正的增量扫描

首次运行、数据库重建或距离上次完整扫描超过 24 小时时执行全量扫描。其他情况使用：

```text
MediaStore.GENERATION_MODIFIED
```

只读取新增或发生变化的图片、视频。扫描游标保存在 `ScanStateStore`，扫描失败不会提前推进游标。
同时保存 MediaStore volume version；系统媒体库版本变化时自动退回全量扫描。

### 2.8 后台扫描和及时展示

扫描由 `MediaScanService` 前台服务执行：

- App 退到后台后扫描继续。
- 常驻低优先级进度通知。
- 每批 500 条完成后广播进度。
- 首页按批读取 SQLite 已产生结果，不等待全部完成。
- App 在前台时监听 MediaStore，新拍照片/新视频会触发防抖后的增量扫描。

### 2.9 删除和数据库一致性

- 重扫资源前移除旧分组关系。
- 每批重新生成指纹和分组。
- 完整授权的全量扫描结束后，通过扫描 token 清理 MediaStore 已不存在的资源、指纹和分组。
- 部分授权绝不执行缺失资源清理。
- 用户确认删除后立即同步清理本地数据库。

### 2.10 结果查看与清理交互

- 分类详情保持两列网格。
- 相似类按组展示。
- 单项可进入大图预览，并左右切换上一项/下一项。
- 支持 Best、最新、最旧、最大、最小排序。
- 支持默认推荐选择、单项选择、全选/取消全选。
- 删除按钮实时显示数量和预计释放容量。
- Android 11+ 使用系统 `MediaStore.createDeleteRequest` 确认删除。

## 3. 10 万资源扫描流程

```text
启动 App
-> 立即展示 SQLite 缓存结果
-> 判断完整/部分授权
-> 判断全量或 generation 增量模式
-> 前台服务按 500 条枚举
-> 生成质量分和视觉指纹
-> duplicateReference 归类重复项并按需计算 SHA-256
-> BK-Tree 召回相似候选
-> 阈值精判并持续写入分组
-> UI 每批刷新
-> 完整全量扫描时同步删除记录
-> 保存 generation 游标
```

扫描不会把 10 万条资源和所有 Bitmap 一次加载进内存。首次完整扫描仍需处理全部资源，但结果会持续出现；之后通常只处理变化资源。

## 4. 主要代码位置

```text
permission/MediaPermissionHelper.kt        媒体授权判断
scanner/MediaStoreRepository.kt            分批和 generation 查询
scanner/ScanStateStore.kt                  增量扫描游标
scanner/SimilarMediaScanner.kt             扫描编排
service/MediaScanService.kt                 前台后台扫描
similarity/MediaQualityAnalyzer.kt          Best 质量评分
similarity/ContentDigestCalculator.kt       SHA-256
similarity/VideoFingerprint.kt              视频缩略图指纹兼容模型
database/ScanDatabase.kt                    缓存、索引、分组和同步
GroupDetailActivity.kt                      排序、选择和删除
```

## 5. 当前验证边界

工程已通过 `assembleDebug`。算法结果仍需要在不同品牌 Android 设备、超大媒体库、HDR/HEIF/超长视频和部分授权切换场景进行真机数据验收，之后才能确认与竞品的最终召回率和误判率完全一致。
