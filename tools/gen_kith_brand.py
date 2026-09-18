#!/usr/bin/env python3
"""Kith 品牌资源生成器。

生成内容：
  1. app/src/main/res/mipmap-*/ic_launcher.webp        传统方形图标（圆角）
  2. app/src/main/res/mipmap-*/ic_launcher_round.webp  传统圆形图标
  3. app/src/main/res/drawable/ic_launcher_background.xml  自适应图标背景层
  4. app/src/main/res/drawable/ic_launcher_foreground.xml  自适应图标前景层
  5. docs/brand/*.png                                  品牌展示图（图标 / 横版组合）

设计概念 —— "Kith 关系星群"
  「Kith」源自古英语 kith and kin，意为亲友与故交。
  图形是一簇由连线构成的节点群：中心一枚琥珀色节点代表「你」，
  外围五枚白色节点沿一条开放链依次相连，代表社会关系链。
  中心与所有外围节点相连 —— 你与每一个人都有联系。

几何基于 Android 自适应图标的 108x108 视口，
所有内容收在中心 r≈31 的安全圆内（系统蒙版安全区 r=33）。
"""
from __future__ import annotations

import os
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

# ── 品牌色 ──────────────────────────────────────────────────────────────────
INK_TOP = (25, 28, 38)        # #191C26 渐变顶
INK_BOTTOM = (14, 16, 21)     # #0E1015 渐变底
AMBER = (245, 181, 68)        # #F5B544 主强调色
WHITE = (255, 255, 255)

# ── 图形几何（108 视口）─────────────────────────────────────────────────────
HUB = (52.0, 47.0, 11.0)                      # 中心节点 x, y, r
SATELLITES = [
    (30.0, 60.0, 8.0, 0.94),
    (48.0, 75.0, 7.5, 0.84),
    (73.0, 64.0, 8.5, 0.90),
    (66.0, 34.0, 7.0, 0.76),
]
# 中心 → 全部外围
SPOKES = [(HUB[0], HUB[1], s[0], s[1]) for s in SATELLITES]
# 外围之间的开放链，代表「关系链」
RING = [
    (SATELLITES[0][0], SATELLITES[0][1], SATELLITES[1][0], SATELLITES[1][1]),
    (SATELLITES[1][0], SATELLITES[1][1], SATELLITES[2][0], SATELLITES[2][1]),
    (SATELLITES[2][0], SATELLITES[2][1], SATELLITES[3][0], SATELLITES[3][1]),
]
LINE_W = 3.2
GLOW_SCALE = 2.15

VIEWPORT = 108.0
SS = 12  # 超采样倍率


def _bg_gradient(size: int) -> Image.Image:
    """竖向渐变底 + 中心暖色柔光。"""
    img = Image.new("RGB", (1, size))
    px = img.load()
    for y in range(size):
        t = y / max(size - 1, 1)
        px[0, y] = tuple(
            round(INK_TOP[i] + (INK_BOTTOM[i] - INK_TOP[i]) * t) for i in range(3)
        )
    img = img.resize((size, size), Image.BILINEAR).convert("RGBA")

    # 中心偏上的琥珀色柔光
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    cx, cy = size * (HUB[0] / VIEWPORT), size * (HUB[1] / VIEWPORT)
    gr = size * 0.34
    gd.ellipse([cx - gr, cy - gr, cx + gr, cy + gr], fill=AMBER + (46,))
    glow = glow.filter(ImageFilter.GaussianBlur(size * 0.09))
    return Image.alpha_composite(img, glow)


def _draw_mark(canvas: Image.Image, scale: float) -> None:
    """在 canvas 上绘制节点群。scale = 108 视口 → 像素的缩放系数。"""
    d = ImageDraw.Draw(canvas, "RGBA")
    lw = max(1, round(LINE_W * scale))

    def P(v: float) -> float:
        return v * scale

    # 中心节点的柔光（独立图层模糊，避免硬边圆盘）
    hx, hy, hr = HUB
    gl = hr * GLOW_SCALE
    glow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse(
        [P(hx - gl), P(hy - gl), P(hx + gl), P(hy + gl)], fill=AMBER + (66,)
    )
    canvas.alpha_composite(glow.filter(ImageFilter.GaussianBlur(hr * scale * 0.50)))

    # 连线
    for x1, y1, x2, y2 in SPOKES:
        d.line([P(x1), P(y1), P(x2), P(y2)], fill=WHITE + (60,), width=lw)
    for x1, y1, x2, y2 in RING:
        d.line([P(x1), P(y1), P(x2), P(y2)], fill=WHITE + (104,), width=lw)

    # 外围节点
    for x, y, r, alpha in SATELLITES:
        d.ellipse(
            [P(x - r), P(y - r), P(x + r), P(y + r)],
            fill=WHITE + (int(alpha * 255),),
        )

    # 中心节点
    d.ellipse([P(hx - hr), P(hy - hr), P(hx + hr), P(hy + hr)], fill=AMBER + (255,))


def render_square(size: int, corner_ratio: float | None) -> Image.Image:
    """正方形图标。corner_ratio=None 表示不做圆角（用于自适应前景/背景）。"""
    px = int(VIEWPORT * SS)
    base = _bg_gradient(px)
    _draw_mark(base, SS)
    out = base.resize((size, size), Image.LANCZOS)

    if corner_ratio is None:
        return out

    # 圆角遮罩
    r = int(size * corner_ratio)
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size * 4 - 1, size * 4 - 1], radius=r * 4, fill=255
    )
    mask = mask.resize((size, size), Image.LANCZOS)
    out.putalpha(mask)
    return out


def render_round(size: int) -> Image.Image:
    """纯圆形图标。"""
    px = int(VIEWPORT * SS)
    base = _bg_gradient(px)
    _draw_mark(base, SS)
    img = base.resize((size, size), Image.LANCZOS)

    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size * 4 - 1, size * 4 - 1], fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    img.putalpha(mask)
    return img


# ── 自适应图标矢量 XML ──────────────────────────────────────────────────────
def bg_vector_xml() -> str:
    return """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:startX="54" android:startY="0"
                android:endX="54" android:endY="108"
                android:type="linear">
                <item android:color="#FF191C26" android:offset="0.0" />
                <item android:color="#FF0E1015" android:offset="1.0" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
"""


def fg_vector_xml() -> str:
    parts: list[str] = []

    # 连线
    for x1, y1, x2, y2 in SPOKES:
        parts.append(
            f'    <path android:pathData="M{x1:g},{y1:g}L{x2:g},{y2:g}"\n'
            f'        android:strokeWidth="2.4" android:strokeColor="#3AFFFFFF" />'
        )
    for x1, y1, x2, y2 in RING:
        parts.append(
            f'    <path android:pathData="M{x1:g},{y1:g}L{x2:g},{y2:g}"\n'
            f'        android:strokeWidth="2.4" android:strokeColor="#60FFFFFF" />'
        )
    # 外围节点
    for x, y, r, alpha in SATELLITES:
        a = format(round(alpha * 255), "02X")
        parts.append(
            f'    <path android:pathData="M{x:g},{y:g}m-{r:g},0a{r:g},{r:g} 0 1,0 {r * 2:g},0'
            f'a{r:g},{r:g} 0 1,0 -{r * 2:g},0"\n'
            f'        android:fillColor="#{a}FFFFFF" />'
        )
    # 中心节点柔光 + 实心
    hx, hy, hr = HUB
    gl = hr * GLOW_SCALE
    parts.append(
        f'    <path android:pathData="M{hx:g},{hy:g}m-{gl:g},0a{gl:g},{gl:g} 0 1,0 {gl * 2:g},0'
        f'a{gl:g},{gl:g} 0 1,0 -{gl * 2:g},0">\n'
        f'        <aapt:attr name="android:fillColor">\n'
        f'            <gradient android:type="radial"\n'
        f'                android:centerX="{hx:g}" android:centerY="{hy:g}"\n'
        f'                android:gradientRadius="{gl:g}">\n'
        f'                <item android:color="#3DF5B544" android:offset="0.0" />\n'
        f'                <item android:color="#00F5B544" android:offset="1.0" />\n'
        f'            </gradient>\n'
        f'        </aapt:attr>\n'
        f'    </path>'
    )
    parts.append(
        f'    <path android:pathData="M{hx:g},{hy:g}m-{hr:g},0a{hr:g},{hr:g} 0 1,0 {hr * 2:g},0'
        f'a{hr:g},{hr:g} 0 1,0 -{hr * 2:g},0"\n'
        f'        android:fillColor="#FFF5B544" />'
    )

    body = "\n".join(parts)
    return f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- Kith 关系星群：中心节点为「你」，外围为关系链上的他人 -->
{body}
</vector>
"""


# ── 品牌展示图 ──────────────────────────────────────────────────────────────
def _font(size: int, bold: bool = True) -> ImageFont.FreeTypeFont:
    cands = [
        "C:/Windows/Fonts/segoeuib.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf",
        "C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf",
        "C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc",
    ]
    for c in cands:
        if os.path.exists(c):
            try:
                return ImageFont.truetype(c, size)
            except OSError:
                continue
    return ImageFont.load_default()


def render_wordmark(icon: Image.Image, height: int = 320) -> Image.Image:
    pad = int(height * 0.30)
    size = int(height * 0.56)
    ic = icon.resize((size, size), Image.LANCZOS)

    canvas = Image.new("RGBA", (2000, height), INK_BOTTOM + (255,))
    cd = ImageDraw.Draw(canvas, "RGBA")
    cd.rounded_rectangle(
        [0, 0, 1999, height - 1], radius=int(height * 0.22), fill=INK_BOTTOM + (255,)
    )

    f_name = _font(int(height * 0.40), bold=True)
    f_sub = _font(int(height * 0.135), bold=False)

    x = pad
    canvas.alpha_composite(ic.convert("RGBA"), (x, (height - size) // 2))
    x += size + int(height * 0.16)

    ty = height / 2
    cd.text((x, ty - height * 0.055), "Kith", font=f_name, fill=WHITE + (255,), anchor="lm")
    cd.text(
        (x + int(height * 0.02), ty + height * 0.175),
        "AI 社会模拟    kith and kin",
        font=f_sub,
        fill=(154, 160, 178, 255),
        anchor="lm",
    )

    # 裁掉右侧多余留白
    xs = [px for px in range(canvas.width - 1, 0, -1)
          if canvas.getpixel((px, height // 2))[:3] != INK_BOTTOM]
    right = min(canvas.width, (max(xs) if xs else x) + pad)
    return canvas.crop((0, 0, right, height))


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    res = root / "app" / "src" / "main" / "res"
    docs = root / "docs" / "brand"
    docs.mkdir(parents=True, exist_ok=True)

    densities = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }

    for name, px in densities.items():
        d = res / f"mipmap-{name}"
        d.mkdir(parents=True, exist_ok=True)

        sq = render_square(px, corner_ratio=0.22)
        sq.save(d / "ic_launcher.webp", "WEBP", lossless=True, quality=100)

        rd = render_round(px)
        rd.save(d / "ic_launcher_round.webp", "WEBP", lossless=True, quality=100)
        print(f"[icon] {name:9s} {px:3d}px  -> ic_launcher.webp + ic_launcher_round.webp")

    # 矢量自适应图标
    (res / "drawable").mkdir(parents=True, exist_ok=True)
    (res / "drawable" / "ic_launcher_background.xml").write_text(
        bg_vector_xml(), encoding="utf-8"
    )
    (res / "drawable" / "ic_launcher_foreground.xml").write_text(
        fg_vector_xml(), encoding="utf-8"
    )
    print("[icon] drawable/ic_launcher_background.xml + ic_launcher_foreground.xml")

    # 自适应图标清单（minSdk 33，仅需 anydpi 即可，保留兼容目录）
    for d in (res / "mipmap-anydpi", res / "mipmap-anydpi-v26"):
        d.mkdir(parents=True, exist_ok=True)
        for fn in ("ic_launcher.xml", "ic_launcher_round.xml"):
            (d / fn).write_text(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/ic_launcher_background" />\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_foreground" />\n'
                "</adaptive-icon>\n",
                encoding="utf-8",
            )
    print("[icon] mipmap-anydpi{,-v26}/ic_launcher{,_round}.xml")

    # 品牌展示图
    master = render_square(1024, corner_ratio=None)
    master.save(docs / "kith_icon_1024.png")
    render_wordmark(master).save(docs / "kith_wordmark.png")
    print(f"[brand] {docs}")


if __name__ == "__main__":
    main()
