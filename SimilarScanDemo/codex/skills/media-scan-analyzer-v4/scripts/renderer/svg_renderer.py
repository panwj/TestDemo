from pathlib import Path
from xml.sax.saxutils import escape


WIDTH = 1400
HEIGHT = 2300
BLUE = "#173f8a"
TEXT = "#172033"
MUTED = "#5b6578"
BORDER = "#cfd9ea"
BG = "#f7faff"


def _text(value, x, y, size=16, weight="400", color=TEXT, anchor="start"):
    return (
        f"<text x='{x}' y='{y}' font-size='{size}' font-weight='{weight}' "
        f"fill='{color}' text-anchor='{anchor}'>{escape(str(value))}</text>"
    )


def _rect(x, y, w, h, fill="#ffffff", stroke=BORDER, radius=8, width=1):
    return (
        f"<rect x='{x}' y='{y}' width='{w}' height='{h}' rx='{radius}' "
        f"fill='{fill}' stroke='{stroke}' stroke-width='{width}'/>"
    )


def _line(x1, y1, x2, y2, color="#9aa8bd", width=1.5, arrow=False):
    marker = " marker-end='url(#arrow)'" if arrow else ""
    return (
        f"<line x1='{x1}' y1='{y1}' x2='{x2}' y2='{y2}' "
        f"stroke='{color}' stroke-width='{width}'{marker}/>"
    )


def _circle(cx, cy, r, fill="#1d63c6", stroke="#ffffff"):
    return f"<circle cx='{cx}' cy='{cy}' r='{r}' fill='{fill}' stroke='{stroke}' stroke-width='2'/>"


def _split_long_token(token, max_chars):
    return [token[i : i + max_chars] for i in range(0, len(token), max_chars)]


def _wrap(value, max_chars):
    text = str(value).strip()
    if not text:
        return [""]

    rough = []
    current = ""
    for token in text.replace("/", " / ").replace("，", "， ").replace("、", "、 ").split():
        pieces = _split_long_token(token, max_chars) if len(token) > max_chars else [token]
        for piece in pieces:
            candidate = piece if not current else f"{current} {piece}"
            if len(candidate) <= max_chars:
                current = candidate
            else:
                if current:
                    rough.append(current)
                current = piece
    if current:
        rough.append(current)
    return rough or [text[:max_chars]]


def _multiline(value, x, y, max_chars, size=12, color=TEXT, weight="400", line_gap=5):
    parts = []
    line_h = size + line_gap
    for index, line in enumerate(_wrap(value, max_chars)):
        parts.append(_text(line, x, y + index * line_h, size=size, weight=weight, color=color))
    return parts, len(_wrap(value, max_chars)) * line_h


def _bullet(value, x, y, max_chars=34, size=12, color=TEXT):
    parts = [_text("•", x, y, size=size, color=color)]
    lines = _wrap(value, max_chars)
    line_h = size + 5
    for index, line in enumerate(lines):
        parts.append(_text(line, x + 16, y + index * line_h, size=size, color=color))
    return parts, max(1, len(lines)) * line_h


def _panel(title, x, y, w, h, fill="#ffffff"):
    return [
        _rect(x, y, w, h, fill=fill),
        _text(title, x + 14, y + 28, size=18, weight="800", color=BLUE),
    ]


def _bullets(items, x, y, max_chars, size=12, color=TEXT, gap=3):
    parts = []
    cy = y
    for item in items:
        item_parts, inc = _bullet(item, x, cy, max_chars=max_chars, size=size, color=color)
        parts.extend(item_parts)
        cy += inc + gap
    return parts


def _table(x, y, widths, row_h, rows, size=11):
    parts = [_rect(x, y, sum(widths), row_h * len(rows), fill="#ffffff", radius=5)]
    cy = y
    for r, row in enumerate(rows):
        fill = "#eef5ff" if r == 0 else ("#ffffff" if r % 2 else "#fbfdff")
        parts.append(_rect(x, cy, sum(widths), row_h, fill=fill, radius=0, stroke="#e0e8f5"))
        cx = x
        for c, value in enumerate(row):
            weight = "700" if r == 0 else "400"
            parts.append(_text(value, cx + 8, cy + row_h / 2 + 4, size=size, weight=weight))
            if c:
                parts.append(_line(cx, cy, cx, cy + row_h, color="#e0e8f5"))
            cx += widths[c]
        cy += row_h
    return parts


def _flow_card(index, title, bullets, x, y, w, h, fill, accent):
    parts = [_rect(x, y, w, h, fill=fill, stroke="#d3dbea")]
    parts.append(_text(title, x + w / 2, y + 30, size=15, weight="800", color=BLUE, anchor="middle"))
    cy = y + 58
    for item in bullets:
        b, inc = _bullet(item, x + 12, cy, max_chars=14, size=10)
        parts.extend(b)
        cy += inc + 2
    parts.append(_circle(x + w / 2, y + h + 16, 13, fill=accent))
    parts.append(_text(index, x + w / 2, y + h + 21, size=11, weight="800", color="#ffffff", anchor="middle"))
    return parts


def _constants(raw):
    flat = {}
    for path, values in raw.get("constants", {}).items():
        for key, value in values.items():
            flat[key] = value
    return flat


def _important_files(raw):
    files = []
    for item in raw.get("important_files", [])[:10]:
        classes = ", ".join(item.get("classes") or [])
        files.append(f"{item.get('path')}：{classes or 'source file'}")
    return files


def render_svg(model, output_dir="output"):
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)
    raw = model.get("raw", {})
    constants = _constants(raw)
    features = raw.get("detected_features", {})
    fp = model.get("fingerprint", {})
    video = model.get("video", {})

    parts = [
        f"<svg xmlns='http://www.w3.org/2000/svg' width='{WIDTH}' height='{HEIGHT}' viewBox='0 0 {WIDTH} {HEIGHT}'>",
        "<defs>",
        "<marker id='arrow' markerWidth='8' markerHeight='8' refX='7' refY='3.5' orient='auto'>",
        "<polygon points='0 0, 8 3.5, 0 7' fill='#7d8ca3'/>",
        "</marker>",
        "<style>text{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',Arial,sans-serif;} .mono{font-family:'SFMono-Regular',Consolas,monospace;}</style>",
        "</defs>",
        f"<rect width='{WIDTH}' height='{HEIGHT}' fill='{BG}'/>",
        _text("媒体扫描 Demo 技术方案讲解图", 24, 42, size=28, weight="900", color=BLUE),
        _text("代码分析 -> 技术方案整理 -> 架构抽象 -> 新人可读的流程图", 24, 72, size=15, color=MUTED),
        _text(f"项目：{raw.get('project_name', 'Unknown')}   源文件：{raw.get('source_file_count', 0)} 个", 24, 98, size=13, color=MUTED),
    ]

    flow = [
        ("1", "授权", ["申请媒体权限", "图片 / 视频读取"], "#f7fbff", "#1f70df"),
        ("2", "资源读取", ["MediaStore", "按批 500 个", "增量 generation"], "#f6fff8", "#2f8f3a"),
        ("3", "类型分类", ["PHOTO", "SCREENSHOT", "VIDEO", "SCREEN_RECORDING"], "#fffaf4", "#f28c22"),
        ("4", "入库", ["media_asset", "state=ACTIVE", "fingerprint=PENDING"], "#fff6f7", "#da3957"),
        ("5", "指纹计算", ["dHash", "colorHash", "sha256", "qualityScore"], "#faf7ff", "#7355c9"),
        ("6", "重复识别", ["类型/尺寸/编辑状态", "dHash 完全相同", "Duplicate"], "#f6fbff", "#1b69c9"),
        ("7", "相似识别", ["BK-Tree 候选", "阈值二次判断", "SimilarGroup"], "#f8fbff", "#2a86c7"),
        ("8", "分组重建", ["Anchor 规则", "避免链式扩散"], "#fffaf5", "#ed7c24"),
        ("9", "首页分类", ["ProductCategory", "结果展示"], "#f8fff9", "#2e9d4f"),
    ]
    parts.append(_text("1. 完整流程概览（从权限到结果展示）", 24, 132, size=20, weight="800", color=BLUE))
    card_w = 140
    x0 = 24
    y0 = 152
    for i, item in enumerate(flow):
        x = x0 + i * 150
        index, title, bullets, fill, accent = item
        parts.extend(_flow_card(index, title, bullets, x, y0, card_w, 172, fill, accent))
        if i < len(flow) - 1:
            parts.append(_line(x + card_w + 4, y0 + 86, x + 148, y0 + 86, arrow=True))

    y = 390
    parts.extend(_panel("2. 扫描模式", 20, y, 320, 320))
    parts.append(_text("全量扫描（Full Scan）", 42, y + 60, size=15, weight="800", color="#155ab0"))
    parts.extend(_bullets([
        "forceFull = true",
        "数据库为空或 Hash 后端变化",
        "MediaStore version 变化",
        "距离上次全量扫描超过 24 小时",
        "缺少完整视觉媒体权限时强制保守扫描",
    ], 42, y + 86, 30))
    parts.append(_text("增量扫描（Incremental Scan）", 42, y + 204, size=15, weight="800", color="#168b45"))
    parts.extend(_bullets([
        "基于 generationModified 读取变化资源",
        "保存 imageGeneration / videoGeneration / mediaStoreVersion",
        "断点续扫，减少重复计算",
    ], 42, y + 230, 30))

    parts.extend(_panel("3. 资源读取流程（批次大小：500）", 360, y, 650, 320))
    for idx, rng in enumerate(["1-500", "501-1000", "1001-1500", "1501-2000", "2001-2400"]):
        bx = 382 + idx * 122
        parts.append(_rect(bx, y + 58, 104, 172, fill="#fbfdff"))
        parts.append(_text(f"Batch {idx + 1}", bx + 52, y + 82, size=12, weight="800", color=BLUE, anchor="middle"))
        parts.append(_text(f"({rng})", bx + 52, y + 100, size=10, color=MUTED, anchor="middle"))
        for j, step in enumerate(["读取", "MediaAsset", "入库", "指纹", "重复", "相似"]):
            parts.append(_text(f"▣ {step}", bx + 16, y + 130 + j * 18, size=10))
    parts.extend(_bullets([
        "500 是读取批次，不是相似比较边界。",
        "每批处理完成后继续读取下一批，并与历史指纹匹配。",
        "全部批次结束后统一清理和重建结果分组。",
    ], 382, y + 256, 68, size=11))

    parts.extend(_panel("4. 媒体分类规则", 1030, y, 350, 320))
    parts.append(_text("图片：基于 DISPLAY_NAME", 1052, y + 60, size=14, weight="800", color=BLUE))
    parts.extend(_bullets(["screenshot / screen_shot / screen-shot", "screenshots / screen_"], 1052, y + 86, 32, size=12))
    parts.append(_text("视频：基于 DISPLAY_NAME", 1052, y + 150, size=14, weight="800", color=BLUE))
    parts.extend(_bullets(["screen_recording / screen-recording", "screenrecorder / record / capture", "mirror / cast 等录屏语义"], 1052, y + 176, 32, size=12))
    parts.append(_text("聊天图片：基于路径/名称关键词", 1052, y + 260, size=13, weight="800", color="#8a5a00"))
    parts.append(_text("wechat / telegram / snapchat / facebook", 1052, y + 284, size=11, color=MUTED))

    y = 740
    parts.extend(_panel("5. 图片指纹（Image Fingerprint）", 20, y, 330, 380))
    parts.extend(_bullets([
        f"imageHash：{fp.get('imageHash', 'dHash64')}，9 x 8 采样形成 64 bit Long",
        f"colorHash：{fp.get('colorHash', 'rgb_hist_8x3')}，RGB 8 x 3 直方图",
        f"sha256：{fp.get('sha256', True)}，作为字节级重复证据",
        "qualityScore：sharpness / exposure / clipping / resolution",
    ], 42, y + 64, 33, size=12))
    parts.append(_text("新人理解：图片先变成一组可比较的数字，再比较距离。", 42, y + 330, size=12, weight="800", color="#0b68b8"))

    parts.extend(_panel("6. 相似图片识别流程", 370, y, 300, 380))
    steps = ["加载缩略图", "计算 dHash + colorHash", "BK-Tree 查询候选", "读取候选完整指纹", "按阈值做最终判断", "写入 SimilarGroup"]
    sy = y + 66
    for idx, step in enumerate(steps):
        parts.append(_rect(410, sy - 18, 210, 30, fill="#fbfdff"))
        parts.append(_text(step, 515, sy + 2, size=12, anchor="middle"))
        if idx < len(steps) - 1:
            parts.append(_line(515, sy + 14, 515, sy + 30, arrow=True))
        sy += 48
    parts.append(_text("BK-Tree 只负责召回，不负责最终判定。", 394, y + 346, size=12, weight="800", color="#0b68b8"))

    parts.extend(_panel("7. 相似图片阈值（CombinedHash）", 690, y, 330, 380))
    parts.append(_text("普通图片（PHOTO）", 712, y + 60, size=13, weight="800", color=BLUE))
    parts.extend(_table(712, y + 76, [86, 96, 78], 26, [
        ["dHash", "colorHash", "结果"],
        ["0-4", "-", "相似"],
        ["5-10", "≤7", "相似"],
        ["11-17", "≤5", "相似"],
        ["≥18", "-", "不相似"],
    ]))
    parts.append(_text("截图（SCREENSHOT）", 712, y + 226, size=13, weight="800", color=BLUE))
    parts.extend(_table(712, y + 242, [86, 96, 78], 26, [
        ["dHash", "colorHash", "结果"],
        ["0-2", "-", "相似"],
        ["3-10", "≤5", "相似"],
        ["11-15", "≤2", "相似"],
        ["≥16", "-", "不相似"],
    ]))

    parts.extend(_panel("8. 重复 / 相同图片识别", 1040, y, 340, 380))
    parts.append(_text("Duplicate 条件（全部满足）", 1062, y + 60, size=14, weight="800", color="#228144"))
    parts.extend(_bullets([
        "类型相同（PHOTO / SCREENSHOT）",
        "文件大小、宽度、高度相同",
        "编辑状态相同",
        "dHash 完全相同",
        "必要时补算 sha256 作为证据",
    ], 1062, y + 88, 31, size=12, color="#1f7d39"))
    parts.append(_rect(1062, y + 265, 270, 70, fill="#fff7f7", stroke="#f0c8c8"))
    parts.extend(_bullets(["sha256 不同不会阻止进入 Duplicate；它用于区分字节完全相同和视觉重复。"], 1080, y + 292, 30, size=12, color="#c0392b"))

    y = 1140
    parts.extend(_panel("9. 视频指纹（Video Fingerprint）", 20, y, 650, 300))
    parts.extend(_table(42, y + 58, [180, 130], 28, [
        ["抽帧参数", "值"],
        ["MIN_INTERVAL_MS", constants.get("MIN_INTERVAL_MS", "2_000L")],
        ["MAX_INTERVAL_MS", constants.get("MAX_INTERVAL_MS", "10_000L")],
        ["DEFAULT_FRAME_COUNT", constants.get("DEFAULT_FRAME_COUNT", "7")],
        ["MAX_FRAME_COUNT", constants.get("MAX_FRAME_COUNT", "13")],
        ["MAX_FILE_BYTES", constants.get("MAX_FILE_BYTES", "400MB")],
    ]))
    parts.append(_text("帧提取与指纹结构", 390, y + 62, size=14, weight="800", color=BLUE))
    parts.extend(_bullets([
        "API 27+ 优先使用 getScaledFrameAtTime",
        "旧系统回退 getFrameAtTime",
        "每帧生成 CombinedHash",
        f"整体视频帧范围：{video.get('frames', '7-13')}",
        "qualityScore 取可用帧中的较高质量分",
    ], 390, y + 92, 34, size=12))

    parts.extend(_panel("10. 视频相似识别方案", 700, y, 680, 300))
    parts.append(_text("候选查询", 724, y + 60, size=14, weight="800", color=BLUE))
    parts.extend(_bullets([
        "类型相同：VIDEO / SCREEN_RECORDING",
        "state = ACTIVE",
        "video_frame_hashes IS NOT NULL",
        "duration_bucket + aspect_bucket 过滤候选",
    ], 724, y + 88, 34, size=12))
    parts.append(_text("相似判断", 1010, y + 60, size=14, weight="800", color=BLUE))
    parts.extend(_bullets([
        "A 视频每帧与 B 视频每帧比较",
        "至少 2 个 A 帧命中即认为相似",
        "帧级阈值复用截图/视频 CombinedHash 规则",
    ], 1010, y + 88, 32, size=12))
    parts.extend(_table(1010, y + 190, [78, 78, 78], 24, [
        ["dHash", "color", "结果"],
        ["0-2", "-", "相似"],
        ["3-10", "≤5", "相似"],
        ["11-15", "≤2", "相似"],
    ]))

    y = 1460
    parts.extend(_panel("11. 资源状态（state）", 20, y, 620, 300))
    parts.extend(_table(42, y + 58, [120, 220, 120, 120], 34, [
        ["状态", "含义", "何时进入", "何时离开"],
        ["ACTIVE", "资源正常存在，可扫描可展示", "首次入库", "删除流程"],
        ["DELETE_PENDING", "用户触发删除，等待确认", "用户点删除", "确认/恢复"],
        ["DELETED", "已确认删除，结果移除", "系统确认", "不再展示"],
        ["UNAVAILABLE", "资源暂不可读或权限变化", "扫描失败", "重新可读"],
    ]))
    parts.append(_text("fingerprint_status：PENDING / DONE / FAILED / SKIPPED", 42, y + 250, size=12, weight="800", color="#995d00"))

    parts.extend(_panel("12. 分组生成规则", 660, y, 330, 300))
    parts.extend(_bullets([
        "扫描中发现重复/相似就写入 SimilarGroup，用于边扫边展示",
        "扫描完成后按类型重建分组",
        "DuplicateGroup 优先展示真正重复",
        "Anchor 直接规则，不做无限链式扩散",
        "A+B、B+C 不必然合并为 A+B+C",
    ], 682, y + 64, 32, size=12))

    parts.extend(_panel("13. 首页分类（ProductCategory）", 1010, y, 370, 300))
    categories = [
        ("Similar Photos", "#32a852"),
        ("Duplicates", "#e07a2b"),
        ("Similar Screenshots", "#2b79d6"),
        ("Similar Videos", "#3867c8"),
        ("Similar Screen Recordings", "#df5d34"),
        ("Other Screenshots", "#f1aa2c"),
        ("Chat Photos", "#49a0d8"),
        ("Other Screen Recordings", "#e58a3a"),
        ("Other Videos", "#6181a9"),
        ("Other", "#7d8794"),
    ]
    for idx, (name, color) in enumerate(categories):
        cx = 1036 + (idx % 2) * 170
        cy = y + 64 + (idx // 2) * 38
        parts.append(_rect(cx, cy - 13, 14, 14, fill=color, stroke=color, radius=2))
        lines, _ = _multiline(name, cx + 22, cy, 15, size=11, weight="800", color=color)
        parts.extend(lines)

    y = 1780
    parts.extend(_panel("14. 删除安全机制（关键点）", 20, y, 650, 300))
    parts.append(_text("用户删除", 48, y + 78, size=14, weight="800"))
    parts.append(_line(124, y + 73, 220, y + 73, arrow=True))
    parts.append(_text("markDeletePending()", 234, y + 78, size=14, weight="800", color=BLUE))
    parts.append(_line(390, y + 73, 492, y + 73, arrow=True))
    parts.append(_text("提交前校验", 506, y + 78, size=14, weight="800", color="#198754"))
    parts.extend(_bullets(["state = DELETE_PENDING", "revision + 1", "扫描线程后续提交必须重新验证 token"], 234, y + 115, 32, size=12))
    parts.extend(_bullets(["state = ACTIVE ?", "revision = token.revision ?", "不满足则直接放弃提交，避免删除资源复活"], 506, y + 115, 32, size=12))
    parts.append(_circle(306, y + 238, 16, fill="#2fa44f"))
    parts.append(_text("是", 306, y + 244, size=12, weight="800", color="#ffffff", anchor="middle"))
    parts.append(_text("允许写入指纹和分组", 332, y + 244, size=12))
    parts.append(_circle(306, y + 272, 16, fill="#d94b4b"))
    parts.append(_text("否", 306, y + 278, size=12, weight="800", color="#ffffff", anchor="middle"))
    parts.append(_text("不写入任何扫描结果", 332, y + 278, size=12))

    parts.extend(_panel("15. 关键参数与阈值汇总", 700, y, 680, 300))
    parts.extend(_table(724, y + 58, [170, 150], 28, [
        ["参数", "值"],
        ["BATCH_SIZE", constants.get("BATCH_SIZE", "500")],
        ["缩略图尺寸", "约 1024px"],
        ["视频帧尺寸", constants.get("FRAME_SIZE", "512")],
        ["普通视频帧数", constants.get("DEFAULT_FRAME_COUNT", "7")],
        ["最大视频帧数", constants.get("MAX_FRAME_COUNT", "13")],
    ]))
    parts.extend(_table(1080, y + 58, [110, 100, 100], 28, [
        ["类型", "dHash", "colorHash"],
        ["PHOTO", "0-4", "-"],
        ["PHOTO", "5-10", "≤7"],
        ["PHOTO", "11-17", "≤5"],
        ["SCREENSHOT", "3-10", "≤5"],
        ["SCREENSHOT", "11-15", "≤2"],
    ]))

    y = 2100
    parts.extend(_panel("16. 新人阅读顺序与代码证据", 20, y, 1360, 170))
    parts.extend(_bullets([
        "先看 SimilarMediaScanner：理解全局扫描编排。",
        "再看 MediaStoreRepository：理解资源如何被批量枚举。",
        "然后看 HashCalculator / Threshold / HammingBkTree：理解相似判断为什么不是简单文件比较。",
        "最后看 ScanDatabase / ProductCategoryBuilder：理解结果如何持久化并展示到首页。",
    ], 44, y + 58, 94, size=12))
    evidence = _important_files(raw)
    if evidence:
        parts.append(_text("代码证据：", 744, y + 58, size=13, weight="800", color=BLUE))
        for idx, item in enumerate(evidence[:6]):
            lines, _ = _multiline(f"{idx + 1}. {item}", 744, y + 82 + idx * 18, 78, size=10, color=MUTED)
            parts.extend(lines[:1])

    parts.append("</svg>")

    with open(output_path / "final.svg", "w", encoding="utf-8") as f:
        f.write("".join(parts))
