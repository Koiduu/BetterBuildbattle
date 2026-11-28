"""Draws previews/preview.png: vanilla next to the pack, as mock screens at 3x."""
import io, os, sys, zipfile
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
GUI = 'assets/minecraft/textures/gui/'
MC = os.path.join(os.environ.get('APPDATA', ''), '.minecraft', 'versions')


def source(kind, ver):
    if kind == 'vanilla':
        z = zipfile.ZipFile(os.path.join(MC, ver, ver + '.jar'))
        return lambda rel: Image.open(io.BytesIO(z.read(GUI + rel))).convert('RGBA')
    from build import MODERN_OUT, LEGACY_OUT
    root = MODERN_OUT if ver != '1.8.9' else LEGACY_OUT
    van = source('vanilla', ver)
    def get(rel):
        p = os.path.join(root, GUI + rel)
        return Image.open(p).convert('RGBA') if os.path.exists(p) else van(rel)
    return get


def nine(im, w, h, b):
    """Rough nine-slice stretch for buttons in the preview."""
    out = Image.new('RGBA', (w, h)); sw, sh = im.size
    for (sx0, sx1, dx0, dx1) in [(0, b, 0, b), (b, sw - b, b, w - b), (sw - b, sw, w - b, w)]:
        for (sy0, sy1, dy0, dy1) in [(0, b, 0, b), (b, sh - b, b, h - b), (sh - b, sh, h - b, h)]:
            if dx1 > dx0 and dy1 > dy0:
                out.paste(im.crop((sx0, sy0, sx1, sy1)).resize((dx1 - dx0, dy1 - dy0), Image.NEAREST), (dx0, dy0))
    return out


def screen_modern(get, label):
    W, H = 420, 250
    s = Image.new('RGBA', (W, H), (96, 130, 170, 255))   # stand-in for the world behind the GUI
    d = ImageDraw.Draw(s)
    s.alpha_composite(get('container/generic_54.png').crop((0, 0, 176, 71)), (8, 8))
    s.alpha_composite(get('container/generic_54.png').crop((0, 125, 176, 222)), (8, 79))
    d.text((16, 14), 'Choose a theme', fill=(64, 64, 64)); d.text((16, 85), 'Inventory', fill=(64, 64, 64))
    tabs = get('container/creative_inventory/tab_items.png').crop((0, 0, 195, 136))
    x0, y0 = 200, 36
    for i in range(1, 6):
        s.alpha_composite(get(f'sprites/container/creative_inventory/tab_top_unselected_{i}.png'), (x0 + (i - 1) * 27 + 27, y0 - 28))
    s.alpha_composite(tabs, (x0, y0))
    s.alpha_composite(get('sprites/container/creative_inventory/tab_top_selected_1.png'), (x0, y0 - 28))
    s.alpha_composite(get('sprites/container/creative_inventory/scroller.png'), (x0 + 175, y0 + 18))
    btn_y = 182
    for i, (name, txt) in enumerate([('button', 'Play'), ('button_highlighted', 'Hovered'), ('button_disabled', 'Disabled')]):
        s.alpha_composite(nine(get(f'sprites/widget/{name}.png'), 90, 20, 3), (8 + i * 96, btn_y))
        d.text((8 + i * 96 + 45 - len(txt) * 3, btn_y + 5), txt, fill=(255, 255, 255) if name != 'button_disabled' else (160, 160, 160))
    s.alpha_composite(nine(get('sprites/widget/text_field_highlighted.png'), 110, 20, 1), (300, btn_y))
    d.text((305, btn_y + 5), '/bb link', fill=(224, 224, 224))
    hb = get('sprites/hud/hotbar.png'); s.alpha_composite(hb, (119, 220)); s.alpha_composite(get('sprites/hud/hotbar_selection.png'), (119 - 1 + 40, 219))
    for i, n in enumerate(['checkbox', 'checkbox_selected']):
        s.alpha_composite(get(f'sprites/widget/{n}.png'), (8 + i * 24, 220))
    d.text((W - 6 - len(label) * 6, H - 12), label, fill=(255, 255, 255))
    return s


def screen_legacy(get, label):
    W, H = 420, 250
    s = Image.new('RGBA', (W, H), (96, 130, 170, 255)); d = ImageDraw.Draw(s)
    g = get('container/generic_54.png')
    s.alpha_composite(g.crop((0, 0, 176, 71)), (8, 8)); s.alpha_composite(g.crop((0, 125, 176, 222)), (8, 79))
    d.text((16, 14), 'Choose a theme', fill=(64, 64, 64))
    tabs = get('container/creative_inventory/tabs.png')
    x0, y0 = 200, 36
    for i in range(1, 6): s.alpha_composite(tabs.crop((i * 28, 0, i * 28 + 28, 32)), (x0 + i * 28, y0 - 28))
    s.alpha_composite(get('container/creative_inventory/tab_items.png').crop((0, 0, 195, 136)), (x0, y0))
    s.alpha_composite(tabs.crop((0, 32, 28, 64)), (x0, y0 - 28))
    s.alpha_composite(tabs.crop((232, 0, 244, 15)), (x0 + 175, y0 + 18))
    w = get('widgets.png')
    for i, (y, txt) in enumerate([(66, 'Play'), (86, 'Hovered'), (46, 'Disabled')]):
        b = Image.new('RGBA', (90, 20)); b.paste(w.crop((0, y, 45, y + 20)), (0, 0)); b.paste(w.crop((155, y, 200, y + 20)), (45, 0))
        s.alpha_composite(b, (8 + i * 96, 182)); d.text((8 + i * 96 + 45 - len(txt) * 3, 187), txt, fill=(255, 255, 255))
    s.alpha_composite(w.crop((0, 0, 182, 22)), (119, 220)); s.alpha_composite(w.crop((0, 22, 24, 46)), (158, 219))
    d.text((W - 6 - len(label) * 6, H - 12), label, fill=(255, 255, 255))
    return s


def main():
    rows = []
    for ver, fn in [('26.2', screen_modern), ('1.8.9', screen_legacy)]:
        a = fn(source('vanilla', ver), f'vanilla {ver}'); b = fn(source('pack', ver), f'Better Battle UI {ver}')
        row = Image.new('RGBA', (a.width * 2 + 8, a.height), (23, 23, 26, 255)); row.paste(a, (0, 0)); row.paste(b, (a.width + 8, 0))
        rows.append(row)
    out = Image.new('RGBA', (rows[0].width, sum(r.height for r in rows) + 8), (23, 23, 26, 255))
    out.paste(rows[0], (0, 0)); out.paste(rows[1], (0, rows[0].height + 8))
    out = out.resize((out.width * 2, out.height * 2), Image.NEAREST)
    os.makedirs(os.path.join(HERE, 'previews'), exist_ok=True)
    p = os.path.join(HERE, 'previews', 'preview.png'); out.save(p); print(p)


if __name__ == '__main__':
    main()
