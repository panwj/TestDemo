# SimilarScanDemo v15 对齐改造与决策

## 1. 本次已完成

### 1.1 Kotlin dHash

继续使用项目根目录 `DHash.kt` 的 Kotlin 实现。

该文件来自对竞品 `libdhash.so` 的反汇编复现，因此正式 Demo 不再包含：

- native so
- JNI 桥接
- Hash 后端切换

后续差异分析默认 Kotlin dHash 是目标实现，不再把“未使用竞品 so”列为待改项。

### 1.2 删除音频能力

竞品相似扫描只包含：

- IMAGE
- SCREENSHOT
- VIDEO
- SCREEN_RECORDING

Demo 已删除：

- `MediaKind.AUDIO`
- MediaStore Audio 枚举
- Audio projection 和 Cursor 转换
- READ_MEDIA_AUDIO 辅助判断
- 音频候选、时长桶和大小桶逻辑
- 音频 UI badge 和预览分支
- 数据库 Audio 标题分支

数据库升级为 v15，旧数据库中的 AUDIO 枚举不会继续被读取。

### 1.3 视频路径失败行为

竞品视频指纹只使用 MediaStore `DATA` 真实路径：

```text
路径为空
或文件不存在
或文件不可读
-> 返回一个无效视频帧指纹
```

Demo 已删除 `content://` URI 回退，行为与竞品一致。

该改动可能使部分分区存储或云端视频无法生成有效帧，但这是严格复现竞品结果所需行为。

### 1.4 视频固定间隔采样

竞品固定间隔函数为：

```text
interval = max(1, interval)
time 从 0 开始
while time < duration:
    添加 time
    time += interval
如果最后一项不是 duration:
    追加 duration
```

Demo 已改为相同逻辑，不再按 7 或 13 对固定间隔结果做额外截断。

常见视频仍进入 13 帧等距分支；该修正主要影响极短视频和异常 duration。

### 1.5 API 30 以下抽帧

竞品 APK 已确认：

```text
minSdkVersion = 30
targetSdkVersion = 36
```

产品需要覆盖 Android 6.0，因此 Demo 保持：

```text
minSdk = 23
targetSdk = 36
```

抽帧分层处理：

```text
API 30+
-> BitmapParams.preferredConfig = ARGB_8888
-> getScaledFrameAtTime(timeUs, option=2, 9, 8, params)

API 27-29
-> getScaledFrameAtTime(timeUs, option=2, 9, 8)

API 23-26
-> getFrameAtTime(timeUs, option=2)
-> Bitmap.createScaledBitmap(9, 8, true)
```

API 30+ 与竞品一致；API 23-29 是竞品不支持系统上的产品兼容方案，无法宣称像素级
完全一致，但保持相同时间点、关键帧选项和 9x8 Hash 输入尺寸。

### 1.6 图片 DATA fallback

竞品图片指纹加载顺序：

```text
Images URI loadThumbnail(1024 x 1024)
-> Images URI input stream + inSampleSize
-> DATA 真实文件路径 + BitmapFactory.decodeFile
```

Demo 已补齐最后一层 DATA 路径 fallback。

---

## 2. 图片 DATA fallback 差异会造成什么问题

没有该 fallback 时，以下资源可能出现差异：

- MediaProvider 缩略图生成失败。
- `openInputStream()` 因厂商实现、迁移状态或数据库异常失败。
- URI 读取失败，但 `_data` 指向的本地文件仍存在且可读。
- 部分旧设备迁移到新系统后，MediaStore 记录和文件访问状态不完全一致。

竞品会继续通过文件路径解码并生成指纹；旧 Demo 会返回 null，并将指纹标记 FAILED。

结果可能表现为：

- 媒体总数存在，但指纹表缺少有效 Hash。
- 本应进入 Similar 或 Duplicates 的图片没有进入分组。
- Other Photos / Other Screenshots 数量偏大。
- 分类合计可能仍守恒，但分类内容与竞品不同。

该差异发生概率通常不高，但属于明确的输入链路差异，因此已经对齐。

---

## 3. 为什么 Demo 相似视频仍可能少于竞品

旧 CSV 显示：

```text
Demo: 19 Similar Videos + 12 Other Videos
竞品: 31 Similar Videos + 0 Other Videos
```

但 CSV 导出时间为 2026-06-23 17:01，早于以下改动：

- 视频多帧最终重建。
- 固定间隔追加末帧。
- 路径失败行为对齐。
- Kotlin dHash v15。
- 数据库 v15 全量重建。

因此旧 CSV 不能用于评价当前代码。

当前视频主逻辑已经对齐：

- 7/13 帧分支。
- 固定间隔函数。
- 时间点乘 1000 转微秒。
- 9x8 ARGB_8888 帧。
- option=2。
- 普通图片同款阈值。
- 所有帧交叉比较。
- 至少命中两次。
- VIDEO 和 SCREEN_RECORDING 分开成组。

如果 v15 真机全量重扫后仍小于竞品，应按以下顺序排查：

1. 比较每个视频的 DATA 路径是否都可读。
2. 导出竞品和 Demo 的采样时间点。
3. 比较每个时间点是否成功取得帧。
4. 比较每帧 9x8 像素摘要。
5. 比较逐帧 imageHash 和 colorHash。
6. 比较每个视频对实际命中了哪些帧对。

不建议放宽阈值。当前阈值已有竞品源码直接证据，盲目放宽会增加误判。

---

## 4. 同创建时间资源的锚点顺序

### 4.1 当前差异

竞品先按：

```text
date_added DESC
```

获取 MediaStore 列表，再执行稳定的：

```text
createdAt ASC
```

创建时间相同时，保留原 MediaStore 顺序。

Demo 最终重建使用：

```text
created_at ASC, media_store_id DESC
```

大多数设备上 MediaStore ID 与添加顺序接近，因此通常一致，但没有正式保证。

### 4.2 影响

锚点分组不是连通分量。若：

```text
A≈B
B≈C
A≉C
```

同时间资源中谁先成为锚点，可能决定最终是：

```text
[A, B] + C
```

还是：

```text
A + [B, C]
```

资源总数不变，但组数、组成员和“Best”展示可能不同。

### 4.3 是否需要对齐

建议对齐，但优先级低于逐帧视频诊断。

推荐新增并持久化：

- `date_added`
- 本轮 MediaStore `enumeration_order`

重建排序：

```text
created_at ASC,
date_added DESC,
enumeration_order ASC
```

这样比单纯依赖 `media_store_id` 更接近竞品稳定排序。

---

## 5. 增量分组策略

### 5.1 竞品

竞品保存旧 folder，并根据：

- 已处理资源集合
- `potentiallySimilarIdentifier`
- folder 类型
- 旧 folder 的边界 identifier

把新增或修改资源合并进已有 folder。

### 5.2 Demo

Demo 使用 MediaStore generation 只重新计算变化资源，但扫描完成后会读取该类型全部有效
指纹，重新执行确定性的锚点分组。

### 5.3 还存在的差异

- 竞品 folder ID 和历史顺序可以长期保留。
- Demo 的 group ID 可能在重建后变化。
- 竞品增量合并可能保留历史组边界。
- Demo 会根据当前全部有效数据修正历史边界。
- 竞品中途退出时可能展示旧 folder 加部分新增结果。
- Demo 扫描期间增量展示，完成后切换到确定性重建结果。

### 5.4 是否需要完全一致

**不建议照搬竞品增量 folder 状态机。**

原因：

- 用户关心的是当前资源的正确最终结果，不是内部 group ID。
- Demo 的重建方式能在删除、修改和算法升级后自动修正旧结果。
- 完全复现竞品历史状态会引入更多缓存污染和边界累积问题。
- 对 10 万资源可以继续使用数据库和 BK-Tree 控制成本，无需牺牲确定性。

需要保持一致的是：

- 全量最终分组数学规则。
- 排序输入。
- 指纹和阈值。
- Similar、Duplicate、Other 的互斥关系。

可以不一致的是：

- group ID。
- 中间状态。
- folder 持久化文件格式。
- 增量合并的内部实现。

结论：

> 建议补齐同时间稳定排序，但保留 Demo 的“增量计算 + 完成后确定性重建”架构。

---

## 6. 下一轮验收

安装 v15 后数据库会自动重建。必须等待完整扫描结束，再导出：

- media_asset
- fingerprint
- similar_group
- similar_group_item

建议新增视频逐帧诊断导出：

- mediaStoreId
- 文件名
- DATA 路径是否可读
- duration
- frameIndex
- sampleTimeMs
- sampleTimeUs
- frameValid
- imageHash
- colorHash

验收重点：

```text
PHOTO + SCREENSHOT + VIDEO + SCREEN_RECORDING = MediaStore 可见资源总数
```

以及：

```text
相同与相似互斥
相似与 Other 互斥
每个视频的帧数、时间点、有效帧和 Hash 可逐项解释
```
