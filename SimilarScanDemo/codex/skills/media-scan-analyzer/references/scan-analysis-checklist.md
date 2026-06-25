# Media Scan Analysis Checklist

Use this checklist when reviewing gallery scan logic.

## Resource Enumeration

- Which MediaStore collections are queried?
- What selection filters are used, such as `_size > 0`?
- What sort order is used?
- Which fields are projected?
- How are `DATE_TAKEN`, `DATE_ADDED`, `DATE_MODIFIED`, and generation fields interpreted?
- Does the project support partial media access?
- Does full scan cleanup only run with full media access?

## Classification

- What types come directly from MediaStore?
- Which types are inferred by filename, bucket, path, MIME, or dimensions?
- Are screenshot and screen-recording rules symmetric with competitor evidence?
- Are chat images product categories or scan types?

## Image Fingerprint Input

- Is the fingerprint bitmap loaded from `loadThumbnail`, input stream, or `DATA` path?
- What size is requested?
- Does UI thumbnail loading share the same path, or is it separate?
- What happens when bitmap loading fails?

## Video Fingerprint Input

- Does video fingerprinting use `DATA` path or `content://` URI?
- Does it fall back when the path fails?
- How are sample times generated?
- Which `MediaMetadataRetriever` methods are used per API version?
- Does the code preserve invalid frames?
- Are UI video covers separate from fingerprint frames?

## Hashes and Thresholds

- What dHash implementation is used?
- What input size and interpolation are used?
- What colorHash bucket layout is used?
- What is the direct dHash threshold per media kind?
- What are the dHash/colorHash combined ranges?
- Are max distances inclusive or exclusive?

## Duplicate and Similar Rules

- What fields define duplicates?
- Are duplicates excluded from similar groups?
- Is SHA-256 required or only diagnostic?
- Is grouping anchor-based or connected-component based?
- What is the anchor order per media type?

## Async and Persistence

- What tables are the source of truth?
- How does checkpointing work?
- What happens on interrupted scans?
- How are user deletions protected from scan-result resurrection?
- Can external system-gallery deletions be detected in incremental mode?

## UI and Performance

- Are database reads on the main thread?
- Is result refresh throttled?
- Are thumbnails loaded asynchronously?
- Are large Other categories loaded fully or previewed?
- Are old refresh tasks cancelled or ignored?
