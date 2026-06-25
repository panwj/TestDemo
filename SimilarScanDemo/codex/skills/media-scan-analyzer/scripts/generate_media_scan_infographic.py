#!/usr/bin/env python3
"""Generate a one-page SVG infographic for SimilarScanDemo media scanning.

The output is designed for interactive review:

1. Generate a review draft:
   python3 codex/skills/media-scan-analyzer/scripts/generate_media_scan_infographic.py --project .

2. After the user approves the content, generate the final image:
   python3 codex/skills/media-scan-analyzer/scripts/generate_media_scan_infographic.py --project . --final
"""

from __future__ import annotations

import argparse
import html
import shutil
import subprocess
from pathlib import Path
from textwrap import wrap


W = 1800
H = 2400
M = 24
G = 18
BLUE = "#185abc"
LIGHT_BLUE = "#eef5ff"
GREEN = "#16833a"
LIGHT_GREEN = "#eefaf1"
ORANGE = "#d96b00"
LIGHT_ORANGE = "#fff4e8"
RED = "#cc344d"
LIGHT_RED = "#fff0f3"
PURPLE = "#6f35b8"
LIGHT_PURPLE = "#f5efff"
GRAY = "#526070"
LIGHT_GRAY = "#f7f9fc"
BORDER = "#cad7ea"


def esc(text: str) -> str:
    return html.escape(text, quote=True)


class Svg:
    def __init__(self) -> None:
        self.parts: list[str] = []

    def add(self, value: str) -> None:
        self.parts.append(value)

    def text(
        self,
        x: int,
        y: int,
        value: str,
        size: int = 20,
        color: str = "#14213d",
        weight: str = "400",
        anchor: str = "start",
    ) -> None:
        self.add(
            f'<text x="{x}" y="{y}" font-size="{size}" fill="{color}" '
            f'font-weight="{weight}" text-anchor="{anchor}">{esc(value)}</text>'
        )

    def rect(
        self,
        x: int,
        y: int,
        w: int,
        h: int,
        fill: str = "#fff",
        stroke: str = BORDER,
        radius: int = 10,
        sw: int = 1,
    ) -> None:
        self.add(
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{radius}" '
            f'fill="{fill}" stroke="{stroke}" stroke-width="{sw}"/>'
        )

    def line(self, x1: int, y1: int, x2: int, y2: int, color: str = "#7b8aa0", sw: int = 2) -> None:
        self.add(
            f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" '
            f'stroke="{color}" stroke-width="{sw}" stroke-linecap="round"/>'
        )

    def arrow(self, x1: int, y1: int, x2: int, y2: int, color: str = "#6b7a90") -> None:
        self.line(x1, y1, x2, y2, color, 2)
        self.add(
            f'<path d="M {x2} {y2} l -9 -5 l 0 10 z" fill="{color}" '
            f'transform="rotate({0 if x2 >= x1 else 180} {x2} {y2})"/>'
        )

    def badge(self, x: int, y: int, label: str, fill: str = BLUE) -> None:
        self.add(f'<circle cx="{x}" cy="{y}" r="17" fill="{fill}"/>')
        self.text(x, y + 7, label, 18, "#fff", "700", "middle")

    def wrapped_text(
        self,
        x: int,
        y: int,
        value: str,
        width_chars: int,
        size: int = 18,
        color: str = "#233044",
        line_height: int = 25,
        weight: str = "400",
    ) -> int:
        lines: list[str] = []
        for raw in value.split("\n"):
            if not raw:
                lines.append("")
            else:
                lines.extend(wrap(raw, width_chars, break_long_words=False))
        for index, line in enumerate(lines):
            self.text(x, y + index * line_height, line, size, color, weight)
        return y + len(lines) * line_height

    def card(
        self,
        x: int,
        y: int,
        w: int,
        h: int,
        title: str,
        fill: str = "#fff",
        color: str = BLUE,
        number: str | None = None,
    ) -> None:
        self.rect(x, y, w, h, fill)
        if number:
            self.badge(x + 25, y + 30, number, color)
            self.text(x + 52, y + 36, title, 22, color, "700")
        else:
            self.text(x + 18, y + 34, title, 22, color, "700")

    def bullet_list(
        self,
        x: int,
        y: int,
        items: list[str],
        width_chars: int,
        size: int = 17,
        color: str = "#253247",
        line_height: int = 24,
    ) -> int:
        current = y
        for item in items:
            self.text(x, current, "•", size, color, "700")
            current = self.wrapped_text(x + 16, current, item, width_chars, size, color, line_height)
        return current

    def table(
        self,
        x: int,
        y: int,
        headers: list[str],
        rows: list[list[str]],
        col_widths: list[int],
        row_h: int = 34,
    ) -> int:
        total_w = sum(col_widths)
        self.rect(x, y, total_w, row_h, "#eaf2ff", BORDER, 8)
        cx = x
        for i, header in enumerate(headers):
            self.text(cx + 10, y + 23, header, 15, BLUE, "700")
            cx += col_widths[i]
        cy = y + row_h
        for row in rows:
            self.rect(x, cy, total_w, row_h, "#fff", BORDER, 0)
            cx = x
            for i, cell in enumerate(row):
                self.text(cx + 10, cy + 23, cell, 15, "#253247")
                cx += col_widths[i]
            cy += row_h
        return cy

    def finish(self) -> str:
        body = "\n".join(self.parts)
        return f"""<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">
<defs>
  <style>
    text {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans CJK SC", "Microsoft YaHei", Arial, sans-serif; }}
  </style>
  <filter id="shadow" x="-10%" y="-10%" width="120%" height="120%">
    <feDropShadow dx="0" dy="2" stdDeviation="4" flood-color="#9aa9bd" flood-opacity="0.22"/>
  </filter>
</defs>
<rect width="{W}" height="{H}" fill="#fbfdff"/>
{body}
</svg>
"""


def step_cards(svg: Svg) -> None:
    svg.text(M, 76, "1. 完整流程概览（从授权到结果展示）", 28, BLUE, "800")
    titles = [
        ("授权", LIGHT_BLUE, BLUE, ["申请权限", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO"]),
        ("资源读取", LIGHT_GREEN, GREEN, ["MediaStore 读取", "图片 + 视频", "_size > 0", "DATE_ADDED DESC"]),
        ("类型分类", LIGHT_ORANGE, ORANGE, ["PHOTO / SCREENSHOT", "VIDEO / SCREEN_RECORDING", "基于文件名规则"]),
        ("入库", LIGHT_RED, RED, ["media_asset", "state = ACTIVE", "fingerprint_status"]),
        ("指纹计算", LIGHT_PURPLE, PURPLE, ["图片: dHash + colorHash", "视频: 7~13 帧", "每帧 hash"]),
        ("重复识别", LIGHT_BLUE, BLUE, ["duplicateReference", "类型/尺寸/hash/大小", "SHA-256 诊断"]),
        ("相似识别", LIGHT_BLUE, BLUE, ["BK-Tree 召回", "阈值精判", "Video 2 帧命中"]),
        ("分组重建", LIGHT_ORANGE, ORANGE, ["扫描结束重建", "Anchor 直接相似", "非连通分量"]),
        ("结果展示", LIGHT_GREEN, GREEN, ["ProductCategory", "后台读库", "800ms 节流"]),
    ]
    x = M
    y = 104
    w = 180
    h = 190
    for i, (title, fill, color, items) in enumerate(titles, start=1):
        svg.card(x, y, w, h, title, fill, color)
        svg.bullet_list(x + 18, y + 78, items, 15, 15)
        svg.badge(x + w // 2, y + h + 18, str(i), color)
        if i < len(titles):
            svg.arrow(x + w + 3, y + 95, x + w + G - 5, y + 95)
        x += w + G


def main_sections(svg: Svg) -> None:
    y = 350
    # Row 1
    svg.card(M, y, 380, 310, "2. 扫描模式", LIGHT_BLUE, BLUE, "2")
    svg.bullet_list(M + 25, y + 72, [
        "全量扫描：数据库为空、权限不完整、MediaStore version 变化、超过 24 小时或 forceFull。",
        "增量扫描：API 30+ 基于 generation_modified 续扫。",
        "中断后 checkpoint 不推进，下次从旧游标继续。",
        "完整授权的全量扫描结束后清理未出现资源。",
    ], 31, 17)

    svg.card(M + 400, y, 520, 310, "3. 资源读取流程（批次大小：500）", "#fff", BLUE, "3")
    batch_x = M + 425
    for i in range(5):
        bx = batch_x + i * 96
        svg.rect(bx, y + 70, 78, 198, "#f8fbff", BORDER, 8)
        svg.text(bx + 39, y + 96, f"Batch {i+1}", 14, BLUE, "700", "middle")
        for j, label in enumerate(["读取", "MediaAsset", "入库", "指纹", "识别"]):
            svg.rect(bx + 10, y + 112 + j * 30, 58, 22, "#fff", "#d8e4f4", 5)
            svg.text(bx + 39, y + 128 + j * 30, label, 12, "#26364d", "500", "middle")
    svg.wrapped_text(M + 430, y + 292, "500 只是读取批次，不是相似比较边界；扫描完成后统一重建 SimilarGroup。", 56, 15, GRAY, 20)

    svg.card(M + 940, y, 404, 310, "4. 媒体分类规则", "#fff", BLUE, "4")
    svg.bullet_list(M + 965, y + 72, [
        "MediaStore.Images -> PHOTO / SCREENSHOT",
        "MediaStore.Video -> VIDEO / SCREEN_RECORDING",
        "截图和录屏依赖 DISPLAY_NAME 关键词二次分类。",
        "Chat Photos 是产品展示分类，不是底层 MediaKind。",
    ], 34, 17)

    svg.card(M + 1364, y, 390, 310, "5. 权限与启动链路", LIGHT_GREEN, GREEN, "5")
    svg.bullet_list(M + 1389, y + 72, [
        "媒体权限通过后标记 pendingScanAfterPermission。",
        "通知权限只请求一次；拒绝不阻止扫描。",
        "startForegroundService 后 2s grace 避免 UI 回到 Rescan。",
        "扫描进度通过包内广播回首页。",
    ], 32, 17)

    # Row 2
    y2 = 655
    svg.card(M, y2, 340, 330, "6. 图片指纹（Image Fingerprint）", "#fff", BLUE, "6")
    svg.bullet_list(M + 25, y2 + 72, [
        "Bitmap 来源：loadThumbnail 1024，失败后 inputStream，再失败走 DATA 文件路径。",
        "dHash：9 x 8 双线性灰度采样，输出 64-bit Long。",
        "colorHash：RGB 8 x 3 直方图，每 32 灰阶一桶。",
        "qualityScore：清晰度、曝光、分辨率、收藏、编辑等综合评分。",
    ], 29, 17)

    svg.card(M + 360, y2, 360, 330, "7. 图片相似识别流程", LIGHT_BLUE, BLUE, "7")
    cx = M + 450
    boxes = ["新图片", "加载指纹 Bitmap", "dHash + colorHash", "BK-Tree 候选召回", "阈值精判", "写入 SimilarGroup"]
    for i, label in enumerate(boxes):
        by = y2 + 70 + i * 39
        svg.rect(cx, by, 180, 26, "#fff", "#cbd9ef", 6)
        svg.text(cx + 90, by + 19, label, 14, "#1c2e48", "600", "middle")
        if i < len(boxes) - 1:
            svg.arrow(cx + 90, by + 28, cx + 90, by + 37)

    svg.card(M + 740, y2, 400, 330, "8. 相似阈值（CombinedHash）", "#fff", BLUE, "8")
    svg.table(M + 765, y2 + 70, ["类型", "直接", "max", "color"], [
        ["PHOTO", "0..4", "<18", "4..10<=7 / 10..17<=5"],
        ["SCREENSHOT", "0..2", "<16", "2..10<=5 / 10..15<=2"],
        ["VIDEO", "0..2", "<16", "2..10<=5 / 10..15<=2"],
        ["SCREEN_REC", "0..2", "<16", "2..10<=5 / 10..15<=2"],
    ], [95, 70, 55, 150], 34)
    svg.wrapped_text(M + 765, y2 + 245, "先判断 dHash 最大距离；直接区间命中即相似；中间距离再用 colorHash 过滤。", 38, 16, GRAY, 23)

    svg.card(M + 1160, y2, 594, 330, "9. 重复 / 相同图片识别", LIGHT_GREEN, GREEN, "9")
    svg.bullet_list(M + 1185, y2 + 72, [
        "Duplicate 候选条件：类型、宽度、高度、isEdited、size、imageHash 全部相同。",
        "SHA-256 按需计算并缓存，只作为字节级验证证据。",
        "Duplicate 优先级高于 Similar，同一资源不重复累计。",
        "用户删除期间 revision 变化，旧扫描令牌提交失败。",
    ], 52, 17)

    # Row 3
    y3 = 1015
    svg.card(M, y3, 570, 350, "10. 视频指纹（Video Fingerprint）", "#fff", BLUE, "10")
    svg.bullet_list(M + 25, y3 + 72, [
        "只接受 MediaStore DATA 真实路径；失败返回无效指纹，不回退 content Uri。",
        "抽帧规则：MIN_INTERVAL=2，MAX_INTERVAL=10，NORMAL_FRAME_COUNT=7，MAX_FRAME_COUNT=13。",
        "API 30+：getScaledFrameAtTime + ARGB_8888 + 9x8。",
        "API 27-29：getScaledFrameAtTime + 9x8。",
        "API 23-26：getFrameAtTime 后缩放到 9x8。",
    ], 49, 17)

    svg.card(M + 590, y3, 570, 350, "11. 视频相似识别方案", LIGHT_BLUE, BLUE, "11")
    svg.bullet_list(M + 615, y3 + 72, [
        "候选限定同类型：VIDEO 或 SCREEN_RECORDING。",
        "left.frames 遍历，right.frames 中找到一个相似帧后 matchedCount + 1。",
        "matchedCount >= 2 判定相似。",
        "当前可优化点：低信息帧过滤、内部重复帧去重、时间位置约束。",
    ], 49, 17)

    svg.card(M + 1180, y3, 574, 350, "12. 分组生成规则", "#fff", ORANGE, "12")
    svg.bullet_list(M + 1205, y3 + 72, [
        "扫描中实时写组，便于 UI 及时展示。",
        "扫描完成后按类型重建 SimilarGroup。",
        "图片/截图锚点顺序：created_at ASC, media_store_id DESC。",
        "视频/录屏锚点顺序：date_added DESC。",
        "只合并与锚点直接相似的资源，不做连通分量合并。",
    ], 49, 17)

    # Row 4
    y4 = 1395
    svg.card(M, y4, 430, 330, "13. 资源状态（state）", "#fff", BLUE, "13")
    svg.table(M + 25, y4 + 72, ["状态", "含义", "处理"], [
        ["ACTIVE", "可展示/扫描", "参与候选"],
        ["DELETE_PENDING", "等待系统确认", "不参与扫描"],
        ["DELETED", "已删除", "移除记录"],
        ["FAILED", "指纹失败", "保留元数据"],
    ], [120, 155, 120], 36)

    svg.card(M + 450, y4, 430, 330, "14. 删除安全机制", LIGHT_ORANGE, ORANGE, "14")
    svg.bullet_list(M + 475, y4 + 72, [
        "markDeletePending：state=DELETE_PENDING，revision+1。",
        "扫描提交时校验 token revision。",
        "系统删除确认后 finalizeDelete 清理资源。",
        "取消或进程恢复时 restore/recover 到 ACTIVE 并标记待扫。",
    ], 36, 17)

    svg.card(M + 900, y4, 430, 330, "15. 首页展示与性能", LIGHT_GREEN, GREEN, "15")
    svg.bullet_list(M + 925, y4 + 72, [
        "扫描进度广播只更新文字。",
        "结果刷新 800ms 节流。",
        "后台线程 loadGroups + build ProductCategory。",
        "adapter submitList 原地更新。",
        "Other 分类只加载 120 个预览，真实数量/大小 SQL 聚合。",
    ], 36, 17)

    svg.card(M + 1350, y4, 404, 330, "16. 当前边界", LIGHT_RED, RED, "16")
    svg.bullet_list(M + 1375, y4 + 72, [
        "外部相册删除在增量扫描中可能延迟同步。",
        "视频相似受静态 UI、片头、低信息帧影响。",
        "DATA 路径不可读会得到无效视频指纹。",
        "Other 详情超大集合后续建议分页。",
    ], 34, 17)

    # Footer summary
    y5 = 1760
    svg.card(M, y5, W - 2 * M, 560, "17. 技术方案总结与审核点", "#fff", BLUE, "17")
    svg.text(M + 35, y5 + 82, "当前方案定位", 22, BLUE, "800")
    svg.bullet_list(M + 35, y5 + 122, [
        "工程层采用产品级异步落库方案：前台服务扫描、SQLite 持久化、候选召回、UI 实时刷新。",
        "算法层使用 dHash + colorHash + 分媒体类型阈值，视频使用多帧指纹和至少 2 帧命中。",
        "展示层区分分析帧与 UI 封面，首页刷新节流并异步加载缩略图。",
    ], 75, 18)
    svg.text(M + 35, y5 + 255, "需要审核确认", 22, RED, "800")
    svg.bullet_list(M + 35, y5 + 295, [
        "视频相似是否继续保持当前严格阈值，还是引入低信息帧过滤与时间位置约束。",
        "外部系统相册删除是否需要每次扫描前做轻量 MediaStore 对账。",
        "Other 分类详情页是否需要分页加载。",
        "是否将最终图作为 PNG 交付；当前 skill 原生输出 SVG，可进一步接入浏览器渲染 PNG。",
    ], 75, 18)
    svg.text(M + 35, y5 + 475, "生成物", 22, GREEN, "800")
    svg.wrapped_text(M + 35, y5 + 512, "审核稿：docs/generated/media-scan-analyzer/review/media_scan_infographic_review.svg；最终稿：docs/generated/media-scan-analyzer/final/media_scan_infographic_final.svg", 120, 18, GRAY, 26)


def build_svg(final: bool) -> str:
    svg = Svg()
    svg.text(M, 36, "SimilarScanDemo 图库扫描技术方案信息图", 30, BLUE, "900")
    svg.text(W - M, 36, "FINAL" if final else "REVIEW DRAFT", 20, GREEN if final else ORANGE, "800", "end")
    step_cards(svg)
    main_sections(svg)
    return svg.finish()


def try_export_png(svg_path: Path, png_path: Path) -> bool:
    """Export SVG to PNG with macOS Quick Look when available.

    The project intentionally avoids Python imaging dependencies. On macOS,
    qlmanage can render SVG previews without adding third-party packages.
    """
    qlmanage = shutil.which("qlmanage")
    if not qlmanage:
        return False

    preview_dir = png_path.parent / ".preview"
    preview_dir.mkdir(parents=True, exist_ok=True)
    for old in preview_dir.glob("*"):
        if old.is_file():
            old.unlink()

    result = subprocess.run(
        [
            qlmanage,
            "-t",
            "-s",
            str(W),
            "-o",
            str(preview_dir),
            str(svg_path),
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if result.returncode != 0:
        return False

    candidates = sorted(preview_dir.glob("*.png"))
    if not candidates:
        return False
    if png_path.exists():
        png_path.unlink()
    candidates[0].replace(png_path)
    for old in preview_dir.glob("*"):
        if old.is_file():
            old.unlink()
    try:
        preview_dir.rmdir()
    except OSError:
        pass
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", default=".", help="Android project root.")
    parser.add_argument("--final", action="store_true", help="Write the approved final image path.")
    parser.add_argument("--no-png", action="store_true", help="Only write SVG; skip PNG export.")
    parser.add_argument(
        "--output-dir",
        default="docs/generated/media-scan-analyzer",
        help="Output directory, relative to project root unless absolute.",
    )
    args = parser.parse_args()

    project = Path(args.project).resolve()
    output_dir = Path(args.output_dir)
    if not output_dir.is_absolute():
        output_dir = project / output_dir
    stage = "final" if args.final else "review"
    target_dir = output_dir / stage
    target_dir.mkdir(parents=True, exist_ok=True)

    filename = "media_scan_infographic_final.svg" if args.final else "media_scan_infographic_review.svg"
    target = target_dir / filename
    target.write_text(build_svg(args.final), encoding="utf-8")
    print(f"Wrote {target}")
    if not args.no_png:
        png_name = filename.replace(".svg", ".png")
        png_target = target_dir / png_name
        if try_export_png(target, png_target):
            print(f"Wrote {png_target}")
        else:
            print("PNG export skipped: qlmanage is unavailable or failed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
