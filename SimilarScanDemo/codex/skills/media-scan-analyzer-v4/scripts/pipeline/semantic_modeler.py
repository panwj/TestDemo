
def build_semantic_model(raw):
    features = raw.get("detected_features", {})
    secondary = ["UI rendering", "Delete consistency", "Incremental scan"]
    if features.get("product_category"):
        secondary.append("Product category aggregation")
    if features.get("video_frames"):
        secondary.append("Video multi-frame fingerprinting")

    risk_points = []
    if features.get("video_frames"):
        risk_points.append("video frame extraction failure or media decode cost")
    if features.get("delete_safety"):
        risk_points.append("DELETE_PENDING and revision race condition")
    risk_points.append("threshold tuning affects duplicate/similar boundary")

    return {
        "critical_path": [
            "MediaStore → MediaAsset → Fingerprint → Candidate Index → SimilarGroup → ProductCategory"
        ],
        "secondary_paths": secondary,
        "risk_points": risk_points,
        "code_facts": {
            "project": raw.get("project_name"),
            "source_file_count": raw.get("source_file_count", 0),
            "important_files": raw.get("important_files", []),
            "detected_features": features,
        },
    }
