"""NovelMaker 图标生成：单一母版 → PC (PNG/ICO) + Android (drawable/mipmap)

设计（与既有品牌一致）：
- 圆角方形底 + 对角渐变（右上 #FF7AD1 → 左下 #D777F5）
- 白色字母 N（左竖 + 对角斜线 + 右竖），几何构造，保证各尺寸下清晰

产出：
  desktopApp/src/main/resources/novelmaker.png   256×256 窗口图标
  desktopApp/src/main/resources/novelmaker.ico   多尺寸安装包图标
  androidApp/src/main/res/mipmap-*/ic_launcher.png / ic_launcher_round.png
        （API 24–25 的传统图标，按密度生成）
  androidApp/src/main/res/drawable-*/ic_launcher_foreground.png
  androidApp/src/main/res/drawable-*/ic_launcher_background.png
        （API 26+ 自适应图标分层，108dp 视口）

自适应图标说明：108dp 画布中，系统只保证中心 72dp 可见，
因此前景层内容需按安全区缩放，背景层需要覆盖整块画布（由启动器裁切形状）。
"""

import os

from PIL import Image, ImageDraw

# ── 母版参数（单位坐标系 108×108，与 Android 视口一致） ──
UNIT = 108.0
CORNER_RADIUS = 12.0

GRAD_START = (0xFF, 0x7A, 0xD1)   # 右上
GRAD_END = (0xD7, 0x77, 0xF5)     # 左下

# N 字形几何
N_STEM_LEFT = (43.6, 35.0, 50.8, 73.0)
N_STEM_RIGHT = (57.2, 35.0, 64.4, 73.0)
N_DIAGONAL = [(43.6, 73.0), (50.8, 73.0), (57.2, 35.0), (64.4, 35.0)]

# 自适应图标安全区：内容缩放到中心 72/108
SAFE_SCALE = 72.0 / 108.0

# 字形放大倍数（围绕画布中心等比放大）。
# 母版里 N 只占画布宽度的 48%，视觉上偏小；放大后在所有尺寸下更醒目。
GLYPH_GAIN = 1.22

# 传统图标（API 24–25）字形的额外留白比例
LEGACY_MARGIN_RATIO = 0.085

# 字形几何的中心（放大时作为不动点）
GLYPH_CENTER = (
    (min(N_STEM_LEFT[0], N_DIAGONAL[0][0]) + max(N_STEM_RIGHT[2], N_DIAGONAL[2][0])) / 2.0,
    (min(N_STEM_LEFT[1], N_DIAGONAL[2][1]) + max(N_STEM_LEFT[3], N_DIAGONAL[0][1])) / 2.0,
)

SS = 4  # 超采样倍数


def lerp(a, b, t):
    return a + (b - a) * t


def make_gradient(size_px):
    """对角渐变（右上 → 左下）。"""
    img = Image.new("RGB", (size_px, size_px))
    px = img.load()
    denom = max(size_px - 1, 1)
    for y in range(size_px):
        ty = y / denom
        row = []
        for x in range(size_px):
            t = (x / denom + ty) / 2.0
            row.append((
                int(round(lerp(GRAD_START[0], GRAD_END[0], t))),
                int(round(lerp(GRAD_START[1], GRAD_END[1], t))),
                int(round(lerp(GRAD_START[2], GRAD_END[2], t))),
            ))
        for x, c in enumerate(row):
            px[x, y] = c
    return img


def render_icon(size_px, rounded=True, content_scale=1.0, gain=GLYPH_GAIN, margin_ratio=0.0):
    """渲染图标。

    Args:
        size_px: 输出像素边长
        rounded: 是否裁成圆角方形（False 时铺满整块画布，供自适应图标背景层用）
        content_scale: 内容缩放（自适应图标前景层需要缩进安全区）；0 表示不画字形
        gain: 字形额外放大倍数（围绕字形中心等比放大）
        margin_ratio: 字形额外留白比例
    """
    big = size_px * SS
    grad = make_gradient(big)

    if rounded:
        mask = Image.new("L", (big, big), 0)
        ImageDraw.Draw(mask).rounded_rectangle(
            [0, 0, big - 1, big - 1], radius=CORNER_RADIUS / UNIT * big, fill=255
        )
    else:
        mask = Image.new("L", (big, big), 255)

    icon = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    icon.paste(grad, (0, 0), mask)

    if content_scale > 0:
        white = render_glyph_mask(big, content_scale, gain, margin_ratio)
        glyph = Image.new("RGBA", (big, big), (255, 255, 255, 255))
        icon = Image.composite(glyph, icon, white)

    return icon.resize((size_px, size_px), Image.LANCZOS)


def render_glyph_mask(big, scale, gain, margin_ratio):
    """生成白色字形的灰度遮罩（超采样尺寸）。

    变换顺序：单位坐标 → 围绕字形中心放大 gain 倍 → 缩放到 scale 并居中 →
    再按 margin_ratio 收缩。这样 gain 是纯粹的「视觉放大」，与安全区约束解耦。
    """
    white = Image.new("L", (big, big), 0)
    wd = ImageDraw.Draw(white)
    cx, cy = GLYPH_CENTER
    usable = 1.0 - margin_ratio * 2.0
    final_scale = scale * gain * usable
    offset = (UNIT * (1.0 - final_scale)) / 2.0

    def tx(v):
        """单位坐标 → 像素：先绕字形中心放大，再整体缩放居中。"""
        return (offset + (cx + (v - cx) * gain) * final_scale) / UNIT * big

    def rect(box):
        wd.rectangle(
            [tx(box[0]), tx(box[1]), tx(box[2]) - 1, tx(box[3]) - 1],
            fill=255,
        )

    rect(N_STEM_LEFT)
    rect(N_STEM_RIGHT)
    wd.polygon([(tx(x), tx(y)) for x, y in N_DIAGONAL], fill=255)
    return white


def render_glyph_only(size_px, scale=SAFE_SCALE, margin_ratio=0.0, gain=GLYPH_GAIN):
    """只渲染白色 N（透明底），用于自适应图标前景层与单色层。

    Args:
        scale: 内容缩放倍数
        margin_ratio: 额外留白比例（0.1 表示四周各留 10% 边距）
        gain: 字形额外放大倍数
    """
    big = size_px * SS
    white = render_glyph_mask(big, scale, gain, margin_ratio)
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    glyph = Image.new("RGBA", (big, big), (255, 255, 255, 255))
    img = Image.composite(glyph, img, white)
    return img.resize((size_px, size_px), Image.LANCZOS)


def compose_legacy(size_px, round_shape=False):
    """传统 launcher 图标（API 24–25）：渐变底 + 较大字形的 N，填满画布。"""
    base = render_icon(size_px, rounded=not round_shape, content_scale=0.0)
    if round_shape:
        mask = Image.new("L", (size_px * SS, size_px * SS), 0)
        ImageDraw.Draw(mask).ellipse(
            [0, 0, size_px * SS - 1, size_px * SS - 1], fill=255
        )
        mask = mask.resize((size_px, size_px), Image.LANCZOS)
        clipped = Image.new("RGBA", (size_px, size_px), (0, 0, 0, 0))
        clipped.paste(base, (0, 0), mask)
        base = clipped
    glyph = render_glyph_only(size_px, scale=1.0, margin_ratio=LEGACY_MARGIN_RATIO)
    return Image.alpha_composite(base, glyph)


# 密度 → 缩放倍数
DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}


def main():
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    res_pc = os.path.join(root, "desktopApp", "src", "main", "resources")
    res_android = os.path.join(root, "androidApp", "src", "main", "res")

    # ── PC ──
    png256 = render_icon(256)
    png256.save(os.path.join(res_pc, "novelmaker.png"), "PNG")
    print("wrote novelmaker.png 256x256")

    ico_sizes = [256, 128, 64, 48, 32, 24, 16]
    frames = [render_icon(s) for s in ico_sizes]
    frames[0].save(
        os.path.join(res_pc, "novelmaker.ico"),
        format="ICO",
        sizes=[(s, s) for s in ico_sizes],
        append_images=frames[1:],
    )
    print("wrote novelmaker.ico %s" % ico_sizes)

    # ── Android：传统图标（API 24–25）与自适应图标分层（API 26+） ──
    for density, scale in DENSITIES.items():
        # 传统 launcher 图标（48dp 基准）——字形与 PC 端观感一致
        legacy_px = int(round(48 * scale))
        mipmap_dir = os.path.join(res_android, f"mipmap-{density}")
        os.makedirs(mipmap_dir, exist_ok=True)
        compose_legacy(legacy_px).save(os.path.join(mipmap_dir, "ic_launcher.png"), "PNG")
        compose_legacy(legacy_px, round_shape=True).save(
            os.path.join(mipmap_dir, "ic_launcher_round.png"), "PNG"
        )

        # 自适应图标分层（108dp 基准）
        adaptive_px = int(round(108 * scale))
        drawable_dir = os.path.join(res_android, f"drawable-{density}")
        os.makedirs(drawable_dir, exist_ok=True)
        # 背景层：铺满画布（启动器负责裁形）
        render_icon(adaptive_px, rounded=False, content_scale=0.0).save(
            os.path.join(drawable_dir, "ic_launcher_background.png"), "PNG"
        )
        # 前景层：白色 N，缩进安全区
        render_glyph_only(adaptive_px).save(
            os.path.join(drawable_dir, "ic_launcher_foreground.png"), "PNG"
        )
        # 单色层（Android 13+ 主题图标）：只保留字形
        render_glyph_only(adaptive_px).save(
            os.path.join(drawable_dir, "ic_launcher_monochrome.png"), "PNG"
        )
        print(f"android {density}: legacy {legacy_px}px, adaptive {adaptive_px}px")

    # 预览图
    preview = Image.new("RGBA", (256 + 128 + 64 + 32 + 16 + 40, 256), (240, 240, 245, 255))
    x = 0
    for s in [256, 128, 64, 32, 16]:
        img = render_icon(s)
        preview.paste(img, (x, 0), img)
        x += s + 10
    preview.save(os.path.join(os.path.dirname(__file__), "icon_preview.png"), "PNG")
    print("wrote icon_preview.png")


if __name__ == "__main__":
    main()
