from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
INPUT = ROOT / 'screenshot_en.png'
TARGET = (1120, 714)
SOURCE = (1500, 956)
SX = TARGET[0] / SOURCE[0]
SY = TARGET[1] / SOURCE[1]

COLOR_TEXT = '#191613'
COLOR_MUTED = '#6F5A33'

FONT_ARIAL_BLACK = '/System/Library/Fonts/Supplemental/Arial Black.ttf'
FONT_ARIAL_BOLD = '/System/Library/Fonts/Supplemental/Arial Bold.ttf'
FONT_ARIAL = '/System/Library/Fonts/Supplemental/Arial.ttf'
FONT_CJK = '/System/Library/Fonts/Hiragino Sans GB.ttc'

LOCALES = {
    'en': {
        'gif': ROOT / 'assets' / 'fox-id-demo.gif',
        'cover': ROOT / 'assets' / 'fox-id-demo-cover.png',
        'fonts': {
            'header_chip': (FONT_ARIAL_BOLD, 15),
            'header_title': (FONT_ARIAL_BLACK, 34),
            'header_sub': (FONT_ARIAL, 20),
            'step_chip': (FONT_ARIAL_BOLD, 16),
            'callout_title': (FONT_ARIAL_BLACK, 32),
            'callout_body': (FONT_ARIAL_BOLD, 21),
            'callout_small': (FONT_ARIAL, 18),
            'bar_num': (FONT_ARIAL_BLACK, 16),
            'bar_label': (FONT_ARIAL_BOLD, 16),
            'helper_title': (FONT_ARIAL_BLACK, 26),
            'helper_body': (FONT_ARIAL_BOLD, 19),
            'helper_chip': (FONT_ARIAL_BOLD, 17),
            'modal_title': (FONT_ARIAL_BLACK, 28),
            'modal_body': (FONT_ARIAL, 19),
            'modal_button': (FONT_ARIAL_BOLD, 18),
            'modal_input': (FONT_ARIAL_BOLD, 22),
            'badge': (FONT_ARIAL_BOLD, 18),
        },
        'header_chip': 'Quick Demo',
        'header_title': 'From numeric Fox ID to KataGo review',
        'header_sub': 'A short preview of the most common path after download.',
        'step_labels': ['Package', 'Sync', 'Numeric ID', 'Review'],
        'step2_badge': 'Open Sync',
        'step1_helper_title': 'Recommended first download',
        'step1_helper_lines': [
            'Start with a bundled package, then switch later only if',
            'you already manage your own engines.',
        ],
        'step1_helper_chips': ['windows64.with-katago', 'mac-arm64.dmg', 'mac-amd64.dmg', 'linux64.with-katago'],
        'modal_chip': 'Numeric Fox ID',
        'modal_title': 'Enter the numeric Fox ID',
        'modal_sub': 'Fetch the latest visible public games.',
        'modal_button': 'Fetch',
        'scenes': [
            {
                'title': 'Choose a package',
                'lines': ['Start with with-katago for', 'the quickest first run.'],
                'chips': [('Windows x64', False), ('mac-arm64', False), ('mac-amd64', False), ('Linux x64', False), ('with-katago', True)],
            },
            {
                'title': 'Open the Fox sync entry',
                'lines': ['Launch the app, then open', 'the sync entry from the top menu.'],
            },
            {
                'title': 'Enter the numeric Fox ID',
                'lines': ['Type the numeric Fox ID and fetch', 'the latest visible public games.'],
            },
            {
                'title': 'Continue with KataGo review',
                'lines': ['Use LizzieYzy for charts,', 'visits, mistakes, and review.'],
            },
        ],
    },
    'cn': {
        'gif': ROOT / 'assets' / 'fox-id-demo-cn.gif',
        'cover': ROOT / 'assets' / 'fox-id-demo-cn-cover.png',
        'fonts': {
            'header_chip': (FONT_CJK, 15),
            'header_title': (FONT_CJK, 31),
            'header_sub': (FONT_CJK, 19),
            'step_chip': (FONT_CJK, 16),
            'callout_title': (FONT_CJK, 28),
            'callout_body': (FONT_CJK, 20),
            'callout_small': (FONT_CJK, 17),
            'bar_num': (FONT_ARIAL_BLACK, 16),
            'bar_label': (FONT_CJK, 15),
            'helper_title': (FONT_CJK, 24),
            'helper_body': (FONT_CJK, 18),
            'helper_chip': (FONT_CJK, 16),
            'modal_title': (FONT_CJK, 26),
            'modal_body': (FONT_CJK, 18),
            'modal_button': (FONT_CJK, 18),
            'modal_input': (FONT_ARIAL_BOLD, 22),
            'badge': (FONT_CJK, 17),
        },
        'header_chip': '快速演示',
        'header_title': '从野狐数字ID到 KataGo 分析',
        'header_sub': '下载后最常见的使用路径',
        'step_labels': ['安装包', '同步', '野狐数字ID', '分析'],
        'step2_badge': '打开同步',
        'step1_helper_title': '推荐先下载整合包',
        'step1_helper_lines': [
            '先用内置 KataGo 的版本，',
            '后面再按需要换引擎。',
        ],
        'step1_helper_chips': ['windows64.with-katago', 'mac-arm64.dmg', 'mac-amd64.dmg', 'linux64.with-katago'],
        'modal_chip': '野狐数字ID',
        'modal_title': '输入野狐数字ID',
        'modal_sub': '获取最新公开棋谱',
        'modal_button': '抓谱',
        'scenes': [
            {
                'title': '先选安装包',
                'lines': ['大多数用户先选', 'with-katago 就行'],
                'chips': [('Windows', False), ('mac-arm64', False), ('mac-amd64', False), ('Linux', False), ('with-katago', True)],
            },
            {
                'title': '打开野狐同步',
                'lines': ['启动程序后，从上方菜单', '进入同步入口'],
            },
            {
                'title': '输入野狐数字ID',
                'lines': ['输入野狐数字ID，抓取', '最新公开棋谱'],
            },
            {
                'title': '继续分析复盘',
                'lines': ['继续用 LizzieYzy 查看', '胜率、失误和分析'],
            },
        ],
    },
}

ACCENTS = [
    (27, 77, 62, 255),
    (140, 90, 36, 255),
    (47, 72, 88, 255),
    (78, 139, 87, 255),
]


def load_font(spec):
    path, size = spec
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


def font(cfg, key):
    return load_font(cfg['fonts'][key])


def draw_header(canvas, cfg):
    header = rounded_overlay(canvas.size, (44, 34, 570, 132), 28, (255, 250, 239, 235), shadow=(0, 10, 18, (84, 56, 18, 45)), stroke=(245, 226, 190, 255))
    canvas.alpha_composite(header)
    draw = ImageDraw.Draw(canvas)
    draw_chip(draw, (70, 54, 176, 84), cfg['header_chip'], (27, 77, 62, 255), (247, 240, 223, 255), font(cfg, 'header_chip'))
    draw_text(draw, (70, 108), cfg['header_title'], font(cfg, 'header_title'), COLOR_TEXT)
    draw_text(draw, (70, 136), cfg['header_sub'], font(cfg, 'header_sub'), COLOR_MUTED)


def draw_step_bar(canvas, active_step, cfg):
    draw = ImageDraw.Draw(canvas)
    bar = rounded_overlay(canvas.size, (196, 646, 924, 694), 24, (255, 250, 241, 220), shadow=(0, 10, 18, (94, 63, 20, 35)), stroke=(242, 223, 187, 255))
    canvas.alpha_composite(bar)
    draw.line((252, 670, 868, 670), fill=(227, 192, 123, 160), width=6)
    xs = [252, 458, 664, 870]
    for idx, (label, color, cx) in enumerate(zip(cfg['step_labels'], ACCENTS, xs), start=1):
        r = 28 if idx == active_step else 22
        draw.ellipse((cx - r, 670 - r, cx + r, 670 + r), fill=color if idx == active_step else (232, 220, 196, 255))
        num_fill = (247, 240, 223, 255) if idx == active_step else COLOR_MUTED
        draw_text(draw, (cx, 670), f'{idx:02d}', font(cfg, 'bar_num'), num_fill, anchor='mm')
        label_fill = color if idx == active_step else COLOR_MUTED
        draw_text(draw, (cx, 698), label, font(cfg, 'bar_label'), label_fill, anchor='ms')


def draw_callout(canvas, step_num, scene, cfg):
    accent = ACCENTS[step_num - 1]
    panel = rounded_overlay(canvas.size, (52, 168, 478, 404), 34, (255, 253, 247, 240), shadow=(0, 16, 26, (94, 64, 19, 50)), stroke=(241, 223, 194, 255))
    canvas.alpha_composite(panel)
    draw = ImageDraw.Draw(canvas)

    draw_chip(draw, (76, 192, 182, 226), f'Step {step_num} of 4' if cfg is LOCALES['en'] else f'第 {step_num} 步 / 共 4 步', accent, (248, 241, 225, 255), font(cfg, 'step_chip'))
    draw_text(draw, (76, 274), scene['title'], font(cfg, 'callout_title'), COLOR_TEXT)
    y = 324
    for line in scene['lines']:
        draw_text(draw, (76, y), line, font(cfg, 'callout_body'), COLOR_MUTED)
        y += 30
    if scene.get('chips'):
        x = 76
        y = 364
        for text, selected in scene['chips']:
            ft = font(cfg, 'callout_small')
            tw = draw.textlength(text, font=ft)
            w = int(tw + 34)
            if x + w > 450:
                x = 76
                y += 42
            bg = accent if selected else (238, 228, 208, 230)
            fg = (248, 241, 225, 255) if selected else COLOR_MUTED
            draw_chip(draw, (x, y, x + w, y + 30), text, bg, fg, ft)
            x += w + 12


def draw_step2_badge(canvas, rect, cfg):
    draw = ImageDraw.Draw(canvas)
    x0, y0, x1, y1 = rect
    badge = rounded_overlay(canvas.size, (x1 - 70, y1 + 16, x1 + 126, y1 + 64), 24, (255, 253, 247, 238), shadow=(0, 10, 16, (94, 64, 19, 40)), stroke=(241, 223, 194, 255))
    canvas.alpha_composite(badge)
    draw_text(draw, (x1 + 28, y1 + 46), cfg['step2_badge'], font(cfg, 'badge'), ACCENTS[1], anchor='mm')


def draw_step3_modal(canvas, cfg):
    modal = rounded_overlay(canvas.size, (626, 184, 1030, 430), 28, (255, 253, 247, 246), shadow=(0, 18, 24, (71, 49, 19, 55)), stroke=(241, 223, 194, 255))
    canvas.alpha_composite(modal)
    draw = ImageDraw.Draw(canvas)

    draw_chip(draw, (652, 206, 744, 238), cfg['modal_chip'], ACCENTS[2], (245, 247, 250, 255), font(cfg, 'step_chip'))
    draw_text(draw, (652, 284), cfg['modal_title'], font(cfg, 'modal_title'), COLOR_TEXT)
    draw_text(draw, (652, 318), cfg['modal_sub'], font(cfg, 'modal_body'), COLOR_MUTED)
    draw.rounded_rectangle((652, 346, 930, 392), radius=18, fill=(250, 247, 239, 255), outline=(229, 207, 165, 255), width=2)
    draw_text(draw, (676, 376), '123456', font(cfg, 'modal_input'), COLOR_TEXT)
    draw.rounded_rectangle((944, 346, 1000, 392), radius=18, fill=ACCENTS[0])
    draw_text(draw, (972, 376), cfg['modal_button'], font(cfg, 'modal_button'), (247, 240, 223, 255), anchor='mm')


def draw_step1_helper(canvas, cfg):
    draw = ImageDraw.Draw(canvas)
    draw.rounded_rectangle((646, 212, 1038, 420), radius=30, fill=(255, 253, 247, 232), outline=(241, 223, 194, 255), width=2)
    draw_text(draw, (676, 258), cfg['step1_helper_title'], font(cfg, 'helper_title'), COLOR_TEXT)
    y = 298
    for line in cfg['step1_helper_lines']:
        draw_text(draw, (676, y), line, font(cfg, 'helper_body'), COLOR_MUTED)
        y += 28
    x = 676
    y = 360
    ft = font(cfg, 'helper_chip')
    for label in cfg['step1_helper_chips']:
        w = int(draw.textlength(label, font=ft) + 34)
        if x + w > 992:
            x = 676
            y += 48
        draw_chip(draw, (x, y, x + w, y + 34), label, (27, 77, 62, 255) if label == cfg['step1_helper_chips'][0] else (238, 228, 208, 230), (247, 240, 223, 255) if label == cfg['step1_helper_chips'][0] else COLOR_MUTED, ft)
        x += w + 12


def prepare_base():
    base = Image.open(INPUT).convert('RGBA').resize(TARGET, Image.LANCZOS)
    tint = Image.new('RGBA', TARGET, (248, 234, 198, 28))
    return Image.alpha_composite(base, tint)


def build_frame(base, scene_index, pulse, cfg, highlight_rects):
    canvas = base.copy()
    canvas = Image.alpha_composite(canvas, Image.new('RGBA', canvas.size, (246, 222, 176, 18)))

    if highlight_rects:
        ov, glow = highlight_overlay(canvas.size, highlight_rects, pulse=pulse)
        canvas = Image.alpha_composite(canvas, ov)
        canvas = Image.alpha_composite(canvas, glow)

    draw_header(canvas, cfg)
    draw_step_bar(canvas, scene_index + 1, cfg)
    draw_callout(canvas, scene_index + 1, cfg['scenes'][scene_index], cfg)

    if scene_index == 0:
        draw_step1_helper(canvas, cfg)
    elif scene_index == 1:
        draw_step2_badge(canvas, highlight_rects[0], cfg)
    elif scene_index == 2:
        draw_step3_modal(canvas, cfg)

    return canvas


def main():
    base = prepare_base()
    sync_rect = scale_rect((182, 0, 132, 46))
    board_rect = scale_rect((290, 60, 620, 648))
    chart_rect = scale_rect((0, 58, 244, 650))
    list_rect = scale_rect((1200, 280, 292, 620))
    modal_rect = scale_rect((640, 180, 398, 250))
    highlights = [[], [sync_rect], [modal_rect], [board_rect, chart_rect, list_rect]]

    for cfg in LOCALES.values():
        cfg['gif'].parent.mkdir(parents=True, exist_ok=True)
        frames = []
        durations = []
        for idx in range(4):
            for pulse, duration in ((0.72, 850), (1.0, 1250)):
                frame = build_frame(base, idx, pulse, cfg, highlights[idx])
                frames.append(frame.convert('P', palette=Image.ADAPTIVE, colors=160, dither=Image.Dither.FLOYDSTEINBERG))
                durations.append(duration)
        cover = build_frame(base, 2, 1.0, cfg, highlights[2])
        cover.save(cfg['cover'])
        frames[0].save(
            cfg['gif'],
            save_all=True,
            append_images=frames[1:],
            duration=durations,
            loop=0,
            optimize=True,
            disposal=2,
        )
        print('Wrote', cfg['gif'])
        print('Wrote', cfg['cover'])


if __name__ == '__main__':
    main()
