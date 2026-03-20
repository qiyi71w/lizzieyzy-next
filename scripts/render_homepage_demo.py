from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
INPUT = ROOT / 'screenshot_en.png'
OUTPUT_GIF = ROOT / 'assets' / 'fox-id-demo.gif'
OUTPUT_COVER = ROOT / 'assets' / 'fox-id-demo-cover.png'

TARGET = (1120, 714)
SOURCE = (1500, 956)
SX = TARGET[0] / SOURCE[0]
SY = TARGET[1] / SOURCE[1]

COLOR_BG = '#121212'
COLOR_TEXT = '#191613'
COLOR_MUTED = '#6F5A33'
COLOR_CREAM = '#FFF8EA'
COLOR_PANEL = '#FFFDF8'
COLOR_PANEL_EDGE = '#F0DFC1'
COLOR_GREEN = '#1B4D3E'
COLOR_BROWN = '#8C5A24'
COLOR_BLUE = '#2F4858'
COLOR_SAGE = '#4E8B57'
COLOR_GOLD = '#B77A23'

FONT_BOLD = '/System/Library/Fonts/Supplemental/Arial Black.ttf'
FONT_SEMIBOLD = '/System/Library/Fonts/Supplemental/Arial Bold.ttf'
FONT_REG = '/System/Library/Fonts/Supplemental/Arial.ttf'


def load_font(path: str, size: int):
    return ImageFont.truetype(path, size=size)


def scale_rect(rect):
    x, y, w, h = rect
    return (int(x * SX), int(y * SY), int((x + w) * SX), int((y + h) * SY))


def rounded_overlay(size, xy, radius, fill, shadow=(0, 12, 22, (97, 63, 19, 55)), stroke=None):
    layer = Image.new('RGBA', size, (0, 0, 0, 0))
    sx, sy, blur, shadow_color = shadow
    if shadow_color[3] > 0:
        shadow_layer = Image.new('RGBA', size, (0, 0, 0, 0))
        sdraw = ImageDraw.Draw(shadow_layer)
        x0, y0, x1, y1 = xy
        sdraw.rounded_rectangle((x0 + sx, y0 + sy, x1 + sx, y1 + sy), radius=radius, fill=shadow_color)
        shadow_layer = shadow_layer.filter(ImageFilter.GaussianBlur(blur))
        layer = Image.alpha_composite(layer, shadow_layer)
    draw = ImageDraw.Draw(layer)
    draw.rounded_rectangle(xy, radius=radius, fill=fill, outline=stroke)
    return layer


def draw_text(draw, pos, text, font, fill, anchor='la'):
    draw.text(pos, text, font=font, fill=fill, anchor=anchor)


def highlight_overlay(size, rects, pulse=0.8):
    overlay = Image.new('RGBA', size, (13, 11, 9, 128))
    mask = Image.new('L', size, 132)
    mdraw = ImageDraw.Draw(mask)
    for rect in rects:
        mdraw.rounded_rectangle(rect, radius=22, fill=12)
    overlay.putalpha(mask)

    glow = Image.new('RGBA', size, (0, 0, 0, 0))
    gdraw = ImageDraw.Draw(glow)
    for rect in rects:
        x0, y0, x1, y1 = rect
        for inset, width, alpha in ((0, 4, int(225 * pulse)), (8, 2, int(140 * pulse))):
            gdraw.rounded_rectangle((x0 - inset, y0 - inset, x1 + inset, y1 + inset), radius=24, outline=(255, 242, 214, alpha), width=width)
    glow = glow.filter(ImageFilter.GaussianBlur(1))
    return overlay, glow


def draw_chip(draw, xy, text, bg, fg, font):
    x0, y0, x1, y1 = xy
    draw.rounded_rectangle(xy, radius=(y1 - y0) // 2, fill=bg)
    draw_text(draw, ((x0 + x1) // 2, y0 + (y1 - y0) // 2 + 1), text, font, fg, anchor='mm')


def draw_header(canvas):
    header = rounded_overlay(canvas.size, (44, 34, 566, 132), 28, (255, 250, 239, 235), shadow=(0, 10, 18, (84, 56, 18, 45)), stroke=(245, 226, 190, 255))
    canvas.alpha_composite(header)
    draw = ImageDraw.Draw(canvas)
    chip_font = load_font(FONT_SEMIBOLD, 15)
    title_font = load_font(FONT_BOLD, 34)
    sub_font = load_font(FONT_REG, 20)
    draw_chip(draw, (70, 54, 176, 84), 'Quick Demo', (27, 77, 62, 255), (247, 240, 223, 255), chip_font)
    draw_text(draw, (70, 108), 'From Fox ID to KataGo review', title_font, COLOR_TEXT)
    draw_text(draw, (70, 134), 'A short preview of the most common path after download.', sub_font, COLOR_MUTED)


def draw_step_bar(canvas, active_step):
    draw = ImageDraw.Draw(canvas)
    bar = rounded_overlay(canvas.size, (196, 646, 924, 694), 24, (255, 250, 241, 220), shadow=(0, 10, 18, (94, 63, 20, 35)), stroke=(242, 223, 187, 255))
    canvas.alpha_composite(bar)
    draw.line((252, 670, 868, 670), fill=(227, 192, 123, 160), width=6)
    step_font = load_font(FONT_SEMIBOLD, 16)
    num_font = load_font(FONT_BOLD, 16)
    steps = [
        ('01', 'Package', COLOR_GREEN),
        ('02', 'Sync', COLOR_BROWN),
        ('03', 'Fox ID', COLOR_BLUE),
        ('04', 'Review', COLOR_SAGE),
    ]
    xs = [252, 458, 664, 870]
    for idx, ((num, label, color), cx) in enumerate(zip(steps, xs), start=1):
        r = 28 if idx == active_step else 22
        draw.ellipse((cx - r, 642 - r + 28, cx + r, 642 + r + 28), fill=color if idx == active_step else (232, 220, 196, 255), outline=None)
        draw_text(draw, (cx, 670), num, num_font, (247, 240, 223, 255) if idx == active_step else COLOR_MUTED, anchor='mm')
        label_fill = color if idx == active_step else COLOR_MUTED
        draw_text(draw, (cx, 698), label, step_font, label_fill, anchor='ms')


def draw_callout(canvas, step_num, title, lines, accent, chips=None):
    panel = rounded_overlay(canvas.size, (52, 168, 478, 404), 34, (255, 253, 247, 240), shadow=(0, 16, 26, (94, 64, 19, 50)), stroke=(241, 223, 194, 255))
    canvas.alpha_composite(panel)
    draw = ImageDraw.Draw(canvas)
    chip_font = load_font(FONT_SEMIBOLD, 16)
    title_font = load_font(FONT_BOLD, 32)
    body_font = load_font(FONT_SEMIBOLD, 21)
    small_font = load_font(FONT_REG, 18)

    draw_chip(draw, (76, 192, 182, 226), f'Step {step_num} of 4', accent, (248, 241, 225, 255), chip_font)
    draw_text(draw, (76, 274), title, title_font, COLOR_TEXT)
    y = 324
    for line in lines:
        draw_text(draw, (76, y), line, body_font, COLOR_MUTED)
        y += 30
    if chips:
        x = 76
        y = 364
        for text, selected in chips:
            font = chip_font if len(text) <= 12 else small_font
            tw = draw.textlength(text, font=font)
            w = int(tw + 34)
            if x + w > 450:
                x = 76
                y += 42
            bg = accent if selected else (238, 228, 208, 230)
            fg = (248, 241, 225, 255) if selected else COLOR_MUTED
            draw_chip(draw, (x, y, x + w, y + 30), text, bg, fg, font)
            x += w + 12


def draw_step2_badge(canvas, rect):
    draw = ImageDraw.Draw(canvas)
    x0, y0, x1, y1 = rect
    badge = rounded_overlay(canvas.size, (x1 - 70, y1 + 16, x1 + 126, y1 + 64), 24, (255, 253, 247, 238), shadow=(0, 10, 16, (94, 64, 19, 40)), stroke=(241, 223, 194, 255))
    canvas.alpha_composite(badge)
    font = load_font(FONT_SEMIBOLD, 18)
    draw_text(draw, (x1 + 28, y1 + 46), 'Open Sync', font, COLOR_BROWN, anchor='mm')


def draw_step3_modal(canvas):
    modal = rounded_overlay(canvas.size, (626, 184, 1030, 430), 28, (255, 253, 247, 246), shadow=(0, 18, 24, (71, 49, 19, 55)), stroke=(241, 223, 194, 255))
    canvas.alpha_composite(modal)
    draw = ImageDraw.Draw(canvas)
    chip_font = load_font(FONT_SEMIBOLD, 16)
    title_font = load_font(FONT_BOLD, 28)
    body_font = load_font(FONT_REG, 19)
    button_font = load_font(FONT_SEMIBOLD, 18)

    draw_chip(draw, (652, 206, 744, 238), 'Fox ID', (47, 72, 88, 255), (245, 247, 250, 255), chip_font)
    draw_text(draw, (652, 284), 'Enter the numeric ID', title_font, COLOR_TEXT)
    draw_text(draw, (652, 318), 'Fetch the latest visible public games.', body_font, COLOR_MUTED)
    draw.rounded_rectangle((652, 346, 930, 392), radius=18, fill=(250, 247, 239, 255), outline=(229, 207, 165, 255), width=2)
    draw_text(draw, (676, 376), '123456', load_font(FONT_SEMIBOLD, 22), COLOR_TEXT)
    draw.rounded_rectangle((944, 346, 1000, 392), radius=18, fill=(27, 77, 62, 255))
    draw_text(draw, (972, 376), 'Fetch', button_font, (247, 240, 223, 255), anchor='mm')


def build_frame(base, scene, pulse):
    canvas = base.copy()
    draw = ImageDraw.Draw(canvas)

    overlay = Image.new('RGBA', canvas.size, (246, 222, 176, 18))
    canvas = Image.alpha_composite(canvas, overlay)

    highlight_rects = scene.get('highlight_rects', [])
    if highlight_rects:
        ov, glow = highlight_overlay(canvas.size, highlight_rects, pulse=pulse)
        canvas = Image.alpha_composite(canvas, ov)
        canvas = Image.alpha_composite(canvas, glow)

    draw_header(canvas)
    draw_step_bar(canvas, scene['step'])
    draw_callout(canvas, scene['step'], scene['title'], scene['lines'], scene['accent'], scene.get('chips'))

    if scene['step'] == 2:
        draw_step2_badge(canvas, highlight_rects[0])
    if scene['step'] == 3:
        draw_step3_modal(canvas)

    # Scene-specific small helpers.
    if scene['step'] == 1:
        draw.rounded_rectangle((646, 212, 1038, 420), radius=30, fill=(255, 253, 247, 232), outline=(241, 223, 194, 255), width=2)
        tag_font = load_font(FONT_SEMIBOLD, 17)
        head_font = load_font(FONT_BOLD, 26)
        body_font = load_font(FONT_SEMIBOLD, 19)
        draw_text(draw, (676, 258), 'Recommended first download', head_font, COLOR_TEXT)
        draw_text(draw, (676, 298), 'Start with a bundled package, then switch later only if', body_font, COLOR_MUTED)
        draw_text(draw, (676, 326), 'you already manage your own engines.', body_font, COLOR_MUTED)
        draw_chip(draw, (676, 360, 828, 394), 'windows64.with-katago', (27, 77, 62, 255), (247, 240, 223, 255), tag_font)
        draw_chip(draw, (844, 360, 968, 394), 'mac-arm64.dmg', (238, 228, 208, 230), COLOR_MUTED, tag_font)
        draw_chip(draw, (676, 408, 798, 442), 'mac-amd64.dmg', (238, 228, 208, 230), COLOR_MUTED, tag_font)
        draw_chip(draw, (814, 408, 958, 442), 'linux64.with-katago', (238, 228, 208, 230), COLOR_MUTED, tag_font)

    return canvas


def prepare_base():
    base = Image.open(INPUT).convert('RGBA').resize(TARGET, Image.LANCZOS)
    tint = Image.new('RGBA', TARGET, (248, 234, 198, 28))
    return Image.alpha_composite(base, tint)


def main():
    OUTPUT_GIF.parent.mkdir(parents=True, exist_ok=True)
    base = prepare_base()

    sync_rect = scale_rect((182, 0, 132, 46))
    board_rect = scale_rect((290, 60, 620, 648))
    chart_rect = scale_rect((0, 58, 244, 650))
    list_rect = scale_rect((1200, 280, 292, 620))

    scenes = [
        {
            'step': 1,
            'title': 'Choose a package',
            'lines': ['Start with with-katago for', 'the quickest first run.'],
            'accent': (27, 77, 62, 255),
            'chips': [('Windows x64', False), ('mac-arm64', False), ('mac-amd64', False), ('Linux x64', False), ('with-katago', True)],
            'highlight_rects': [],
        },
        {
            'step': 2,
            'title': 'Open the Fox sync entry',
            'lines': ['Launch the app, then open', 'the sync entry from the top menu.'],
            'accent': (140, 90, 36, 255),
            'highlight_rects': [sync_rect],
        },
        {
            'step': 3,
            'title': 'Enter the numeric Fox ID',
            'lines': ['Type the Fox ID and fetch', 'the latest visible public games.'],
            'accent': (47, 72, 88, 255),
            'highlight_rects': [scale_rect((640, 180, 398, 250))],
        },
        {
            'step': 4,
            'title': 'Continue with KataGo review',
            'lines': ['Use LizzieYzy for charts,', 'visits, mistakes, and review.'],
            'accent': (78, 139, 87, 255),
            'highlight_rects': [board_rect, chart_rect, list_rect],
        },
    ]

    frames = []
    durations = []
    for scene in scenes:
        for pulse, duration in ((0.72, 850), (1.0, 1250)):
            frame = build_frame(base, scene, pulse)
            frames.append(frame.convert('P', palette=Image.ADAPTIVE, colors=160, dither=Image.Dither.FLOYDSTEINBERG))
            durations.append(duration)

    cover = build_frame(base, scenes[2], 1.0)
    cover.save(OUTPUT_COVER)

    frames[0].save(
        OUTPUT_GIF,
        save_all=True,
        append_images=frames[1:],
        duration=durations,
        loop=0,
        optimize=True,
        disposal=2,
    )

    print(f'Wrote {OUTPUT_GIF}')
    print(f'Wrote {OUTPUT_COVER}')


if __name__ == '__main__':
    main()
