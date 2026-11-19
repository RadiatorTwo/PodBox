#!/usr/bin/env python3
"""PodBox theme assets (480x640, RGB565 display).

Generates the bitmaps of the "podbox" theme:
  wps/podbox/*.bmp                   backdrops, cards, bars, status icons
  icons/podbox_icons.32x32.bmp       list icon strip (32 bit, alpha)

Requirements: Python 3 with Pillow and numpy, the Inter TTFs in
theme/fonts and the Material Symbols Sharp font (downloaded to
theme/cache on first run, Apache 2.0).

Colour rules (see docs/STATUS.md, "Design"):
- Every flat colour is an exact RGB565 value, so code (line.c, %Vs(fill))
  can fill the same colour pixel-exact next to bitmap areas.
- Gradients and shadows are dithered here (8x8 Bayer) to RGB565 and
  written as 24 bit BMPs whose values Rockbox maps 1:1 (no dither there).
- Opaque bitmaps are composited onto the known background, so there is
  no run-time blending except for the list icons.

Layout constants are mirrored in wps/podbox.480x640x16.{wps,sbs}.
"""
import os
import struct
import sys
import urllib.request

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUT = os.path.join(ROOT, "wps", "podbox")
ICONS = os.path.join(ROOT, "icons")
FONTS = os.path.join(ROOT, "theme", "fonts")
CACHE = os.path.join(ROOT, "theme", "cache")
MSYM_URL = ("https://raw.githubusercontent.com/google/material-design-icons/"
            "master/variablefont/MaterialSymbolsSharp%5BFILL,GRAD,opsz,wght%5D")

W, H = 480, 640

# --- palette: exact RGB565 values ----------------------------------------
def c565(hexstr):
    r, g, b = (int(hexstr[i:i + 2], 16) for i in (0, 2, 4))
    r5, g6, b5 = r >> 3, g >> 2, b >> 3
    return ((r5 << 3) | (r5 >> 2), (g6 << 2) | (g6 >> 4), (b5 << 3) | (b5 >> 2))

BG = c565("181C20")
SURFACE = c565("202428")      # cards
SURFACE2 = c565("2A2E33")     # raised cards (cover placeholder, overlays)
TEXT = c565("E8ECF0")
SEC = c565("A8ACB0")
TER = c565("84888E")
TRACK = c565("3A3E44")
ACCENT = c565("E0E4E8")
ICON = c565("C4C8CC")

def hexcol(c):
    return "%02X%02X%02X" % c

# --- RGB565 dithering -----------------------------------------------------
_B8 = np.array([[0, 32, 8, 40, 2, 34, 10, 42],
                [48, 16, 56, 24, 50, 18, 58, 26],
                [12, 44, 4, 36, 14, 46, 6, 38],
                [60, 28, 52, 20, 62, 30, 54, 22],
                [3, 35, 11, 43, 1, 33, 9, 41],
                [51, 19, 59, 27, 49, 17, 57, 25],
                [15, 47, 7, 39, 13, 45, 5, 37],
                [63, 31, 55, 23, 61, 29, 53, 21]], dtype=np.float64)

def _expand(q, bits):
    return (q << (8 - bits)) | (q >> (2 * bits - 8))

def _quant(ch, bits, thr):
    levels = (1 << bits) - 1
    exp = np.array([_expand(q, bits) for q in range(levels + 1)], dtype=np.float64)
    q = np.clip(np.searchsorted(exp, ch, side="right") - 1, 0, levels - 1)
    lo, hi = exp[q], exp[q + 1]
    v = q + np.clip((ch - lo) / (hi - lo), 0.0, 1.0)
    out = np.clip(np.floor(v + thr), 0, levels).astype(np.int64)
    return np.array([_expand(int(x), bits) for x in range(levels + 1)])[out]

def to565(img):
    """float RGB array (h, w, 3) 0..255 -> uint8 array of RGB565 values"""
    h, w, _ = img.shape
    thr = (np.tile(_B8, (h // 8 + 1, w // 8 + 1))[:h, :w] + 0.5) / 64.0
    out = np.zeros((h, w, 3), dtype=np.uint8)
    for i, bits in enumerate((5, 6, 5)):
        out[..., i] = _quant(img[..., i], bits, thr)
    return out

def save24(arr, name):
    Image.fromarray(to565(arr)).save(os.path.join(OUT, name))

def save32(rgba, path):
    """32 bit BMP with alpha (BI_RGB, bottom-up); colour dithered to 565"""
    h, w, _ = rgba.shape
    rgb = to565(rgba[..., :3].astype(np.float64))
    a = np.clip(rgba[..., 3], 0, 255).astype(np.uint8)
    px = np.dstack([rgb[..., 2], rgb[..., 1], rgb[..., 0], a])[::-1]
    data = px.tobytes()
    hdr = struct.pack("<2sIHHI", b"BM", 54 + len(data), 0, 0, 54)
    info = struct.pack("<IiiHHIIiiII", 40, w, h, 1, 32, 0, len(data), 2835, 2835, 0, 0)
    with open(path, "wb") as f:
        f.write(hdr + info + data)

# --- drawing helpers (float canvases) -------------------------------------
def canvas(w, h, col):
    a = np.zeros((h, w, 3), dtype=np.float64)
    a[...] = col
    return a

def over(dst, col, alpha, x=0, y=0):
    """blend colour with alpha mask (h, w, 0..1) onto dst at x, y (clipped)"""
    h, w = alpha.shape
    x0, y0 = max(x, 0), max(y, 0)
    x1, y1 = min(x + w, dst.shape[1]), min(y + h, dst.shape[0])
    if x1 <= x0 or y1 <= y0:
        return
    a = alpha[y0 - y:y1 - y, x0 - x:x1 - x, None]
    dst[y0:y1, x0:x1] = dst[y0:y1, x0:x1] * (1 - a) + np.asarray(col, dtype=np.float64) * a

def blurred_rect(w, h, blur, pad):
    m = Image.new("L", (w + 2 * pad, h + 2 * pad), 0)
    ImageDraw.Draw(m).rectangle([pad, pad, pad + w - 1, pad + h - 1], fill=255)
    if blur > 0:
        m = m.filter(ImageFilter.GaussianBlur(blur))
    return np.asarray(m, dtype=np.float64) / 255.0

def card(dst, x, y, w, h, col, elevation):
    """flat card with a soft two-part shadow (key light from above)"""
    if elevation == "high":
        layers = ((16, 10, 0.70), (4, 3, 0.45))   # (blur, y offset, opacity)
    else:
        layers = ((8, 5, 0.55), (2, 1, 0.40))
    for blur, dy, op in layers:
        pad = blur * 3
        m = blurred_rect(w, h, blur, pad) * op
        over(dst, (0, 0, 0), m, x - pad, y - pad + dy)
    dst[y:y + h, x:x + w] = col

# --- glyphs --------------------------------------------------------------
_fonts = {}

def inter(weight, size):
    key = ("inter", weight, size)
    if key not in _fonts:
        _fonts[key] = ImageFont.truetype(os.path.join(FONTS, "Inter-%s.ttf" % weight), size)
    return _fonts[key]

def msym_font(size, fill=0, weight=400):
    key = ("msym", size, fill, weight)
    if key not in _fonts:
        path = os.path.join(CACHE, "MaterialSymbolsSharp.ttf")
        if not os.path.exists(path):
            os.makedirs(CACHE, exist_ok=True)
            print("downloading Material Symbols Sharp ...")
            urllib.request.urlretrieve(MSYM_URL + ".ttf", path)
            urllib.request.urlretrieve(MSYM_URL + ".codepoints",
                                       os.path.join(CACHE, "MaterialSymbolsSharp.codepoints"))
        f = ImageFont.truetype(path, size)
        axes = {a["name"]: a for a in f.get_variation_axes()}
        vals = []
        for a in f.get_variation_axes():
            n = a["name"]
            n = n.decode() if isinstance(n, bytes) else n
            vals.append({"Fill": fill, "Grade": 0, "Optical size": max(20, min(48, size)),
                         "Weight": weight}.get(n, a["default"]))
        f.set_variation_by_axes(vals)
        _fonts[key] = f
    return _fonts[key]

_cps = None

def msym_cp(name):
    global _cps
    if _cps is None:
        msym_font(24)
        _cps = {}
        with open(os.path.join(CACHE, "MaterialSymbolsSharp.codepoints")) as f:
            for line in f:
                n, cp = line.split()
                _cps[n] = chr(int(cp, 16))
    return _cps[name]

def glyph_mask(name, size, box=None, fill=0, weight=400):
    """alpha mask (box x box) of a Material symbol, centred"""
    box = box or size
    m = Image.new("L", (box, box), 0)
    f = msym_font(size, fill, weight)
    ch = msym_cp(name)
    l, t, r, b = f.getbbox(ch)
    ImageDraw.Draw(m).text(((box - (r - l)) / 2 - l, (box - (b - t)) / 2 - t), ch, font=f, fill=255)
    return np.asarray(m, dtype=np.float64) / 255.0

# --- layout (mirrored in the skins) ----------------------------------------
COVER = (112, 46, 256, 256)
PCARD = (24, 428, 432, 80)        # progress card
PBAR = (44, 446, 392, 6)          # progress bar inside the card
CHIP = (344, 532, 112, 38)        # "3 / 12"
VCARD = (20, 428, 440, 150)       # volume overlay card
VREGION = (0, 418, 480, 222)      # opaque image region covering VCARD + shadow
VBAR = (44, 534, 392, 14)
MINI = (16, 544, 448, 86)         # mini player in the menus (SBS)
MINI_ART = (26, 555, 64, 64)
MINI_BAR = (16, 627, 448, 3)


def wps_backdrop(with_volume=False):
    a = canvas(W, H, BG)
    x, y, w, h = COVER
    card(a, x, y, w, h, SURFACE2, "high")
    x, y, w, h = PCARD
    card(a, x, y, w, h, SURFACE, "low")
    x, y, w, h = CHIP
    card(a, x, y, w, h, SURFACE, "low")
    if with_volume:
        x, y, w, h = VCARD
        card(a, x, y, w, h, SURFACE2, "high")
    return a


def main():
    os.makedirs(OUT, exist_ok=True)

    # WPS
    save24(wps_backdrop(), "wps-backdrop.bmp")
    vx, vy, vw, vh = VREGION
    vol = wps_backdrop(True)
    save24(vol[vy:vy + vh, vx:vx + vw], "vol-card.bmp")

    # no album art: placeholder with a note
    x, y, w, h = COVER
    ph = canvas(w, h, SURFACE2)
    over(ph, TER, glyph_mask("music_note", 96, 160), (w - 160) // 2, (h - 160) // 2)
    save24(ph, "cover-none.bmp")

    # progress and volume bars (track / fill)
    for name, (x, y, w, h), bg in (("pb", PBAR, SURFACE), ("vol", VBAR, SURFACE2)):
        save24(canvas(w, h, TRACK), "%s-track.bmp" % name)
        save24(canvas(w, h, ACCENT), "%s-fill.bmp" % name)

    # volume icon on the overlay card
    ic = canvas(40, 40, SURFACE2)
    over(ic, ACCENT, glyph_mask("volume_up", 36, 40, fill=1))
    save24(ic, "vol-icon.bmp")

    # status bar icons (20x20, on BG): play, pause, stop | shuffle | repeat, repeat one
    def strip(names, col, size=20, glyph=18, fill=1):
        s = canvas(size, size * len(names), BG)
        for i, n in enumerate(names):
            if n:
                over(s, col, glyph_mask(n, glyph, size, fill=fill), 0, i * size)
        return s
    save24(strip(["play_arrow", "pause", "stop"], TEXT), "st-playmode.bmp")
    save24(strip(["shuffle"], SEC, fill=0), "st-shuffle.bmp")
    save24(strip(["repeat", "repeat_one", "repeat"], SEC, fill=0), "st-repeat.bmp")

    # battery: 11 levels (0..100 %), 28x16
    bat = canvas(28, 16 * 11, BG)
    for i in range(11):
        y0 = i * 16
        m = np.zeros((16, 28))
        m[2:14, 1:24] = 1.0
        m[4:12, 3:22] = 0.0
        m[5:11, 24:26] = 1.0
        over(bat, SEC, m, 0, y0)
        fill = int(round(16 * i / 10))
        if fill:
            f = np.zeros((16, 28))
            f[5:11, 4:4 + fill] = 1.0
            over(bat, TEXT if i > 1 else c565("E07060"), f, 0, y0)
    save24(bat, "st-battery.bmp")

    # menus (SBS): background with the mini player card
    s = canvas(W, H, BG)
    x, y, w, h = MINI
    card(s, x, y, w, h, SURFACE2, "high")
    save24(s, "sbs-backdrop.bmp")
    x, y, w, h = MINI_ART
    m = canvas(w, h, TRACK)
    over(m, TER, glyph_mask("music_note", 32, 40), (w - 40) // 2, (h - 40) // 2)
    save24(m, "mini-none.bmp")
    x, y, w, h = MINI_BAR
    save24(canvas(w, h, TRACK), "mini-track.bmp")
    save24(canvas(w, h, ACCENT), "mini-fill.bmp")
    mp = canvas(28, 84, SURFACE2)
    for i, n in enumerate(["play_arrow", "pause", "stop"]):
        over(mp, ACCENT, glyph_mask(n, 28, 28, fill=1), 0, i * 28)
    save24(mp, "mini-playmode.bmp")

    # list icons, order of enum themable_icons (apps/gui/icon.h)
    names = ["music_note", "folder", "queue_music", "chevron_right", "play_circle",
             "memory", "font_download", "language", "tune", "extension",
             "bookmark", "star", "playlist_add", "drag_indicator", "keyboard",
             "chevron_left", "help", "settings", "arrow_forward", "chevron_right",
             "settings", "mic", "record_voice_over", "settings", "info",
             "play_circle", "display_settings", "smartphone", "radio", "folder",
             "equalizer", "album"]
    size = 32
    strip_a = np.zeros((size * len(names), size, 4))
    for i, n in enumerate(names):
        m = glyph_mask(n, 30, size)
        strip_a[i * size:(i + 1) * size, :, :3] = ICON
        strip_a[i * size:(i + 1) * size, :, 3] = m * 255
    save32(strip_a, os.path.join(ICONS, "podbox_icons.32x32.bmp"))

    with open(os.path.join(OUT, "colors.txt"), "w") as f:
        for n in ("BG", "SURFACE", "SURFACE2", "TEXT", "SEC", "TER", "TRACK", "ACCENT", "ICON"):
            f.write("%s %s\n" % (n, hexcol(globals()[n])))
    print("ok:", OUT)


if __name__ == "__main__":
    sys.exit(main())
