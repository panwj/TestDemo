# Similar Scan Demo

这是一个 Android Demo 工程，用 Kotlin + XML 实现本地相似媒体扫描能力，方案对齐竞品，并升级为更接近产品级的扫描架构：

```text
MediaStore 扫描
+ 缩略图/降采样解码
+ 64-bit dHash
+ RGB 8x3 colorHash
+ 分媒体类型阈值
+ SQLite 指纹缓存
+ BK-Tree 汉明距离候选召回
+ 竞品 duplicateReference + 按需 SHA-256 校验
+ MediaStore generation 增量扫描
+ 前台服务后台执行
+ progressive snapshot 扫描中阶段性展示结果
+ DB 中间发布低频兜底
```

## 技术配置

- Kotlin
- XML 布局
- 不使用 Compose UI
- `compileSdk = 36`
- `targetSdk = 36`
- `minSdk = 23`
- Android 原生权限与 MediaStore API
- SQLiteOpenHelper 本地缓存

## 支持功能

- 相似照片
- 竞品 duplicateReference 重复照片识别，并缓存 SHA-256 作为字节级验证证据
- 相似截图
- 相似视频（竞品 7～13 帧组合哈希）
- 相似录屏（竞品 7～13 帧组合哈希）
- 其他截图、其他视频、其他录屏、聊天图片和其他照片
- 本地缩略图展示
- 分批扫描与断点续扫基础能力
- 三页首次启动引导和独立订阅展示页
- 相似组 Best 推荐保留
- 清晰度、曝光、分辨率、收藏和编辑状态综合质量评分
- Best、日期和容量排序
- 前台服务扫描、进度通知和前台 MediaStore 变化监听
- 首页扫描中优先展示 progressive snapshot，未覆盖分类回退上次 DB 缓存
- 详情页扫描中优先读取当前分类 snapshot，停留页面内按 resultUpdated 阶段刷新
- 默认清理选择、全选和取消全选
- Android 系统媒体删除确认


## 权限方案

Android 13+：

- `READ_MEDIA_IMAGES`
- `READ_MEDIA_VIDEO`

Android 14+ / target 34+：

- 额外声明并请求 `READ_MEDIA_VISUAL_USER_SELECTED`，兼容用户选择部分媒体访问的场景。

Android 12 及以下：

- `READ_EXTERNAL_STORAGE`

## 相似算法

### dHash

```text
Bitmap
-> ARGB_8888
-> 9 x 8 网格双线性灰度采样
-> 每行横向相邻采样点比较
-> 64-bit imageHash
```

核心代码位于 `similarity/KotlinDHash.kt`。项目不再包含 native so、JNI 桥接或 Hash 后端切换入口。

### colorHash

```text
RGB 三通道
每通道 0..255 分成 8 桶
形成 Double[8][3]
```

### 普通照片阈值

| dHash 汉明距离 | colorHash 距离要求 | 结果 |
| --- | ---: | --- |
| 0..4 | 不需要 | 相似 |
| 4..10 | 0..7 | 相似 |
| 10..17 | 0..5 | 相似 |
| >=18 | 不相似 | 不相似 |

### 截图 / 普通视频 / 录屏阈值

| dHash 汉明距离 | colorHash 距离要求 | 结果 |
| --- | ---: | --- |
| 0..2 | 不需要 | 相似 |
| 2..10 | 0..5 | 相似 |
| 10..15 | 0..2 | 相似 |
| >=16 | 不相似 | 不相似 |

详细的 Bitmap 获取、视频抽帧、阈值和分组策略见：

```text
docs/current_technical_solution.md
```

## 构建

```bash
./gradlew assembleDebug
```

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

当前工程已在本机执行 `assembleDebug` 并通过。

## 目录结构

```text
app/
  src/main/java/com/example/similarscandemo/
    permission/   Demo 层权限申请、拒绝后跳转设置等业务交互
    service/      前台扫描服务，负责把 SDK 同步扫描放到后台线程执行
    ui/           首页、分类列表、详情页和预览页适配器
    util/         Demo 展示层格式化和辅助工具

similar-scan-core/
  src/main/java/com/clean/similarscan/
    api/          SDK 对外入口、请求参数、观察者和结果模型
    permission/   SDK 权限状态判断，只判断不弹权限框
    internal/     SDK 内部数据库、扫描编排、Bitmap 加载和相似算法

docs/
  current_technical_solution.md        当前 Demo 技术方案
  media_scan_business_constraints.md   当前业务 Case 和边界约束
  documentation_inventory.md           文档目录和归档说明
  archive/                             历史文档归档，不作为当前实现依据
```

## 产品级扫描策略

当前实现不再一次性把全部媒体加载到内存里，而是：

```text
MediaStore Cursor
-> 每 500 个资源组成一批
-> upsert 到 SQLite
-> 视觉资源计算 dHash/colorHash
-> 写入 fingerprint 表
-> 图片通过 BK-Tree 按汉明距离召回候选
-> 视频按当前多帧规则抽帧，并要求至少 2 个有效帧命中
-> 精确阈值过滤
-> 写入 candidate edge
-> progressive snapshot 发布扫描中临时结果
-> DB 中间发布低频物化兜底
-> 完成后 final rebuild 更新 similar_group / similar_group_item
-> UI 根据 resultUpdated 阶段刷新首页和详情
```

当前图片候选已升级为：

```text
SQLite 读取 assetId + 64-bit imageHash
-> 内存 BK-Tree 按汉明距离完整召回
-> 批量回库读取 colorHash
-> 使用当前分媒体类型阈值精判
```

Kotlin dHash 使用项目根目录 `DHash.kt` 的双线性网格采样实现，不再依赖
`Bitmap.createScaledBitmap()` 的系统插值结果。

BK-Tree 不使用拍摄时间或宽高比做硬过滤，因此不会在最终阈值判断前漏掉旋转、
裁剪、跨目录复制或较早创建的相似图片。

这样可以支持 10 万级资源的扫描思路：

- 资源分批处理，避免内存峰值。
- 指纹落库，支持断点续扫。
- 候选桶减少比较量，避免全量 O(n²)。
- UI 边扫边展示结果，不等最后一刻。
- 扫描中 UI 主路径使用内存 snapshot，不频繁触发 DB rebuild。
- DB 中间发布保留为兜底，避免页面重建或 snapshot 不可用时完全没有阶段性结果。
- 下次打开先展示旧结果，再继续增量更新。
- 常规重扫只查询 generation 发生变化的资源，每 24 小时执行一次完整同步。

视频扫描当前关键方式：

```text
BALANCED 模式
-> 优先读取系统视频缩略图并计算 dHash + colorHash
-> 再通过 MediaMetadataRetriever 补充少量时间点帧
-> 视频候选按时长桶、宽高比桶和算法版本收窄
-> 多帧 dHash + colorHash 精判
```

竞品扫描范围只包含图片和视频，因此对齐版本不请求音频权限，也不枚举音频资源。

## 文档入口

当前 Demo 技术方案：

```text
docs/current_technical_solution.md
```

SDK 能力和接入入口：

```text
similar-scan-core/README.md
similar-scan-core/docs/core-technical-design.md
similar-scan-core/docs/integration-guide.md
similar-scan-core/docs/reference-implementation-plan.md
```

业务边界和权限、删除、中断场景 Case：

```text
docs/media_scan_business_constraints.md
```

文档清理、活跃文档和历史归档说明：

```text
docs/documentation_inventory.md
docs/archive/
```

项目内 Codex skill：

```text
codex/skills/media-scan-analyzer/SKILL.md
```

该 skill 用于分析图库扫描核心逻辑、交互式确认不确定点，并输出一张信息图图片。

生成信息图审核稿（默认 SVG + PNG）：

```bash
python3 codex/skills/media-scan-analyzer/scripts/generate_media_scan_infographic.py --project .
```

审核通过后生成最终信息图（默认 SVG + PNG）：

```bash
python3 codex/skills/media-scan-analyzer/scripts/generate_media_scan_infographic.py --project . --final
```
