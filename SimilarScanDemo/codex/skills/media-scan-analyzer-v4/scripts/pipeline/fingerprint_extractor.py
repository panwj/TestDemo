
def extract_fingerprint(raw):
    features = raw.get("detected_features", {})
    return {
        "imageHash": "dHash64" if features.get("dhash") else "visual_hash",
        "colorHash": "rgb_hist_8x3" if features.get("color_hash") else "optional_color_hash",
        "sha256": bool(features.get("sha256")),
        "candidateIndex": "BK-Tree" if features.get("bk_tree") else "candidate buckets",
    }
