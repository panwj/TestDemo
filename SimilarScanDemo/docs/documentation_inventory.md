# 文档清理评估

## 1. 结论

当前文档数量较多，但不建议直接删除。原因是其中多份文档记录了竞品反编译、CSV 排查、
视频分组差异和每一轮决策过程，后续复盘问题时仍有价值。

建议采用以下策略：

```text
当前实现说明 -> 只看 current_technical_solution.md
历史排查记录 -> 保留，但不作为当前实现依据
明显过期设计 -> 保留并标记为历史，不再继续维护
```

## 2. 当前应优先阅读

| 文档 | 状态 | 用途 |
| --- | --- | --- |
| `README.md` | 当前 | 工程入口、功能摘要和构建说明 |
| `docs/current_technical_solution.md` | 当前 | 当前代码对应的完整技术方案 |
| `docs/documentation_inventory.md` | 当前 | 文档状态说明 |
| `codex/skills/media-scan-analyzer/SKILL.md` | 当前 | 项目内 Codex skill，用于图库扫描逻辑分析和技术图输出 |
| `docs/generated/media-scan-analyzer/review/media_scan_infographic_review.svg/png` | 审核稿 | 信息图审核稿，供用户互动式确认 |
| `docs/generated/media-scan-analyzer/final/media_scan_infographic_final.svg/png` | 最终稿 | 用户审核通过后的最终信息图 |

## 3. 可作为历史参考

| 文档 | 状态 | 说明 |
| --- | --- | --- |
| `docs/async_scan_and_user_mutation_design.md` | 历史设计，部分仍有效 | 异步扫描、删除状态、revision 思路仍与当前代码一致，但未覆盖最新首页节流刷新和 Other 预览限制 |
| `docs/core_similarity_alignment_audit.md` | 历史审计 | 记录 v14/v15 后的核心审计，部分阈值和结论已被最新代码覆盖 |
| `docs/media_classification_and_count_fix.md` | 历史问题单 | 记录 214 资源计数差异和 Duplicate/Similar 互斥修复 |
| `docs/v14_core_scan_alignment_audit.md` | 历史审计 | v14 时点，不代表当前实现 |
| `docs/v15_alignment_changes_and_decisions.md` | 历史决策 | 记录删除 native so、删除音频、视频路径行为等决策 |
| `docs/v16_video_grouping_analysis.md` | 历史问题单 | 记录视频大组问题分析，不代表当前最终阈值和 UI 性能优化 |
| `docs/competitor_alignment_1_10_implementation.md` | 历史实现说明 | 记录早期 1～10 点对齐实现，未覆盖最新权限链路和首页性能优化 |
| `docs/competitor_gap_analysis_and_upgrade.md` | 过期改造说明 | 包含音频能力等旧内容，不应作为当前功能清单 |
| `docs/product_level_similarity_scan_design.md` | 过期设计稿 | 早期产品级方案，包含音频章节和旧阈值描述，不应作为当前技术依据 |

## 4. 是否删除过期文档

当前不建议删除，建议后续稳定后统一归档：

```text
docs/archive/
```

可归档候选：

- `product_level_similarity_scan_design.md`
- `competitor_gap_analysis_and_upgrade.md`
- `v14_core_scan_alignment_audit.md`
- `v15_alignment_changes_and_decisions.md`
- `v16_video_grouping_analysis.md`
- `media_classification_and_count_fix.md`

暂不删除的理由：

- 这些文档包含竞品反编译路径、CSV 结论和历史判断依据。
- 当前仍在与竞品结果对齐，删除后会降低问题回溯效率。
- 后续若要写正式 PRD/技术白皮书，可从这些历史文档提取证据。

## 5. 后续维护规则

- 新问题只更新 `current_technical_solution.md` 和必要的问题单。
- 历史文档不再补丁式维护，避免多个文档出现相互矛盾的“当前方案”。
- README 只保留摘要和入口链接，不再展开全部算法细节。
