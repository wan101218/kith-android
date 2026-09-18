#!/usr/bin/env python3
"""生成 Kith 的应用内矢量图标（res/drawable/*.xml）。

统一规格：24x24 视口，stroke 线宽 1.8，round 端点。
只做 material-icons-core 里没有的、本项目特有的语义图标。
"""
from pathlib import Path

RES = Path(__file__).resolve().parents[1] / "app/src/main/res/drawable"


def circle(cx, cy, r):
    return f"M{cx},{cy} m-{r},0 a{r},{r} 0 1,0 {r*2},0 a{r},{r} 0 1,0 -{r*2},0"


def stroke_icon(name, paths, width=1.8):
    body = "\n".join(
        f'    <path\n        android:pathData="{d}"\n'
        f'        android:strokeWidth="{width}"\n'
        f'        android:strokeColor="#FF000000"\n'
        f'        android:strokeLineCap="round"\n'
        f'        android:strokeLineJoin="round"\n'
        f'        android:fillColor="#00000000" />'
        for d in paths
    )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n    android:height="24dp"\n'
        '    android:viewportWidth="24"\n    android:viewportHeight="24">\n'
        f"{body}\n</vector>\n"
    )


def fill_icon(name, paths, width=1.8):
    body = "\n".join(
        f'    <path\n        android:pathData="{d}"\n'
        f'        android:fillColor="#FF000000" />'
        for d in paths
    )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n    android:height="24dp"\n'
        '    android:viewportWidth="24"\n    android:viewportHeight="24">\n'
        f"{body}\n</vector>\n"
    )


ICONS = {}

# ── 关系图：树状 ────────────────────────────────────────────────────────────
ICONS["kith_ic_tree"] = stroke_icon("tree", [
    "M12,6 L12,12",
    "M6,12 L18,12",
    "M6,12 L6,16",
    "M18,12 L18,16",
    circle(12, 4, 2.2),
    circle(6, 18.2, 2.2),
    circle(18, 18.2, 2.2),
])

# ── 关系图：链式 ────────────────────────────────────────────────────────────
ICONS["kith_ic_chain"] = stroke_icon("chain", [
    "M6.6,12 L9.4,12",
    "M14.6,12 L17.4,12",
    "M12,4.5 L12,6.2",
    "M12,17.8 L12,19.5",
    circle(4, 12, 2.6),
    circle(20, 12, 2.6),
    circle(12, 12, 3.0),
    circle(12, 3.2, 1.3),
    circle(12, 20.8, 1.3),
])

# ── 旁白：摊开的书 + 星火 ───────────────────────────────────────────────────
ICONS["kith_ic_narrator"] = stroke_icon("narrator", [
    "M12,6.4 C9.8,4.6 6.4,4 3.2,4.6 L3.2,18.4 C6.4,17.8 9.8,18.4 12,20.2"
    " C14.2,18.4 17.6,17.8 20.8,18.4 L20.8,4.6 C17.6,4 14.2,4.6 12,6.4 Z",
    "M12,6.4 L12,20.2",
])

# ── 推进剧情的星火 ──────────────────────────────────────────────────────────
ICONS["kith_ic_spark"] = fill_icon("spark", [
    "M12,2.6 L13.7,9.5 L20.6,11.2 L13.7,12.9 L12,19.8 L10.3,12.9 L3.4,11.2"
    " L10.3,9.5 Z",
])

ICONS["kith_ic_spark_small"] = fill_icon("spark_small", [
    "M12,4 L13.2,9.2 L18.4,10.4 L13.2,11.6 L12,16.8 L10.8,11.6 L5.6,10.4"
    " L10.8,9.2 Z",
])

# ── 表情 ────────────────────────────────────────────────────────────────────
ICONS["kith_ic_sticker"] = stroke_icon("sticker", [
    "M7.5,2.6 L16.5,2.6 A4.9,4.9 0 0 1 21.4,7.5 L21.4,16.5"
    " A4.9,4.9 0 0 1 16.5,21.4 L7.5,21.4 A4.9,4.9 0 0 1 2.6,16.5"
    " L2.6,7.5 A4.9,4.9 0 0 1 7.5,2.6 Z",
    circle(9, 10, 1.25),
    circle(15, 10, 1.25),
    "M8.6,14.6 C9.6,16.2 10.7,16.9 12,16.9 C13.3,16.9 14.4,16.2 15.4,14.6",
])

# ── 转账 ────────────────────────────────────────────────────────────────────
ICONS["kith_ic_transfer"] = stroke_icon("transfer", [
    circle(12, 12, 9.2),
    "M8.6,7.6 L12,12 L15.4,7.6",
    "M12,12 L12,17.2",
    "M9.2,13.4 L14.8,13.4",
    "M9.2,15.3 L14.8,15.3",
])

# ── 导入 / 导出 ─────────────────────────────────────────────────────────────
_tray = "M4,15.6 L4,19 A2,2 0 0 0 6,21 L18,21 A2,2 0 0 0 20,19 L20,15.6"
ICONS["kith_ic_import"] = stroke_icon("import", [
    "M12,3 L12,14",
    "M8,10.2 L12,14.2 L16,10.2",
    _tray,
])
ICONS["kith_ic_export"] = stroke_icon("export", [
    "M12,14.2 L12,3.2",
    "M8,7.2 L12,3.2 L16,7.2",
    _tray,
])

# ── 标签 ────────────────────────────────────────────────────────────────────
ICONS["kith_ic_tag"] = stroke_icon("tag", [
    "M11.4,3.2 L20.8,3.2 L20.8,12.6 L12.6,20.8"
    " A1.6,1.6 0 0 1 10.3,20.8 L3.2,13.7 A1.6,1.6 0 0 1 3.2,11.4 Z",
    circle(17, 7, 1.5),
])

# ── 日志 ────────────────────────────────────────────────────────────────────
ICONS["kith_ic_log"] = stroke_icon("log", [
    "M4.4,6.6 L19.6,6.6",
    "M4.4,12 L19.6,12",
    "M4.4,17.4 L13.4,17.4",
    circle(2.4, 6.6, 0.8),
    circle(2.4, 12, 0.8),
    circle(2.4, 17.4, 0.8),
])

# ── 视觉（眼睛）─────────────────────────────────────────────────────────────
ICONS["kith_ic_vision"] = stroke_icon("vision", [
    "M12,5.2 C6.2,5.2 2.5,10.2 1.4,12 C2.5,13.8 6.2,18.8 12,18.8"
    " C17.8,18.8 21.5,13.8 22.6,12 C21.5,10.2 17.8,5.2 12,5.2 Z",
    circle(12, 12, 3.1),
])

# ── 模型（芯片）─────────────────────────────────────────────────────────────
ICONS["kith_ic_chip"] = stroke_icon("chip", [
    "M8.2,6.6 L15.8,6.6 A1.8,1.8 0 0 1 17.6,8.4 L17.6,15.6"
    " A1.8,1.8 0 0 1 15.8,17.4 L8.2,17.4 A1.8,1.8 0 0 1 6.4,15.6"
    " L6.4,8.4 A1.8,1.8 0 0 1 8.2,6.6 Z",
    circle(12, 12, 2.5),
    "M9.2,3 L9.2,6.6", "M14.8,3 L14.8,6.6",
    "M9.2,17.4 L9.2,21", "M14.8,17.4 L14.8,21",
    "M3,9.2 L6.4,9.2", "M3,14.8 L6.4,14.8",
    "M17.6,9.2 L21,9.2", "M17.6,14.8 L21,14.8",
])

# ── 文生图 ──────────────────────────────────────────────────────────────────
ICONS["kith_ic_imagegen"] = stroke_icon("imagegen", [
    "M3.2,6.2 A2,2 0 0 1 5.2,4.2 L14.8,4.2 A2,2 0 0 1 16.8,6.2"
    " L16.8,17.8 A2,2 0 0 1 14.8,19.8 L5.2,19.8 A2,2 0 0 1 3.2,17.8 Z",
    "M3.2,15.6 L8.2,10.8 L12.4,14.8 L16.8,11.2",
    circle(8.6, 8.4, 1.5),
    "M20.4,2.4 L21.2,5.2 L24,6 L21.2,6.8 L20.4,9.6 L19.6,6.8 L16.8,6 L19.6,5.2 Z",
])

# ── 人物 ────────────────────────────────────────────────────────────────────
ICONS["kith_ic_people"] = stroke_icon("people", [
    circle(9, 8, 3.4),
    "M3.2,20 C3.2,16.1 5.8,13.8 9,13.8 C12.2,13.8 14.8,16.1 14.8,20",
    "M15.6,4.9 A3.1,3.1 0 0 1 15.6,11.1",
    "M16.6,13.9 C19.2,14.4 20.9,16.5 20.9,20",
])

# ── 上帝视角 ────────────────────────────────────────────────────────────────
ICONS["kith_ic_godview"] = stroke_icon("godview", [
    "M12,7.4 C7.6,7.4 4.6,11.4 3.7,12.9 C4.6,14.4 7.6,18.4 12,18.4"
    " C16.4,18.4 19.4,14.4 20.3,12.9 C19.4,11.4 16.4,7.4 12,7.4 Z",
    circle(12, 12.9, 2.6),
    "M12,2.2 L12,4.2", "M4.4,4.6 L5.8,6", "M19.6,4.6 L18.2,6",
    "M2.2,12.9 L4,12.9", "M21.8,12.9 L20,12.9",
])

# ── 设置里的杂项 ────────────────────────────────────────────────────────────
ICONS["kith_ic_dice"] = stroke_icon("dice", [
    "M5.4,3.2 L18.6,3.2 A2.2,2.2 0 0 1 20.8,5.4 L20.8,18.6"
    " A2.2,2.2 0 0 1 18.6,20.8 L5.4,20.8 A2.2,2.2 0 0 1 3.2,18.6"
    " L3.2,5.4 A2.2,2.2 0 0 1 5.4,3.2 Z",
    circle(8.4, 8.4, 1.3), circle(12, 12, 1.3), circle(15.6, 15.6, 1.3),
])

ICONS["kith_ic_globe"] = stroke_icon("globe", [
    circle(12, 12, 9.2),
    "M2.8,12 L21.2,12",
    "M12,2.8 C15.2,6.4 15.2,17.6 12,21.2 C8.8,17.6 8.8,6.4 12,2.8 Z",
])

ICONS["kith_ic_lock"] = stroke_icon("lock", [
    "M6.2,10.4 L17.8,10.4 A1.8,1.8 0 0 1 19.6,12.2 L19.6,19.4"
    " A1.8,1.8 0 0 1 17.8,21.2 L6.2,21.2 A1.8,1.8 0 0 1 4.4,19.4"
    " L4.4,12.2 A1.8,1.8 0 0 1 6.2,10.4 Z",
    "M7.8,10.4 L7.8,7.6 A4.2,4.2 0 0 1 16.2,7.6 L16.2,10.4",
])

ICONS["kith_ic_flask"] = stroke_icon("flask", [
    "M9.4,2.8 L14.6,2.8",
    "M10.4,2.8 L10.4,9 L4.8,18.6 A1.6,1.6 0 0 0 6.2,21 L17.8,21"
    " A1.6,1.6 0 0 0 19.2,18.6 L13.6,9 L13.6,2.8",
    "M7.2,15.4 L16.8,15.4",
])


def main():
    RES.mkdir(parents=True, exist_ok=True)
    for name, xml in ICONS.items():
        (RES / f"{name}.xml").write_text(xml, encoding="utf-8")
        print(f"[icon] {name}.xml")
    print(f"共 {len(ICONS)} 个")


if __name__ == "__main__":
    main()
