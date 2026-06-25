
def analyze_video(raw):
    constants = raw.get("constants", {})
    frame_count = "7"
    max_frame_count = "13"
    max_file = "400MB"
    for values in constants.values():
        frame_count = values.get("DEFAULT_FRAME_COUNT", frame_count)
        max_frame_count = values.get("MAX_FRAME_COUNT", max_frame_count)
        max_file = values.get("MAX_FILE_BYTES", max_file)
    return {
        "frames": f"{frame_count}-{max_frame_count}",
        "match": "cartesian frame-to-frame comparison",
        "threshold": ">=2 frames",
        "maxFileBytes": max_file,
    }
