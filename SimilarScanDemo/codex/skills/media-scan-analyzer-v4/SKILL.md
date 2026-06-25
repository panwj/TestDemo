---
name: media-scan-analyzer-v4
description: Analyze Android media-scan, similar-photo, duplicate-media, fingerprinting, BKTree grouping, delete-consistency, incremental-scan, and video-frame matching projects by reading code, extracting implementation facts, organizing a technical solution, abstracting architecture, and generating a beginner-friendly Chinese technical explanation diagram. Use when Codex needs to turn a local media scan project into an onboarding-ready architecture/technical-plan SVG plus a machine-readable analysis model.
---

# Media Scan Analyzer V4

## Overview

Use this skill to analyze a local media scanning project and produce an onboarding-ready technical explanation. The workflow is: read code, extract implementation facts, organize the technical solution, abstract the architecture, reorganize information for newcomers, then render a dense but readable Chinese SVG diagram.

## Workflow

1. Inspect the target project enough to confirm it is a media scanning, fingerprinting, grouping, deletion, or video matching codebase.
2. Read the generated `analysis_model.json` after running the script when you need exact implementation evidence. Use it to ground the explanation in real files, classes, constants, and detected capabilities.
3. Run the bundled script from the skill directory or with an absolute path:

```bash
python3 scripts/run_media_scan.py /path/to/project --yes --output-dir /path/to/output
```

4. Omit `--yes` when an explicit interactive confirmation is desired. The script will print the semantic model and risk graph, then ask `Confirm model? (y/n):`.
5. Review `final.svg` in the output directory. The renderer must produce a beginner-friendly Chinese technical-plan diagram with generous spacing, no overlapping text, and clearly separated sections: flow overview, scan modes, batch reading, classification rules, image/video fingerprinting, duplicate/similar recognition, state transitions, grouping, product categories, deletion safety, thresholds, reading order, and code evidence.
6. Use the generated model concepts in the final answer: critical path, implementation evidence, secondary paths, risk points, ranking, graph layout, fingerprint model, video matching model, thresholds, and newcomer reading order.

## Preserved V4 Pipeline

Keep the stage order aligned with the original `skill.yaml`:

- `parse_project`
- `build_semantic_model`
- `rank_importance`
- `build_graph_model`
- `extract_fingerprint`
- `extract_video_pipeline`
- `extract_thresholds`
- `detect_state_machine`
- `interactive_review`
- `render_svg`

The migrated implementation exposes the same core model keys:

- `semantic`
- `ranked`
- `graph`
- `fingerprint`
- `video`
- `thresholds`

The script extracts project facts from Kotlin/Java/XML/Markdown sources, including important files, class names, function names, constants, and feature signals. Keep this extraction deterministic and conservative; Codex can add reasoning on top of these facts in the final response.

## Output Semantics

Report results using this conceptual vocabulary:

- Critical path: `MediaStore -> MediaAsset -> Fingerprint -> Candidate Index -> SimilarGroup -> ProductCategory`
- Secondary paths: UI rendering, delete consistency, incremental scan
- Risk points: video frame extraction/decode cost, `DELETE_PENDING` race condition, threshold tuning
- Fingerprints: `dHash64`, `rgb_hist_8x3`, `sha256`
- Video matching: 7-13 frames, cartesian matching, at least 2 matching frames
- Thresholds: photo `dhash=17`, photo `color=5`, video `frame_match=2`
- Code evidence: key files/classes/functions/constants extracted into `analysis_model.json`

## Resources

- `scripts/run_media_scan.py`: Codex-friendly entrypoint. Writes `final.svg` and `analysis_model.json`.
- `scripts/pipeline/scan_analyzer.py`: scans local source files and extracts implementation facts.
- `scripts/pipeline/`: semantic modeling, ranking, graph, fingerprint, video, and threshold modules.
- `scripts/interactor/review.py`: original confirmation prompt.
- `scripts/renderer/svg_renderer.py`: beginner-friendly SVG renderer with fixed grid layout and anti-overlap spacing.
