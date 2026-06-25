
import argparse
import json
from pathlib import Path

from pipeline.semantic_modeler import build_semantic_model
from pipeline.ranking_engine import rank_importance
from pipeline.graph_builder import build_graph_model
from pipeline.scan_analyzer import analyze_scan
from pipeline.fingerprint_extractor import extract_fingerprint
from pipeline.video_analyzer import analyze_video
from pipeline.threshold_extractor import extract_thresholds
from interactor.review import interactive_review
from renderer.svg_renderer import render_svg

def run_skill(project_path, auto_confirm=False, output_dir="output"):

    raw = analyze_scan(project_path)

    semantic = build_semantic_model(raw)
    ranked = rank_importance(semantic)
    graph = build_graph_model(ranked)

    fp = extract_fingerprint(raw)
    video = analyze_video(raw)
    thresholds = extract_thresholds(raw)

    model = {
        "raw": raw,
        "semantic": semantic,
        "ranked": ranked,
        "graph": graph,
        "fingerprint": fp,
        "video": video,
        "thresholds": thresholds
    }

    confirmed = True if auto_confirm else interactive_review(model)

    if not confirmed:
        return "rejected"

    render_svg(model, output_dir=output_dir)
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)
    (output_path / "analysis_model.json").write_text(
        json.dumps(model, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    return "done"


def main():
    parser = argparse.ArgumentParser(
        description="Run the Media Scan Analyzer V4 semantic pipeline."
    )
    parser.add_argument("project_path", help="Path to the media scan project to analyze.")
    parser.add_argument(
        "--yes",
        action="store_true",
        help="Skip the interactive confirmation and render the SVG directly.",
    )
    parser.add_argument(
        "--output-dir",
        default="output",
        help="Directory for final.svg. Defaults to ./output.",
    )
    args = parser.parse_args()

    status = run_skill(
        project_path=args.project_path,
        auto_confirm=args.yes,
        output_dir=args.output_dir,
    )
    print(status)
    return 0 if status == "done" else 1


if __name__ == "__main__":
    raise SystemExit(main())
