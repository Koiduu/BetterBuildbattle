"""Builds the Better Battle UI pack for Minecraft 26.x and 1.8.9.

Colours come from web/style.css on battle.aspir3.net: dark grey cards, pink primary, gold accents.
Block, item and entity textures are left alone on purpose: builds must look the same to you as to the voters.

Container backgrounds are made by remapping the greys of your own vanilla jars, so slot layouts stay
pixel-exact. Buttons, fields, sliders, the hotbar and tooltips are drawn from scratch.

    python tools/ui-pack/build.py              # reads %APPDATA%/.minecraft/versions/{26.2,1.8.9}
    python tools/ui-pack/build.py --mc-dir <path> --modern 26.2 --legacy 1.8.9 --zip

Writes into the mod's resources, where each loader ships it as a built-in pack:
    mod/fabric-26.3/src/main/resources/resourcepacks/betterbattle_ui/   (all 26.x builds share it)
    mod/forge-1.8.9/src/main/resources/betterbattle_ui/
--zip also writes stand-alone packs to tools/ui-pack/dist/.
"""
import argparse, io, json, os, shutil, zipfile
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
MODERN_OUT = os.path.join(REPO, 'mod', 'fabric-26.3', 'src', 'main', 'resources', 'resourcepacks', 'betterbattle_ui')
LEGACY_OUT = os.path.join(REPO, 'mod', 'forge-1.8.9', 'src', 'main', 'resources', 'betterbattle_ui')
GUI = 'assets/minecraft/textures/gui/'

# --- palette (web/style.css) ---
def hx(s, a=255):
    s = s.lstrip('#'); return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)

BG, CARD, CARD2, LINE = hx('17171a'), hx('1f1f24'), hx('141416'), hx('3a3a42')
EDGE, CARD_HI, CARD_LO = hx('000000'), hx('34343c'), hx('121215')
PINK, PINK_HI, PINK_LO, PINK_FILL, PINK_HOVER = hx('ff66aa'), hx('ff9cc8'), hx('9a2f61'), hx('d9508f'), hx('ee62a2')
BTN, BTN_HI, BTN_LO, BTN_OFF = hx('6f6f6f'), hx('a8a8a8'), hx('4f4f4f'), hx('2c2c2c')
WHITE, GOLD = hx('ffffff'), hx('ffaa00')

# vanilla grey -> new colour. Pixels in between are interpolated; coloured pixels are kept.
RAMP = [(0x00, EDGE), (0x37, hx('0a0a0c')), (0x55, CARD_LO), (0x8b, CARD2), (0xc6, CARD), (0xff, LINE)]


def ramp(v):
    for (a, ca), (b, cb) in zip(RAMP, RAMP[1:]):
        if a <= v <= b:
            t = (v - a) / (b - a) if b > a else 0
            return tuple(round(ca[i] + (cb[i] - ca[i]) * t) for i in range(3))
    return RAMP[-1][1][:3]


def remap(im, white_to=None, region=None):
    """Re-colours the greys of a vanilla GUI texture. white_to replaces pure white (bevel highlights)."""
    im = im.convert('RGBA'); px = im.load()
    x0, y0, x1, y1 = region or (0, 0, im.width, im.height)
    for y in range(y0, y1):
        for x in range(x0, x1):
            r, g, b, a = px[x, y]
            if a == 0 or max(r, g, b) - min(r, g, b) > 6:
                continue
            if white_to and r == g == b == 255:
                px[x, y] = white_to[:3] + (a,)
            else:
                px[x, y] = ramp((r + g + b) // 3) + (a,)
    return im


def accent_edge(im, region, from_top, thickness=2):
    """Draws a pink bar just inside a tab's black outer edge (the edge away from the panel)."""
    px = im.load(); x0, y0, x1, y1 = region
    ys = range(y0, y1) if from_top else range(y1 - 1, y0 - 1, -1)

    def edge_at(x):
        for i, y in enumerate(ys):
            if px[x, y][3]: return i
        return None
    # Only columns whose outline sits on the flat edge, so rounded corners stay clean.
    # Tabs sit side by side in the 1.8.9 sheet, so "flat" is judged per tab (each run of columns).
    flat = set(); run = []
    for x in list(range(x0, x1)) + [None]:
        e = edge_at(x) if x is not None else None
        if e is not None: run.append((x, e)); continue
        if run:
            m = min(e for _, e in run); flat.update(x for x, e in run if e == m)
        run = []
    for x in range(x0, x1):
        if x not in flat: continue
        seen_edge = False; done = 0
        for y in ys:
            r, g, b, a = px[x, y]
            if a == 0: continue
            if not seen_edge:
                if (r, g, b) == EDGE[:3]: seen_edge = True; continue
                break
            if (r, g, b) == EDGE[:3] or done >= thickness: break
            px[x, y] = (PINK if done == 0 else PINK_LO)[:3] + (a,); done += 1
    # columns whose edge is the side outline (left/right of the tab) must not get a bar
    return im


def tint(im, rgb, alpha_mul):
    im = im.convert('RGBA'); px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            a = px[x, y][3]
            if a: px[x, y] = rgb[:3] + (min(255, round(a * alpha_mul)),)
    return im


# --- drawn widgets ---
def bevel(w, h, fill, hi, lo, outer=EDGE, inner=None, bottom=1):
    """A box like the site's .btn: outline, optional inner outline, light top-left, dark bottom-right."""
    im = Image.new('RGBA', (w, h), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    d.rectangle([0, 0, w - 1, h - 1], fill=outer)
    o = 1
    if inner:
        d.rectangle([1, 1, w - 2, h - 2], fill=inner); o = 2
    d.rectangle([o, o, w - 1 - o, h - 1 - o], fill=lo)
    d.rectangle([o, o, w - 2 - o, h - 1 - o - bottom], fill=hi)
    d.rectangle([o + 1, o + 1, w - 2 - o, h - 1 - o - bottom], fill=fill)
    return im


def button(state, w=200, h=20):
    if state == 'normal':
        return bevel(w, h, BTN, BTN_HI, BTN_LO, bottom=2)
    if state == 'hover':
        return bevel(w, h, PINK_FILL, PINK_HI, PINK_LO, inner=WHITE, bottom=1)
    im = Image.new('RGBA', (w, h), EDGE)
    ImageDraw.Draw(im).rectangle([1, 1, w - 2, h - 2], fill=BTN_OFF)
    return im


def field(w, h, border, fill=CARD2):
    im = Image.new('RGBA', (w, h), border)
    ImageDraw.Draw(im).rectangle([1, 1, w - 2, h - 2], fill=fill)
    return im


CHECK = ["..........", ".........#", "........##", ".......##.", "#.....##..",
         "##...##...", ".##.##....", "..###.....", "...#......"]


def checkbox(selected, hover):
    im = field(20, 20, PINK if hover else LINE)
    ImageDraw.Draw(im).rectangle([1, 1, 18, 18], outline=EDGE)
    if selected:
        for y, row in enumerate(CHECK):
            for x, c in enumerate(row):
                if c == '#': im.putpixel((5 + x, 5 + y), PINK if not hover else WHITE)
    return im


def tab(selected, hover):
    im = Image.new('RGBA', (130, 24), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 129, 23], fill=EDGE)
    d.rectangle([1, 1, 128, 23], fill=(CARD if selected else CARD2)[:3] + (230,))
    if selected:
        d.rectangle([1, 1, 128, 2], fill=PINK)
    elif hover:
        d.rectangle([1, 1, 128, 1], fill=PINK_LO)
    return im


def hotbar():
    im = Image.new('RGBA', (182, 22), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 181, 21], fill=EDGE)
    for i in range(9):
        x = 1 + 20 * i
        d.rectangle([x, 1, x + 19, 20], fill=BG[:3] + (200,))
        d.line([x, 1, x + 19, 1], fill=CARD_HI[:3] + (220,))
        d.line([x, 1, x, 20], fill=CARD_HI[:3] + (220,))
        if i: d.line([x - 1, 1, x - 1, 20], fill=EDGE)
    return im


def selection(h):
    im = Image.new('RGBA', (24, h), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 23, h - 1], outline=EDGE)
    d.rectangle([1, 1, 22, h - 2], outline=PINK)
    d.rectangle([2, 2, 21, h - 3], outline=PINK)
    d.line([2, 2, 21, 2], fill=PINK_HI); d.line([2, 2, 2, h - 3], fill=PINK_HI)
    d.rectangle([3, 3, 20, h - 4], outline=EDGE[:3] + (160,))
    return im


def creative_scroller(enabled):
    if enabled:
        return bevel(12, 15, PINK_FILL, PINK_HI, PINK_LO)
    return bevel(12, 15, BTN_OFF, hx('3c3c3c'), hx('1c1c1c'))


def tooltip_bg():
    im = Image.new('RGBA', (100, 100), (0, 0, 0, 0))
    ImageDraw.Draw(im).rectangle([8, 8, 91, 91], fill=BG[:3] + (240,))
    return im


def tooltip_frame(vanilla):
    """Keeps vanilla's frame shape, swaps the purple gradient for pink."""
    im = vanilla.convert('RGBA'); px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            if a:
                t = max(0.0, min(1.0, (b - 0x7f) / (0xff - 0x7f)))
                c = tuple(round(PINK_LO[i] + (PINK[i] - PINK_LO[i]) * t) for i in range(3))
                px[x, y] = c + (min(255, a * 2),)
    return im


def tile_bg(alpha):
    im = Image.new('RGBA', (16, 16), BG[:3] + (alpha,))
    for x, y in [(3, 5), (11, 2), (7, 12), (14, 9), (1, 14)]:
        im.putpixel((x, y), CARD[:3] + (alpha,))
    return im


# --- building ---
class Pack:
    def __init__(self, root):
        self.root = root
        if os.path.exists(root): shutil.rmtree(root)
        os.makedirs(root)

    def save(self, rel, im):
        p = os.path.join(self.root, rel); os.makedirs(os.path.dirname(p), exist_ok=True); im.save(p)

    def text(self, rel, s):
        p = os.path.join(self.root, rel); os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, 'w', encoding='utf-8', newline='\n').write(s)


def read_jar(path):
    z = zipfile.ZipFile(path)
    def get(rel):
        return Image.open(io.BytesIO(z.read(rel))).convert('RGBA')
    names = [n for n in z.namelist() if n.startswith(GUI) and n.endswith('.png')]
    return z, get, names


DESC_MODERN = "§dBetter Battle §7UI\n§8Matches battle.aspir3.net"
DESC_LEGACY = "§dBetter Battle §7UI §8(1.8.9)"


def build_modern(jar, out, icon):
    z, get, names = read_jar(jar)
    p = Pack(out)
    p.text('pack.mcmeta', json.dumps({"pack": {"description": DESC_MODERN, "min_format": 84, "max_format": 97}}, indent=2, ensure_ascii=False) + '\n')
    if icon: p.save('pack.png', icon)

    def copy_meta(rel):
        if rel + '.mcmeta' in z.namelist(): p.text(rel + '.mcmeta', z.read(rel + '.mcmeta').decode('utf-8'))

    drawn = {}
    W = GUI + 'sprites/widget/'
    drawn[W + 'button.png'] = button('normal')
    drawn[W + 'button_highlighted.png'] = button('hover')
    drawn[W + 'button_disabled.png'] = button('off')
    drawn[W + 'text_field.png'] = field(200, 20, LINE)
    drawn[W + 'text_field_highlighted.png'] = field(200, 20, PINK)
    drawn[W + 'slider.png'] = field(200, 20, EDGE)
    drawn[W + 'slider_highlighted.png'] = field(200, 20, PINK)
    drawn[W + 'slider_handle.png'] = bevel(8, 20, BTN, BTN_HI, BTN_LO, bottom=2)
    drawn[W + 'slider_handle_highlighted.png'] = bevel(8, 20, PINK_FILL, PINK_HI, PINK_LO, bottom=2)
    for sel in (False, True):
        for hov in (False, True):
            name = 'checkbox' + ('_selected' if sel else '') + ('_highlighted' if hov else '')
            drawn[W + name + '.png'] = checkbox(sel, hov)
            name = 'tab' + ('_selected' if sel else '') + ('_highlighted' if hov else '')
            drawn[W + name + '.png'] = tab(sel, hov)
    drawn[W + 'scroller.png'] = field(6, 32, EDGE, PINK_FILL)
    drawn[W + 'scroller_background.png'] = field(6, 32, EDGE, CARD2)
    drawn[GUI + 'sprites/hud/hotbar.png'] = hotbar()
    drawn[GUI + 'sprites/hud/hotbar_selection.png'] = selection(23)
    C = GUI + 'sprites/container/creative_inventory/'
    drawn[C + 'scroller.png'] = creative_scroller(True)
    drawn[C + 'scroller_disabled.png'] = creative_scroller(False)
    drawn[GUI + 'sprites/tooltip/background.png'] = tooltip_bg()
    drawn[GUI + 'sprites/tooltip/frame.png'] = tooltip_frame(get(GUI + 'sprites/tooltip/frame.png'))
    drawn[GUI + 'menu_background.png'] = tile_bg(150)
    drawn[GUI + 'inworld_menu_background.png'] = tile_bg(110)
    drawn[GUI + 'menu_list_background.png'] = tile_bg(190)
    drawn[GUI + 'inworld_menu_list_background.png'] = tile_bg(160)
    for sep in ('header_separator', 'footer_separator', 'inworld_header_separator', 'inworld_footer_separator'):
        im = Image.new('RGBA', (32, 2), PINK[:3] + (170,))
        for x in range(32): im.putpixel((x, 1 if sep.endswith('header_separator') else 0), EDGE[:3] + (200,))
        drawn[GUI + sep + '.png'] = im

    for rel, im in drawn.items():
        p.save(rel, im); copy_meta(rel)

    # Remapped vanilla: containers, creative tabs, toasts, recipe book, advancements window.
    remap_dirs = (GUI + 'container/', GUI + 'sprites/container/', GUI + 'sprites/advancements/tab_',
                  GUI + 'sprites/toast/advancement', GUI + 'sprites/toast/recipe', GUI + 'sprites/popup/',
                  GUI + 'sprites/recipe_book/')
    remap_files = (GUI + 'recipe_book.png', GUI + 'advancements/window.png')
    skip = (GUI + 'sprites/container/slot/',)   # empty-slot icons would vanish on a dark slot
    for n in names:
        if n in drawn or n.startswith(skip): continue
        if not (n.startswith(remap_dirs) or n in remap_files): continue
        im = remap(get(n))
        if '/creative_inventory/tab_top_selected_' in n: im = accent_edge(im, (0, 0) + im.size, True)
        if '/creative_inventory/tab_bottom_selected_' in n: im = accent_edge(im, (0, 0) + im.size, False)
        p.save(n, im); copy_meta(n)
    return p


def build_legacy(jar, out, icon):
    z, get, names = read_jar(jar)
    p = Pack(out)
    p.text('pack.mcmeta', json.dumps({"pack": {"pack_format": 1, "description": DESC_LEGACY}}, indent=2, ensure_ascii=False) + '\n')
    if icon: p.save('pack.png', icon)

    w = get(GUI + 'widgets.png')
    for (x, y, h, im) in [(0, 0, 22, hotbar()), (0, 22, 24, selection(24)),
                          (0, 46, 20, button('off')), (0, 66, 20, button('normal')), (0, 86, 20, button('hover'))]:
        w.paste(Image.new('RGBA', im.size, (0, 0, 0, 0)), (x, y)); w.paste(im, (x, y))
    p.save(GUI + 'widgets.png', w)
    p.save(GUI + 'options_background.png', tile_bg(255).resize((16, 16)))

    for n in names:
        if not n.startswith(GUI + 'container/'): continue
        im = get(n)
        if n.endswith('creative_inventory/tabs.png'):
            im = remap(im)
            im = accent_edge(im, (0, 32, 256, 64), True); im = accent_edge(im, (0, 96, 256, 128), False)
            im.paste(creative_scroller(True), (232, 0)); im.paste(creative_scroller(False), (244, 0))
        else:
            im = remap(im)
        p.save(n, im)
    return p


def zipdir(src, dst):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as z:
        for base, _, files in os.walk(src):
            for f in sorted(files):
                full = os.path.join(base, f); z.write(full, os.path.relpath(full, src).replace(os.sep, '/'))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--mc-dir', default=os.path.join(os.environ.get('APPDATA', ''), '.minecraft'))
    ap.add_argument('--modern', default='26.2'); ap.add_argument('--legacy', default='1.8.9')
    ap.add_argument('--icon', default=os.path.join(REPO, 'mod', 'fabric-26.3', 'src', 'main', 'resources', 'assets', 'betterbattle', 'icon.png'))
    ap.add_argument('--zip', action='store_true', help='also write stand-alone pack zips to tools/ui-pack/dist')
    a = ap.parse_args()
    v = os.path.join(a.mc_dir, 'versions')
    icon = Image.open(a.icon).convert('RGBA').resize((128, 128), Image.NEAREST) if os.path.exists(a.icon) else None
    build_modern(os.path.join(v, a.modern, a.modern + '.jar'), MODERN_OUT, icon)
    build_legacy(os.path.join(v, a.legacy, a.legacy + '.jar'), LEGACY_OUT, icon)
    print('built', MODERN_OUT); print('built', LEGACY_OUT)
    if a.zip:
        zipdir(MODERN_OUT, os.path.join(HERE, 'dist', 'BetterBattle-UI-26.x.zip'))
        zipdir(LEGACY_OUT, os.path.join(HERE, 'dist', 'BetterBattle-UI-1.8.9.zip'))
        print('zipped', os.path.join(HERE, 'dist'))


if __name__ == '__main__':
    main()
