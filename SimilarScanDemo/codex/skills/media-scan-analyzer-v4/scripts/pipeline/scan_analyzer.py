
from pathlib import Path
import re


SOURCE_SUFFIXES = {".kt", ".java", ".xml", ".md", ".kts"}
IMPORTANT_NAMES = {
    "SimilarMediaScanner",
    "MediaStoreRepository",
    "ScanDatabase",
    "HashCalculator",
    "HammingBkTree",
    "Threshold",
    "VideoFingerprintCalculator",
    "VideoFingerprint",
    "ProductCategoryBuilder",
    "MediaClassifier",
    "ScanStateStore",
    "MediaScanService",
    "MainActivity",
    "GroupDetailActivity",
}
KEYWORDS = {
    "MediaStore": ["MediaStore"],
    "batch_scan": ["BATCH_SIZE", "forEachMediaBatch", "batchSize"],
    "incremental_scan": ["generationModified", "MediaStore.getVersion", "shouldRunFullScan"],
    "dhash": ["dHash", "imageHash", "DHash"],
    "color_hash": ["colorHash", "ColorRange", "rgb"],
    "sha256": ["sha256", "contentSha"],
    "bk_tree": ["HammingBkTree", "BK-Tree", "BKTree"],
    "duplicate": ["Duplicate", "duplicateReference", "linkDuplicateAssets"],
    "similar": ["SimilarGroup", "linkSimilarAssets", "isSimilarTo"],
    "video_frames": ["VideoFingerprint", "getFrameAtTime", "getScaledFrameAtTime"],
    "delete_safety": ["DELETE_PENDING", "revision", "markDeletePending"],
    "product_category": ["ProductCategory", "ProductCategoryBuilder"],
}


def _read_text(path):
    try:
        return path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return ""


def _class_names(text):
    return sorted(set(re.findall(r"\b(?:class|object|data class|enum class)\s+([A-Za-z_][A-Za-z0-9_]*)", text)))


def _function_names(text):
    return sorted(set(re.findall(r"\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", text)))[:18]


def _constants(text):
    constants = {}
    for name, value in re.findall(r"\b(?:private\s+)?const\s+val\s+([A-Z0-9_]+)\s*=\s*([^\n]+)", text):
        constants[name] = value.strip()
    return constants


def analyze_scan(project_path):
    root = Path(project_path).resolve()
    files = [p for p in root.rglob("*") if p.is_file() and p.suffix in SOURCE_SUFFIXES]
    source_files = []
    constants = {}
    detected = {name: False for name in KEYWORDS}

    for path in files:
        rel = str(path.relative_to(root))
        text = _read_text(path)
        if not text:
            continue

        for feature, words in KEYWORDS.items():
            if any(word in text for word in words):
                detected[feature] = True

        path_constants = _constants(text)
        if path_constants:
            constants[rel] = path_constants

        classes = _class_names(text)
        important = (
            any(name in path.stem for name in IMPORTANT_NAMES)
            or any(name in IMPORTANT_NAMES for name in classes)
            or any(word in text for word in ["SimilarMediaScanner", "VideoFingerprint", "HammingBkTree"])
        )
        if important:
            source_files.append(
                {
                    "path": rel,
                    "classes": classes,
                    "functions": _function_names(text),
                    "line_count": text.count("\n") + 1,
                }
            )

    source_files = sorted(source_files, key=lambda item: item["path"])[:28]

    return {
        "project_name": root.name,
        "project_path": str(root),
        "scan_flow": "media_store -> media_asset -> fingerprint -> candidate_index -> grouping -> product_category",
        "source_file_count": len(files),
        "important_files": source_files,
        "constants": constants,
        "detected_features": detected,
    }
