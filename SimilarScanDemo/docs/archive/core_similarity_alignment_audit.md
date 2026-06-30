# Demo 与竞品核心相似扫描逻辑审计

> 历史审计：本文记录 v14/v15 附近的对齐结论，部分阈值和 UI/性能实现已被最新代码覆盖。
> 当前实现请以 `docs/current_technical_solution.md` 为准。

## 1. 审计结论

当前 v14 Demo 已按最新反编译证据修正视频多帧逻辑，并删除 native so 依赖，
但还不能在没有 v14 真机结果的情况下
宣称扫描结果完全一致。

已明确对齐的部分：

- Kotlin dHash 使用根目录 `DHash.kt` 的双线性采样实现。
- RGB `8 x 3` colorHash。
- 图片、截图、视频和录屏的分类型阈值。
- 视频 7～13 帧抽取和至少两次跨帧命中。
- 创建时间升序、最早资源为锚点的分组规则。
- 图片/视频 MediaStore 枚举范围。
- Duplicate 引用字段和 Duplicate 优先级。
- 截图、录屏仅使用 `DISPLAY_NAME` 分类。

仍需真机确认的部分：

- 不同 Android 版本的 `MediaMetadataRetriever` 对同一时间点返回的关键帧是否一致。
- Kotlin dHash 与竞品 native 在目标设备样本上的逐项 Hash 差异。

## 2. 指纹和 Hash

### 2.1 dHash

Demo 仅保留 Kotlin dHash，不再打包 `libdhash.so`，也没有 JNI 桥接或运行时切换。
Kotlin 实现直接在原图上对 9x8 网格进行双线性灰度采样，再按从左到右、从上到下
的顺序写入 64 个比较位。

该实现用于复现项目根目录 `DHash.kt`，但仍需通过同一批 Bitmap 的逐项 Hash 导出，
确认其与竞品 native 输出是否完全一致。

### 2.2 colorHash

两边都执行：

```text
ARGB_8888 Bitmap
-> 分别统计 R/G/B 的 0..255 直方图
-> 每 32 个值合成一桶
-> 形成 Double[8][3]
-> 每桶除以 pixelCount / 16
```

相似判断中的颜色距离均为所有桶绝对差求和后截断为 Long。

## 3. 指纹输入

竞品 `qh.k.f(CGAsset)` 明确执行：

```text
MediaStore.Images URI + media id
-> loadThumbnail(1024, 1024)
-> Images URI input stream
-> 按媒体类型获取文件路径并尝试 BitmapFactory.decodeFile
```

该路径用于图片类指纹。继续追踪 `qh.m -> a4.a -> a4.b -> uh.a` 后确认，视频和录屏
另有专用流程：

```text
MediaMetadataRetriever
-> 读取 METADATA_KEY_DURATION
-> 按 ExtractionRule 抽取 7～13 帧
-> getScaledFrameAtTime(timeUs, OPTION_CLOSEST_SYNC, 9, 8)
-> 每帧计算 dHash + colorHash
```

v12 优先使用竞品同样的真实文件路径；Android 分区存储无法返回 DATA 时回退媒体 URI。

UI 预览不使用这条兼容路径，始终加载真实媒体 URI，不影响图片查看。

## 4. 相似阈值

### 普通照片、视频、录屏

```text
dHash 0..4             -> 直接相似
dHash 4..10 + color 0..7
dHash 10..17 + color 0..5
dHash >= 18            -> 不相似
```

### 截图

```text
dHash 0..2             -> 直接相似
dHash 2..10 + color 0..5
dHash 10..15 + color 0..2
dHash >= 16            -> 不相似
```

阈值区间、最大距离的排除关系以及颜色距离取整方式均与竞品一致。

## 5. 分组

竞品不是按相似关系的连通分量合并，而是：

```text
按创建时间升序排序
-> 取最早资源作为锚点
-> 找出所有与锚点直接相似的资源
-> 形成一组并从待处理集合移除
-> 继续下一个锚点
```

Demo 完成阶段使用相同规则。图片每个资源索引一个 dHash；视频每个有效帧都进入
BK-Tree 召回，最终仍执行完整跨帧比较，不改变竞品精判结果。

视频比较不按帧序号或时间点对齐，而是两层循环比较全部帧对。至少命中两次即相似；
任一侧不足两帧时退化为至少命中一次。该行为与竞品 `bn.k` 一致。

当前数据库使用 `created_at ASC, media_store_id DESC`。竞品排序只比较创建时间，并依赖
MediaStore 原列表在时间相同时的稳定顺序。当前测试资源没有相同 `created_at`，
因此这一差异没有影响；其他设备出现同时间资源时，组边界可能存在轻微差别。

## 6. Duplicate

竞品图片/截图 Duplicate 引用为：

```text
媒体类型 + 宽 + 高 + imageHash + false + 文件大小
```

其中 edited 固定为 `false`。v11 起已停止使用 generation 推导 edited。

Demo 额外计算 SHA-256 作为诊断证据，但 SHA-256 不参与竞品式 Duplicate 判定。
Duplicate 和 Similar 在数据库及展示层保持互斥。

## 7. 媒体分类

MediaStore 只提供图片和视频基础集合。竞品在启用分类开关时，再使用文件名区分：

- 普通图片 / 截图。
- 普通视频 / 录屏。

v11 起只把 `DISPLAY_NAME` 传入竞品关键词规则，不再加入 bucket、relative path 或
Demo 自定义本地化关键词。

## 8. 不影响相似数量的扩展

以下功能属于 Demo 产品增强，不参与相似组判定：

- SHA-256 诊断。
- 清晰度、曝光、分辨率等 Best 质量评分。
- SQLite 缓存、BK-Tree、generation 增量扫描。
- 删除乐观锁和异步扫描一致性。

这些实现可以与竞品内部架构不同，只要不改变最终输入 Hash、阈值和锚点顺序。

## 9. 是否还需要优化

需要，但下一步不应继续修改阈值或人为把视频全部放入 Similar。

建议按以下顺序验证：

1. 使用 v14 在同一台设备执行完整扫描。
2. 导出 Kotlin dHash，与竞品同一资源的 Hash 逐项比较。
3. 再次导出四张数据库表。
4. 检查截图是否接近竞品 `114 / 24 组`。
5. 检查视频是否从 v9 的 `19 Similar + 12 Other` 向 `31 + 0` 收敛。
6. 若视频仍不一致，导出每个视频的帧数、时间点与逐帧 Hash，不要继续放宽阈值。

只有 v12 真机数据能确认多帧抽取在目标设备上是否与竞品一致。
