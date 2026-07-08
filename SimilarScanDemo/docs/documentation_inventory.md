# 文档目录与维护规则

本文档用于区分 SimilarScanDemo 当前有效文档和历史归档文档。新人接手项目时，应优先阅读活跃文档；
`docs/archive/` 只作为问题复盘、竞品对齐和历史决策查证资料，不作为当前实现依据。

## 1. 目录分层

```text
docs/
  current_technical_solution.md        当前 Demo 技术方案
  media_scan_business_constraints.md   当前业务边界和 Case
  issue_prevention_checklist.md        问题记录与规避清单
  documentation_inventory.md           当前文档索引
  archive/                             历史文档归档，不作为当前实现依据

similar-scan-core/
  README.md                            SDK 能力和接入入口
  docs/core-technical-design.md        SDK 核心技术设计
  docs/integration-guide.md            SDK 接入指南
  docs/reference-implementation-plan.md  其他媒体扫描项目参考实现计划
```

## 2. 当前应优先阅读

| 文档 | 状态 | 用途 |
| --- | --- | --- |
| `README.md` | 当前 | Demo 工程入口、功能摘要、构建说明和文档导航 |
| `similar-scan-core/README.md` | 当前 | SDK module 能力边界、对外 API 和接入入口 |
| `similar-scan-core/docs/core-technical-design.md` | 当前 | SDK 内部扫描、指纹、分组、删除一致性和性能策略 |
| `similar-scan-core/docs/integration-guide.md` | 当前 | SDK 接入、权限申请、后台扫描、删除流程和宿主职责 |
| `similar-scan-core/docs/reference-implementation-plan.md` | 当前 | 其他媒体扫描项目复用当前扫描、首页更新和详情页数据方案的落地计划 |
| `docs/current_technical_solution.md` | 当前 | Demo 基于 SDK 的当前扫描、展示和竞品兼容方案 |
| `docs/media_scan_business_constraints.md` | 当前 | 权限、部分授权、扫描中断、系统干扰和删除确认等业务 Case |
| `docs/issue_prevention_checklist.md` | 当前 | 数据库崩溃、异步生命周期、分页展示、权限链路和外部干扰等问题记录与规避清单 |
| `docs/documentation_inventory.md` | 当前 | 文档分层、归档说明和维护规则 |

## 3. 历史归档文档

以下文档已移动到 `docs/archive/`。它们保留了竞品分析、CSV 排查、算法对齐和历史决策过程，
但其中的功能清单、包名、路径、阈值、指纹尺寸或架构描述可能已经被当前代码覆盖。

| 文档 | 状态 | 说明 |
| --- | --- | --- |
| `docs/archive/async_scan_and_user_mutation_design.md` | 历史设计，部分仍有效 | 异步扫描、删除状态、revision 思路仍有参考价值，但未覆盖最新 SDK 边界和中断 Case |
| `docs/archive/core_similarity_alignment_audit.md` | 历史审计 | 记录早期核心扫描审计，部分阈值和结论已被当前实现覆盖 |
| `docs/archive/media_classification_and_count_fix.md` | 历史问题单 | 记录 214 资源计数差异、Duplicate/Similar 互斥和旧视频分组修复 |
| `docs/archive/v14_core_scan_alignment_audit.md` | 历史审计 | v14 时点的竞品对齐审计，不代表当前 SDK 化后的实现 |
| `docs/archive/v15_alignment_changes_and_decisions.md` | 历史决策 | 记录删除 native so、删除音频、视频路径行为等决策 |
| `docs/archive/v16_video_grouping_analysis.md` | 历史问题单 | 记录视频大组问题分析，不代表当前最终视频策略和性能优化 |
| `docs/archive/competitor_alignment_1_10_implementation.md` | 历史实现说明 | 记录早期 1～10 点对齐实现，未覆盖最新权限链路、SDK 拆分和业务 Case |
| `docs/archive/competitor_gap_analysis_and_upgrade.md` | 过期改造说明 | 包含旧功能清单和音频相关描述，不应作为当前功能依据 |
| `docs/archive/product_level_similarity_scan_design.md` | 过期设计稿 | 早期产品级方案草稿，包含音频章节、旧路径和旧阈值口径 |

## 4. 是否删除归档文档

当前不建议删除 `docs/archive/` 中的文档。

原因：

- 归档文档包含竞品反编译路径、CSV 结论、真机差异和历史判断依据。
- 当前项目仍可能继续对齐竞品结果，历史证据有助于快速定位为什么做过某个决策。
- 后续如果要写正式 PRD、技术白皮书或复盘报告，可以从归档文档中提取证据。

如果后续确认某份归档文档没有任何查证价值，再单独删除，不做批量清理。

## 5. 后续维护规则

- 当前功能、算法、性能和业务边界变更，应优先更新活跃文档。
- 历史归档文档原则上不再补丁式维护，避免多个文件出现互相矛盾的“当前方案”。
- 新的问题排查文档如果只是阶段性记录，完成后应直接放入 `docs/archive/`。
- 新的长期有效设计文档才放在 `docs/` 第一层。
- SDK 对外能力变化，应同步更新 `similar-scan-core/README.md` 和 `similar-scan-core/docs/integration-guide.md`。
- SDK 内部算法、数据库、指纹或分组策略变化，应同步更新 `similar-scan-core/docs/core-technical-design.md`。
