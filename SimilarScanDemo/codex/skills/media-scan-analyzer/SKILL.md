---
name: media-scan-analyzer
description: Use when analyzing Android gallery/media scanning logic, SimilarScanDemo, MediaStore enumeration, similar photo/video detection, dHash/colorHash, video fingerprinting, thresholds, grouping, async scan state, user deletion consistency, and generating a reviewed one-page infographic image for media-cleaner scan flows.
---

# Media Scan Analyzer

This skill analyzes Android local gallery scanning and similar-media detection projects. It is optimized for `SimilarScanDemo`, but can be used on similar Kotlin/Java Android projects.

## Workflow

1. Confirm the analysis goal with the user:
   - Current-code-only analysis or competitor comparison.
   - Whether code changes are allowed or the task is documentation-only.
   - Desired output: review infographic or final infographic.

2. Locate and read the core files:
   - `app/src/main/java/**/scanner/MediaStoreRepository.kt`
   - `app/src/main/java/**/scanner/MediaClassifier.kt`
   - `app/src/main/java/**/scanner/MediaBitmapLoader.kt`
   - `app/src/main/java/**/scanner/SimilarMediaScanner.kt`
   - `app/src/main/java/**/similarity/KotlinDHash.kt`
   - `app/src/main/java/**/similarity/HashCalculator.kt`
   - `app/src/main/java/**/similarity/Threshold.kt`
   - `app/src/main/java/**/similarity/CombinedHash.kt`
   - `app/src/main/java/**/similarity/VideoFingerprintCalculator.kt`
   - `app/src/main/java/**/similarity/VideoFingerprint.kt`
   - `app/src/main/java/**/database/ScanDatabase.kt`
   - `app/src/main/java/**/MainActivity.kt`
   - `app/src/main/java/**/service/MediaScanService.kt`

3. Use the infographic review loop as the default output workflow:

   ```bash
   python3 codex/skills/media-scan-analyzer/scripts/generate_media_scan_infographic.py --project .
   ```

   Send the review image paths to the user:

   - `docs/generated/media-scan-analyzer/review/media_scan_infographic_review.svg`
   - `docs/generated/media-scan-analyzer/review/media_scan_infographic_review.png`

   After the user approves the content, run:

   ```bash
   python3 codex/skills/media-scan-analyzer/scripts/generate_media_scan_infographic.py --project . --final
   ```

   Final image paths:

   - `docs/generated/media-scan-analyzer/final/media_scan_infographic_final.svg`
   - `docs/generated/media-scan-analyzer/final/media_scan_infographic_final.png`

   If PNG export fails, keep the SVG and tell the user the local renderer is unavailable.

4. Analyze in this order:
   - Permission and first-launch flow.
   - MediaStore resource enumeration.
   - Media type classification.
   - Image bitmap source for fingerprinting.
   - Video frame source for fingerprinting.
   - UI thumbnail/cover source.
   - dHash and colorHash calculation.
   - Threshold matrix.
   - Duplicate rules.
   - Similar photo and screenshot grouping.
   - Similar video and screen-recording grouping.
   - SQLite persistence, incremental scan, and deletion consistency.
   - UI refresh and performance.

5. Ask for confirmation before finalizing uncertain points. Focus on:
   - Whether the user wants strict competitor parity or product-level improvements.
   - Whether video thresholds should stay current-code-only.
   - Whether diagrams should be added to an existing document or emitted in the answer.

6. Final delivery should be the approved infographic image, not a Markdown report.

## Diagram Guidance

The final output is a single infographic image. Internal diagrams may be represented in SVG directly.

## Rules

- Treat current source code as the source of truth.
- Do not claim competitor parity unless current code and competitor evidence both support it.
- Do not change code when the user asks only for analysis or documentation.
- When the code and older docs disagree, explicitly mark older docs as historical.
- For Android version behavior, list API-specific branches separately.
- For video analysis, always distinguish fingerprint frames from UI cover frames.
