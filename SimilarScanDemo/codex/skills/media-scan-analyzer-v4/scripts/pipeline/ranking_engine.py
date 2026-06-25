
def rank_importance(semantic):
    return {
        "critical": semantic["critical_path"],
        "high": semantic["risk_points"],
        "medium": semantic["secondary_paths"]
    }
