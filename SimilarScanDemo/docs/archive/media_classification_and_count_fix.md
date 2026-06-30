# 媒体分类与扫描计数修正说明

## 1. CSV 结论

本次分析使用根目录导出的四张表：

- `similar_scan_db-media_asset.csv`
- `similar_scan_db-fingerprint.csv`
- `similar_scan_db-similar_group.csv`
- `similar_scan_db-similar_group_item.csv`

`media_asset` 共 214 条。分类合计为 218 的直接原因是 4 张截图同时存在于
`SIMILAR_SCREENSHOT_g12` 和 `DUPLICATE_SCREENSHOT_g18`，因此被重复累计。

修正后采用以下优先级：

```text
Duplicate > Similar > Other
```

资源进入 Duplicate 时，数据库会事务性删除它在同媒体类型 Similar 组中的关系；
后续增量扫描也禁止把 Duplicate 资源重新加入 Similar。展示层另有一次防御去重，
用于兼容尚未完成全量重扫的旧数据库。

## 2. MediaClassifier 是否应当存在

应当存在，但它只负责 MediaStore 基础类型之外的二次分类。

Android MediaStore 能可靠提供的是图片或视频集合、MIME、目录、文件名等字段，
并不提供标准的“截图”和“录屏”媒体类型。因此正确流程是：

```text
MediaStore.Images -> PHOTO / SCREENSHOT
MediaStore.Video -> VIDEO / SCREEN_RECORDING
```

竞品也不是直接读取一个系统“录屏类型”。反编译代码中：

- `qh.k.b(false/true)` 分别查询普通图片和截图。
- `qh.k.c(false/true)` 分别查询普通视频和录屏。
- `android.support.v4.media.session.a.J(String)` 使用文件名关键词识别录屏。

Demo 的 `MediaClassifier` 已恢复为竞品同源关键词，包括
`screen_recording`、`screen-recording`、`screenrecord`、`screen_record`、
`screen-record`、`recording`、`capture`、`mirror` 和 `cast`。

## 3. 相似截图分组

旧实现会在发现跨组相似边时合并两个完整分组。该方式会产生单链放大：

```text
A 与 B 相似，B 与 C 相似
-> 旧实现把 A、B、C 合成一组
-> 即使 A 与 C 不满足阈值，也会被展示为同一组
```

新实现不再合并两个已经存在的稳定组。扫描中的新资源可以加入一个已有组，
但不会作为桥梁把两个组整体吞并，降低截图大组和分组边界失真的概率。

## 4. 相似视频

CSV 中 Demo 的 31 个视频有 28 个进入相似组，3 个进入 Other Videos：

- `screen-20260622-154549.mp4`：436,512,117 字节，被 400MB 条件直接标记 FAILED。
- `PXL_20260617_095419209.TS.mp4`：多帧规则未达到至少两帧命中。
- `screen-20250219-193354.mp4`：多帧规则未达到至少两帧命中。

> v12 更正：本节是早期未完整追踪调用链时的结论。继续分析
> `qh.m -> a4.a -> a4.b -> uh.a -> bn.k` 后确认，竞品视频实际抽取 7～13 帧，
> 并在所有帧之间交叉比较，至少命中两次。v12 已恢复并按竞品字节码实现多帧方案；
> 仍不使用 400MB 条件直接拒扫。

## 5. 升级与验证

数据库版本已提升到 v12。安装新版 APK 后旧扫描缓存会重建，避免 v11 单帧视频指纹、
旧媒体分类和重复分组继续影响结果。

## 6. 第二轮真机差异修正

第二轮真机结果为：

| 分类 | Demo | 竞品 |
| --- | ---: | ---: |
| 相似截图 | 129 / 19 组 | 114 / 24 组 |
| 其他截图 | 42 | 57 |
| 相似视频 | 18 | 31 |
| 其他视频 | 13 | 0 |

反编译确认了两个根因：

1. Demo 的媒体阈值映射相反。竞品普通照片、视频和录屏使用 `0..4 / <18`
   阈值，截图使用更严格的 `0..2 / <16` 阈值。
2. 竞品先按创建时间升序排序，以最早资源作为锚点，只收纳与锚点直接相似的
   资源。Demo 原来的增量关系合并仍可能产生传递式大组。

当前实现已修正阈值映射，并在扫描完成阶段使用 BK-Tree 候选召回后执行竞品锚点
成组。扫描过程中仍保留增量结果，完成后切换为确定性最终结果。

## 7. 最新 v9 指纹复核

最新导出表包含 214 个媒体资源和 214 条指纹，说明没有指纹失败。结果为：

| 分类 | v9 Demo | 竞品 |
| --- | ---: | ---: |
| 相似截图 | 116 / 25 组 | 114 / 24 组 |
| 其他截图 | 55 | 57 |
| 相似视频 | 19 / 5 组 | 31 |
| 其他视频 | 12 | 0 |

相同与相似不存在交叉，截图和视频分类也分别保持总数守恒。截图只剩一个两项组
的差异，不应通过修改竞品阈值强行删除，否则会影响其他设备的正确结果。

> v12 更正：v9/v10 阶段把图片缩略图调用链误认为视频最终指纹路径。完整调用链显示，
> 视频通过 `MediaMetadataRetriever` 抽取 7～13 帧，并执行跨帧匹配。v12 已替换
> 单缩略图方案；本段历史数据仅用于说明差异演进，不再代表当前实现。

进一步静态审查还修正了两处明确差异：

- 截图和录屏只使用 MediaStore `DISPLAY_NAME` 分类，不再把 bucket、relative path
  或 Demo 自定义本地化关键词加入竞品规则。
- 竞品 `duplicateReference` 中的 edited 字段固定为 `false`。Demo 不再使用
  `GENERATION_MODIFIED > GENERATION_ADDED` 推导编辑状态。

建议在同一设备重新导出四张表，并验证：

1. `media_asset` 活跃资源数等于产品分类去重后的合计数。
2. 任一 asset 不同时属于同类型 Duplicate 和 Similar。
3. 超过 400MB 且可抽取视频帧的资源不再为 FAILED。
4. Similar Videos 与 Other Videos 的合计等于普通 VIDEO 数量。
5. Similar Screen Recordings 与 Other Screen Recordings 的合计等于录屏数量。
