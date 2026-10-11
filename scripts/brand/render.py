#!/usr/bin/env python3
"""
The brand's files, drawn by one script from the design module's own constants, so that the mark
on the identity page, in a browser tab, in a wallet's approval sheet and on the dashboard cannot
drift from the mark the app draws.

    python3 scripts/brand/render.py                  write every file
    python3 scripts/brand/render.py --check          write nothing; exit 1 if a committed file differs
    python3 scripts/brand/render.py --previews DIR   also write PNGs to look at (never committed)
    python3 -m unittest discover -s scripts/brand    the tests of this script and of the page

READS (android/core/design is the one place that may write a colour, the mark or a font):
  BrandGlyph.kt      HdGlyph: the mark on its 24-unit grid (HULL, SEAM) and the launcher icon on
                     its 108-unit grid (LAUNCHER_FACE, LAUNCHER_EDGE, LAUNCHER_UNDERSIDE, LAUNCHER_SEAM)
  colors.xml         hd_pit, hd_chalk, hd_seam and the icon's own shades (hd_icon_*)
  HdColors.kt        HdArgb, the palette in Kotlin, dark and light, and the hairline's alpha
  big_shoulders_display_extrabold.ttf
                     the display face's weight-800 instance, for the outlines of the wordmark

WRITES
  brand/mark.svg                 the mark in colour on its pit tile: the launcher icon's centre
  brand/mark-mono.svg            the mark as a silhouette in currentColor: a bar over a line
  brand/wordmark.svg             HEADS over DOWN, the seam line between them; for a dark surface
  brand/wordmark-mono.svg        the same in currentColor
  site/icon.png                  512x512, opaque: what a wallet shows as the app's identity
  site/favicon.ico               16, 32, 48 and 64 px, each drawn on its own pixel grid
  dashboard/src/app/icon.svg     the dashboard's tab icon
  site/index.html                ONLY the lines between the two "mark row" comments
CHECKS ONLY (never written)
  dashboard/src/app/layout.tsx   carries the mark's two paths, as HdGlyph spells them
  dashboard/src/app/globals.css  its page, card, ink, ember and gold are HdArgb's, dark and light
  site/index.html                its seven tokens are HdArgb's, dark and light; it has no script,
                                 no form, and loads nothing but icon.png and favicon.ico

Needs Python 3.9 or later, Pillow 11 and fontTools 4.60 (the versions FONTS.md in the design
module names). A raster is compared by its pixels, so another zlib does not fail the check.
"""

from __future__ import annotations

import argparse
import io
import re
import sys
from pathlib import Path
from typing import Callable, Dict, List, Optional, Sequence, Tuple

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw

REPO = Path(__file__).resolve().parents[2]

DESIGN = "android/core/design/src/main"
GLYPH_KT = DESIGN + "/kotlin/xyz/headsdown/core/design/BrandGlyph.kt"
COLORS_XML = DESIGN + "/res/values/colors.xml"
PALETTE_KT = DESIGN + "/kotlin/xyz/headsdown/core/design/HdColors.kt"
DISPLAY_FONT = DESIGN + "/res/font/big_shoulders_display_extrabold.ttf"

MARK_SVG = "brand/mark.svg"
MARK_MONO_SVG = "brand/mark-mono.svg"
WORDMARK_SVG = "brand/wordmark.svg"
WORDMARK_MONO_SVG = "brand/wordmark-mono.svg"
SITE_ICON = "site/icon.png"
SITE_FAVICON = "site/favicon.ico"
SITE_INDEX = "site/index.html"
DASHBOARD_ICON = "dashboard/src/app/icon.svg"
DASHBOARD_LAYOUT = "dashboard/src/app/layout.tsx"
DASHBOARD_CSS = "dashboard/src/app/globals.css"

# Which role of the palette each CSS variable carries. Every one is defined twice in its file:
# the dark value first, then the light one.
SITE_TOKENS = {"--pit": "PIT", "--slab": "SLAB", "--chalk": "CHALK", "--ash": "ASH", "--seam": "SEAM", "--ember": "EMBER"}
DASHBOARD_TOKENS = {"--bg": "PIT", "--surface": "SLAB", "--text": "CHALK", "--text-muted": "ASH", "--gold": "SEAM", "--ember": "EMBER"}

# The launcher icon is drawn on a 108-unit grid of which a launcher shows the central 72 (the
# rest is the bleed an adaptive icon needs). The web icons are that centre, edge to edge, so a
# wallet or a browser that masks them to a circle still shows the whole mark: its farthest
# corner is 30.8 units from the centre and the inscribed circle has a radius of 36.
LAUNCHER_GRID = 108
CROP = 18
CROP_SIDE = 72

ICON_SIZE = 512
FAVICON_SIZES = (16, 32, 48, 64)
SUPERSAMPLE = 4

# At 32 px and below the face of the slab is three or four pixels of a grey that is 1.6:1 on the
# pit. It is lifted one step so the slab still reads as an object and not as a hole.
SMALL_FACE = "#4A4A50"
SMALL_MAX = 32

# The wordmark, in the font's own units (2000 to the em, capitals 1600 tall).
WORDMARK_TRACKING_EM = -0.02  # Hd.type.display: letterSpacing = (-0.02).em
WORDMARK_GAP = 300  # between a word and the line
WORDMARK_LINE = 120  # the seam line: 2 px when the lockup is 64 px tall
WORDMARK_SCALE = 0.1  # font units to SVG units: one decimal keeps every point exact

MARK_ROW_BEGIN = "  <!-- mark row: written by scripts/brand/render.py; change it there, not here -->"
MARK_ROW_END = "  <!-- end of mark row -->"
SITE_WORDMARK_HEIGHT = 64


# ───────────────────────── what the design module says ─────────────────────────


def read_glyph(repo: Path = REPO) -> Dict[str, str]:
    """HdGlyph's string constants, by name."""
    text = (repo / GLYPH_KT).read_text(encoding="utf-8")
    found = dict(re.findall(r'const val (\w+) = "([^"]+)"', text))
    for name in ("HULL", "SEAM", "LAUNCHER_FACE", "LAUNCHER_EDGE", "LAUNCHER_UNDERSIDE", "LAUNCHER_SEAM"):
        if name not in found:
            raise SystemExit("HdGlyph.%s is not in %s" % (name, GLYPH_KT))
    return found


def read_colors(repo: Path = REPO) -> Dict[str, str]:
    """The XML palette as #RRGGBB. Every colour in it is opaque; one that is not stops the script."""
    text = (repo / COLORS_XML).read_text(encoding="utf-8")
    colors: Dict[str, str] = {}
    for name, argb in re.findall(r'<color name="(\w+)">#([0-9A-Fa-f]{8})</color>', text):
        if argb[:2].upper() != "FF":
            raise SystemExit("%s is not opaque: #%s" % (name, argb))
        colors[name] = "#" + argb[2:].upper()
    for name in ("hd_pit", "hd_chalk", "hd_seam", "hd_icon_background", "hd_icon_face", "hd_icon_edge", "hd_icon_underside_shadow", "hd_icon_seam"):
        if name not in colors:
            raise SystemExit("%s is not in %s" % (name, COLORS_XML))
    return colors


def read_palette(repo: Path = REPO) -> Dict[str, Dict[str, str]]:
    """HdArgb as {"dark": {"PIT": "#RRGGBB", ...}, "light": {...}}, and the hairline's alpha under "alpha"."""
    text = (repo / PALETTE_KT).read_text(encoding="utf-8")
    try:
        start = text.index("object HdArgb")
        day = text.index("object Day", start)
        end = text.index("object HdCompat", day)
    except ValueError:
        raise SystemExit("%s no longer has HdArgb, HdArgb.Day and HdCompat in that order" % PALETTE_KT)

    def constants(block: str) -> Dict[str, str]:
        found = {}
        for name, argb in re.findall(r"const val (\w+): Int = 0x([0-9A-Fa-f]{8})\.toInt\(\)", block):
            if argb[:2].upper() != "FF":
                raise SystemExit("HdArgb.%s is not opaque" % name)
            found[name] = "#" + argb[2:].upper()
        return found

    alpha = re.search(r"const val HAIRLINE_ALPHA = ([0-9.]+)f", text)
    palette = {"dark": constants(text[start:day]), "light": constants(text[day:end]), "alpha": {"HAIRLINE": alpha.group(1) if alpha else ""}}
    for mode in ("dark", "light"):
        for role in ("PIT", "SLAB", "CHALK", "ASH", "SEAM", "EMBER"):
            if role not in palette[mode]:
                raise SystemExit("HdArgb has no %s for %s" % (role, mode))
    return palette


def token_problems(css: str, tokens: Dict[str, str], palette: Dict[str, Dict[str, str]]) -> List[str]:
    """Each variable is defined exactly twice, as the palette's dark value and then its light one."""
    problems = []
    for variable, role in sorted(tokens.items()):
        values = [v.upper() for v in re.findall(r"(?<![\w-])%s:\s*(#[0-9A-Fa-f]{6})\b" % re.escape(variable), css)]
        expected = [palette["dark"][role], palette["light"][role]]
        if values != expected:
            problems.append("%s is %s; HdArgb.%s is %s (dark, then light)" % (variable, ", ".join(values) or "not defined as a colour", role, ", ".join(expected)))
    return problems


def hairline_problems(css: str, variable: str, palette: Dict[str, Dict[str, str]]) -> List[str]:
    """The hairline is the ink at the design module's alpha, in both palettes, written as rgb(r g b / a)."""
    found = re.findall(r"(?<![\w-])%s:\s*rgb\((\d+) (\d+) (\d+) / ([0-9.]+)\)" % re.escape(variable), css)
    expected = [tuple(str(c) for c in rgb(palette[mode]["CHALK"])) + (palette["alpha"]["HAIRLINE"],) for mode in ("dark", "light")]
    if found != expected:
        return ["%s is %s; the hairline is the ink at %s: %s" % (variable, found or "not defined", palette["alpha"]["HAIRLINE"], expected)]
    return []


def polygon(path: str) -> List[Tuple[float, float]]:
    """
    The corners of one closed path made of straight lines (M, L, H, V and their relative forms,
    then Z): the whole grammar the mark uses. Anything else stops the script.
    """
    if re.search(r"[^MmLlHhVvZz0-9.,\s-]", path):
        raise SystemExit("a curve or an arc in %r: the mark is made of straight lines" % path)
    tokens = re.findall(r"[MmLlHhVvZz]|-?\d+(?:\.\d+)?", path)
    points: List[Tuple[float, float]] = []
    x = y = 0.0
    i = 0
    closed = False
    while i < len(tokens):
        command = tokens[i]
        i += 1
        if command in "Zz":
            closed = True
            if i != len(tokens):
                raise SystemExit("more than one subpath in %r" % path)
            break
        if command not in "MmLlHhVv":
            raise SystemExit("unexpected %r in %r" % (command, path))
        # A command's numbers may repeat: "l-3,4 2,2" is two lines.
        while i < len(tokens) and tokens[i] not in "MmLlHhVvZz":
            if command in "Hh":
                dx = float(tokens[i])
                i += 1
                x = x + dx if command == "h" else dx
            elif command in "Vv":
                dy = float(tokens[i])
                i += 1
                y = y + dy if command == "v" else dy
            else:
                px, py = float(tokens[i]), float(tokens[i + 1])
                i += 2
                x, y = (x + px, y + py) if command in "ml" else (px, py)
            points.append((x, y))
    if not closed or len(points) < 3:
        raise SystemExit("not a closed polygon: %r" % path)
    return points


def bounds(points: Sequence[Tuple[float, float]]) -> Tuple[float, float, float, float]:
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    return min(xs), min(ys), max(xs), max(ys)


def num(value: float) -> str:
    """A number as SVG wants it: no trailing zeros, no "-0"."""
    text = ("%.1f" % value).rstrip("0").rstrip(".")
    return "0" if text in ("-0", "") else text


# ───────────────────────── the mark, as vectors ─────────────────────────


def mark_svg(glyph: Dict[str, str], colors: Dict[str, str]) -> str:
    """The launcher icon's centre: the slab from a low angle over its line of light, on the pit."""
    _, top, _, bottom = bounds(polygon(glyph["LAUNCHER_UNDERSIDE"]))
    middle = num(LAUNCHER_GRID / 2)
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="{c} {c} {s} {s}" role="img" aria-labelledby="t">'
        '<title id="t">Heads Down</title>'
        '<defs><linearGradient id="u" gradientUnits="userSpaceOnUse" x1="{m}" y1="{y1}" x2="{m}" y2="{y2}">'
        '<stop offset="0" stop-color="{shadow}"/><stop offset="1" stop-color="{seam}"/></linearGradient></defs>'
        '<rect x="{c}" y="{c}" width="{s}" height="{s}" fill="{pit}"/>'
        '<path d="{face_d}" fill="{face}"/>'
        '<path d="{edge_d}" fill="{edge}"/>'
        '<path d="{under_d}" fill="url(#u)"/>'
        '<path d="{seam_d}" fill="{seam}"/>'
        "</svg>\n"
    ).format(
        c=CROP,
        s=CROP_SIDE,
        m=middle,
        y1=num(top),
        y2=num(bottom),
        shadow=colors["hd_icon_underside_shadow"],
        seam=colors["hd_icon_seam"],
        pit=colors["hd_icon_background"],
        face=colors["hd_icon_face"],
        edge=colors["hd_icon_edge"],
        face_d=glyph["LAUNCHER_FACE"],
        edge_d=glyph["LAUNCHER_EDGE"],
        under_d=glyph["LAUNCHER_UNDERSIDE"],
        seam_d=glyph["LAUNCHER_SEAM"],
    )


def mark_mono_svg(glyph: Dict[str, str]) -> str:
    """The silhouette the status bar and the Quick Settings tile carry, in the reader's own ink."""
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" role="img" aria-labelledby="t">'
        '<title id="t">Heads Down</title>'
        '<path d="{hull}" fill="currentColor"/><path d="{seam}" fill="currentColor"/>'
        "</svg>\n"
    ).format(hull=glyph["HULL"], seam=glyph["SEAM"])


def dashboard_icon_svg(glyph: Dict[str, str], colors: Dict[str, str]) -> str:
    """A tab icon: the pit as a small slab, the mark on it in chalk over its gold line."""
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
        '<rect width="24" height="24" rx="3" fill="{pit}"/>'
        '<path d="{hull}" fill="{chalk}"/><path d="{seam}" fill="{gold}"/>'
        "</svg>\n"
    ).format(pit=colors["hd_pit"], chalk=colors["hd_chalk"], gold=colors["hd_seam"], hull=glyph["HULL"], seam=glyph["SEAM"])


# ───────────────────────── the wordmark ─────────────────────────


class Wordmark:
    """The outlines of HEADS, of the line and of DOWN, in SVG units, and the box they fill."""

    def __init__(self, heads: str, line: str, down: str, width: float, height: float):
        self.heads = heads
        self.line = line
        self.down = down
        self.width = width
        self.height = height

    @property
    def view_box(self) -> str:
        return "0 0 %s %s" % (num(self.width), num(self.height))


def _pair_kerning(font: TTFont) -> Callable[[str, str], int]:
    """
    The font's own pair kerning (GPOS "kern", pair adjustments of formats 1 and 2), which is what
    the text engine on a phone applies to the same two words. fontTools does not shape text, so the
    lookups are read here: the first subtable that covers the left glyph and has the pair decides.
    """
    if "GPOS" not in font:
        return lambda left, right: 0
    table = font["GPOS"].table
    wanted = set()
    for record in table.FeatureList.FeatureRecord:
        if record.FeatureTag == "kern":
            wanted.update(record.Feature.LookupListIndex)
    subtables = []
    for index in sorted(wanted):
        lookup = table.LookupList.Lookup[index]
        for sub in lookup.SubTable:
            if lookup.LookupType == 9:  # an extension wraps the real subtable
                sub = sub.ExtSubTable
            if type(sub).__name__ != "PairPos":
                raise SystemExit("the display font kerns with something other than pairs: %s" % type(sub).__name__)
            subtables.append(sub)

    def kern(left: str, right: str) -> int:
        for sub in subtables:
            if left not in sub.Coverage.glyphs:
                continue
            if sub.Format == 1:
                pair_set = sub.PairSet[sub.Coverage.glyphs.index(left)]
                for pair in pair_set.PairValueRecord:
                    if pair.SecondGlyph == right:
                        return (pair.Value1.XAdvance or 0) if pair.Value1 else 0
                continue
            first = sub.ClassDef1.classDefs.get(left, 0)
            second = sub.ClassDef2.classDefs.get(right, 0)
            value = sub.Class1Record[first].Class2Record[second].Value1
            return (value.XAdvance or 0) if value else 0
        return 0

    return kern


def wordmark(repo: Path = REPO) -> Wordmark:
    """
    HEADS over DOWN with the seam line between them, set as the app's `Wordmark` sets them: the
    display face at weight 800, tracked -0.02em, kerned by the font, both words from the same
    left edge, the line as wide as the wider word.
    """
    font = TTFont(str(repo / DISPLAY_FONT))
    if font["OS/2"].usWeightClass != 800:
        raise SystemExit("the display font is not the weight-800 instance")
    upm = font["head"].unitsPerEm
    cap = font["OS/2"].sCapHeight
    glyphs = font.getGlyphSet()
    cmap = font.getBestCmap()
    glyf = font["glyf"]
    hmtx = font["hmtx"]
    kern = _pair_kerning(font)
    tracking = WORDMARK_TRACKING_EM * upm

    def lay(word: str) -> Tuple[List[Tuple[str, float]], float, float, float]:
        """Each glyph and where its origin sits, then the ink's right edge, top and bottom."""
        names = [cmap[ord(ch)] for ch in word]
        x = -float(glyf[names[0]].xMin)  # the first stem starts at 0, not at its side bearing
        placed: List[Tuple[str, float]] = []
        for i, name in enumerate(names):
            if i:
                x += kern(names[i - 1], name) + tracking
            placed.append((name, x))
            x += hmtx[name][0]
        last, last_x = placed[-1]
        right = last_x + glyf[last].xMax
        return placed, right, max(glyf[n].yMax for n in names), min(glyf[n].yMin for n in names)

    heads, heads_right, heads_top, _ = lay("HEADS")
    down, down_right, _, down_bottom = lay("DOWN")
    # Round letters overshoot the cap line and the baseline by a few units: the box includes them.
    over_top = max(0, heads_top - cap)
    over_bottom = max(0, -down_bottom)
    width = max(heads_right, down_right)
    heads_baseline = over_top + cap
    line_top = heads_baseline + WORDMARK_GAP
    down_baseline = line_top + WORDMARK_LINE + WORDMARK_GAP + cap
    height = down_baseline + over_bottom

    scale = WORDMARK_SCALE

    def outline(placed: List[Tuple[str, float]], baseline: float) -> str:
        pen = SVGPathPen(glyphs, ntos=num)
        for name, x in placed:
            # Font units have y up from the baseline; SVG has y down from the top.
            glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, x * scale, baseline * scale)))
        return pen.getCommands()

    line = "M0 %sH%sV%sH0Z" % (num(line_top * scale), num(width * scale), num((line_top + WORDMARK_LINE) * scale))
    return Wordmark(outline(heads, heads_baseline), line, outline(down, down_baseline), width * scale, height * scale)


def wordmark_svg(mark: Wordmark, colors: Dict[str, str]) -> str:
    """Chalk over gold: for the pit, a slab or any dark surface."""
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="{box}" role="img" aria-labelledby="t">'
        '<title id="t">Heads Down</title>'
        '<path d="{heads}" fill="{chalk}"/><path d="{line}{down}" fill="{gold}"/>'
        "</svg>\n"
    ).format(box=mark.view_box, heads=mark.heads, line=mark.line, down=mark.down, chalk=colors["hd_chalk"], gold=colors["hd_seam"])


def wordmark_mono_svg(mark: Wordmark) -> str:
    """One colour, the reader's own ink: for paper, for a light surface, for a stamp."""
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="{box}" role="img" aria-labelledby="t">'
        '<title id="t">Heads Down</title>'
        '<path d="{heads}{line}{down}" fill="currentColor"/>'
        "</svg>\n"
    ).format(box=mark.view_box, heads=mark.heads, line=mark.line, down=mark.down)


# ───────────────────────── the mark, as pixels ─────────────────────────


def rgb(color: str) -> Tuple[int, int, int]:
    return int(color[1:3], 16), int(color[3:5], 16), int(color[5:7], 16)


def icon_geometry(glyph: Dict[str, str], size: int) -> Dict[str, int]:
    """
    Where the mark sits on a `size` px grid: every horizontal and vertical edge on a whole pixel,
    and the right half the mirror of the left (the mark is symmetric about the middle of its
    grid). At 512 px this gives the face (78,128)-(434,199), the underside down to y 270 between
    x 128 and 384, and the seam (114,341)-(398,377).
    """
    unit = size / CROP_SIDE

    def px(value: float) -> int:
        return int(round((value - CROP) * unit))

    def span(value: float) -> int:
        return max(1, int(round(value * unit)))

    face = bounds(polygon(glyph["LAUNCHER_FACE"]))
    edge = bounds(polygon(glyph["LAUNCHER_EDGE"]))
    under = polygon(glyph["LAUNCHER_UNDERSIDE"])
    under_box = bounds(under)
    seam = bounds(polygon(glyph["LAUNCHER_SEAM"]))
    under_bottom_left = min(x for x, y in under if y == under_box[3])
    for box in (face, under_box, seam):
        if abs((box[0] + box[2]) / 2 - LAUNCHER_GRID / 2) > 1e-6:
            raise SystemExit("the launcher mark is no longer symmetric about the middle of its grid")

    face_top = px(face[1])
    face_bottom = face_top + span(face[3] - face[1])
    return {
        "face_left": px(face[0]),
        "face_top": face_top,
        "face_bottom": face_bottom,
        "edge_height": span(edge[3] - edge[1]),
        "under_bottom": face_bottom + span(under_box[3] - under_box[1]),
        "under_bottom_left": px(under_bottom_left),
        "seam_left": px(seam[0]),
        "seam_top": px(seam[1]),
        "seam_bottom": px(seam[1]) + span(seam[3] - seam[1]),
    }


def raster_icon(glyph: Dict[str, str], colors: Dict[str, str], size: int) -> Image.Image:
    """
    The mark on the pit, `size` px square and opaque. Drawn four times too large without
    antialiasing and averaged down four to one, which leaves the edges that sit on whole pixels
    exact and smooths only the two slanted sides of the underside.
    """
    g = icon_geometry(glyph, size)
    k = SUPERSAMPLE
    big = size * k
    small = size <= SMALL_MAX
    image = Image.new("RGB", (big, big), rgb(colors["hd_icon_background"]))
    draw = ImageDraw.Draw(image)

    left, right = g["face_left"] * k, (size - g["face_left"]) * k
    draw.rectangle([left, g["face_top"] * k, right - 1, g["face_bottom"] * k - 1], fill=rgb(SMALL_FACE if small else colors["hd_icon_face"]))
    if not small:
        # The top edge catches a little light. Below 33 px it would be a line as bright as the face is tall.
        draw.rectangle([left, g["face_top"] * k, right - 1, (g["face_top"] + g["edge_height"]) * k - 1], fill=rgb(colors["hd_icon_edge"]))

    # The underside: a trapezoid, shadow under the face to gold at its lower edge.
    shadow, gold = rgb(colors["hd_icon_underside_shadow"]), rgb(colors["hd_icon_seam"])
    top, bottom = g["face_bottom"] * k, g["under_bottom"] * k
    inset = (g["under_bottom_left"] - g["face_left"]) * k
    for y in range(top, bottom):
        t = (y - top + 0.5) / (bottom - top)
        dx = int(round(inset * t))
        color = tuple(int(round(shadow[c] + (gold[c] - shadow[c]) * t)) for c in range(3))
        draw.rectangle([left + dx, y, right - dx - 1, y], fill=color)

    seam_left, seam_right = g["seam_left"] * k, (size - g["seam_left"]) * k
    draw.rectangle([seam_left, g["seam_top"] * k, seam_right - 1, g["seam_bottom"] * k - 1], fill=gold)
    return image.reduce(k)


def png_bytes(image: Image.Image) -> bytes:
    out = io.BytesIO()
    image.save(out, format="PNG", optimize=True)
    return out.getvalue()


def favicon_bytes(glyph: Dict[str, str], colors: Dict[str, str]) -> bytes:
    """One .ico holding every size, each its own drawing (not one picture scaled four times)."""
    frames = [raster_icon(glyph, colors, size).convert("RGBA") for size in FAVICON_SIZES]
    out = io.BytesIO()
    frames[-1].save(out, format="ICO", sizes=[(s, s) for s in FAVICON_SIZES], append_images=frames[:-1])
    return out.getvalue()


def ico_frames(data: bytes) -> Dict[Tuple[int, int], bytes]:
    with Image.open(io.BytesIO(data)) as image:
        if image.format != "ICO":
            raise ValueError("not an .ico")
        return {size: image.ico.getimage(size).convert("RGBA").tobytes() for size in sorted(image.ico.sizes())}


def png_pixels(data: bytes) -> Tuple[Tuple[int, int], str, bytes]:
    with Image.open(io.BytesIO(data)) as image:
        if image.format != "PNG":
            raise ValueError("not a PNG")
        return image.size, image.mode, image.tobytes()


# ───────────────────────── the identity page ─────────────────────────


def site_mark_row(mark: Wordmark) -> str:
    """
    The icon and the wordmark, side by side, as the first thing on the identity page. The
    wordmark is inline so the page asks the network for nothing but its own icon; its two inks
    come from the page's own tokens (classes `wm-ink` and `wm-seam`), so it follows the palette.
    Both are decoration to a screen reader: the heading under them says the name.
    """
    width = int(round(SITE_WORDMARK_HEIGHT * mark.width / mark.height))
    return "\n".join(
        [
            MARK_ROW_BEGIN,
            '  <div class="mark">',
            '    <img src="icon.png" width="64" height="64" alt="">',
            '    <svg class="wordmark" viewBox="%s" width="%d" height="%d" aria-hidden="true" focusable="false">'
            '<path class="wm-ink" d="%s"/><path class="wm-seam" d="%s%s"/></svg>' % (mark.view_box, width, SITE_WORDMARK_HEIGHT, mark.heads, mark.line, mark.down),
            "  </div>",
            MARK_ROW_END,
        ]
    )


def site_index(current: str, mark: Wordmark) -> str:
    """`current` with the lines between the two mark-row comments replaced. Nothing else moves."""
    begin = current.find(MARK_ROW_BEGIN)
    end = current.find(MARK_ROW_END)
    if begin < 0 or end < begin:
        raise SystemExit("%s has lost its mark-row comments: put them back around the icon row" % SITE_INDEX)
    return current[:begin] + site_mark_row(mark) + current[end + len(MARK_ROW_END) :]


# What a page can make a browser fetch. A plain link (<a href>) is not a fetch: the page links
# to ORE and to the source on purpose.
_LOADS = re.compile(
    r"""<(?:img|script|iframe|embed|source|audio|video|track|input|object|link|use|image)\b[^>]*?\s(?:src|href|data|srcset|poster|xlink:href)\s*=\s*["']?([^"'\s>]+)"""
    r"""|url\(\s*["']?([^"')\s]+)|@import\s+["']([^"']+)""",
    re.IGNORECASE,
)


def external_loads(html: str) -> List[str]:
    """Every URL the page would fetch that is not a file beside it."""
    found = []
    for match in _LOADS.finditer(html):
        url = next(group for group in match.groups() if group)
        if re.match(r"(?:[a-z][a-z0-9+.-]*:|//)", url, re.IGNORECASE) or url.startswith("/"):
            found.append(url)
    return found


def site_problems(html: str) -> List[str]:
    """What must stay true of the identity page whoever edits it."""
    problems = ["loads %s" % url for url in external_loads(html)]
    local = sorted(set(url for m in _LOADS.finditer(html) for url in m.groups() if url) - set(external_loads(html)))
    if local != ["favicon.ico", "icon.png"]:
        problems.append("loads %s; it may load icon.png and favicon.ico only" % ", ".join(local))
    for tag in ("script", "form", "base", "iframe", "object", "embed"):
        if re.search(r"<%s\b" % tag, html, re.IGNORECASE):
            problems.append("has a <%s>" % tag)
    if re.search(r"http-equiv\s*=\s*[\"']?refresh", html, re.IGNORECASE):
        problems.append("redirects with a meta refresh")
    if "http-equiv=\"Content-Security-Policy\"" not in html or "default-src 'none'" not in html:
        problems.append("has no Content-Security-Policy that starts from default-src 'none'")
    return problems


# ───────────────────────── write, or check ─────────────────────────


def render(repo: Path, root: Path) -> Dict[str, bytes]:
    """Every file this script owns, as bytes. Sources come from `repo`; the page is read from `root`."""
    glyph = read_glyph(repo)
    colors = read_colors(repo)
    mark = wordmark(repo)
    files = {
        MARK_SVG: mark_svg(glyph, colors).encode(),
        MARK_MONO_SVG: mark_mono_svg(glyph).encode(),
        WORDMARK_SVG: wordmark_svg(mark, colors).encode(),
        WORDMARK_MONO_SVG: wordmark_mono_svg(mark).encode(),
        DASHBOARD_ICON: dashboard_icon_svg(glyph, colors).encode(),
        SITE_ICON: png_bytes(raster_icon(glyph, colors, ICON_SIZE)),
        SITE_FAVICON: favicon_bytes(glyph, colors),
    }
    index = root / SITE_INDEX
    if index.is_file():
        files[SITE_INDEX] = site_index(index.read_text(encoding="utf-8"), mark).encode()
    return files


def same(path: str, committed: bytes, rendered: bytes) -> bool:
    if committed == rendered:
        return True
    try:
        if path.endswith(".png"):
            return png_pixels(committed) == png_pixels(rendered)
        if path.endswith(".ico"):
            return ico_frames(committed) == ico_frames(rendered)
    except Exception:  # a file that is not an image at all is simply different
        return False
    return False


def check(repo: Path, root: Path) -> List[str]:
    """What differs from what this script would write, one line each. Empty when nothing does."""
    problems: List[str] = []
    for path, rendered in sorted(render(repo, root).items()):
        target = root / path
        if not target.is_file():
            problems.append("%s is missing" % path)
        elif not same(path, target.read_bytes(), rendered):
            problems.append("%s differs from what scripts/brand/render.py draws" % path)

    palette = read_palette(repo)
    index = root / SITE_INDEX
    if not index.is_file():
        problems.append("%s is missing" % SITE_INDEX)
    else:
        html = index.read_text(encoding="utf-8")
        found = site_problems(html) + token_problems(html, SITE_TOKENS, palette) + hairline_problems(html, "--hairline", palette)
        problems += ["%s %s" % (SITE_INDEX, problem) for problem in found]

    css = root / DASHBOARD_CSS
    if not css.is_file():
        problems.append("%s is missing" % DASHBOARD_CSS)
    else:
        text = css.read_text(encoding="utf-8")
        found = token_problems(text, DASHBOARD_TOKENS, palette) + hairline_problems(text, "--outline", palette)
        problems += ["%s: %s" % (DASHBOARD_CSS, problem) for problem in found]

    glyph = read_glyph(repo)
    layout = root / DASHBOARD_LAYOUT
    if not layout.is_file():
        problems.append("%s is missing" % DASHBOARD_LAYOUT)
    else:
        text = layout.read_text(encoding="utf-8")
        for name in ("HULL", "SEAM"):
            if 'd="%s"' % glyph[name] not in text:
                problems.append("%s does not draw HdGlyph.%s (%s)" % (DASHBOARD_LAYOUT, name, glyph[name]))
    return problems


def write(repo: Path, root: Path) -> List[str]:
    written = []
    for path, data in sorted(render(repo, root).items()):
        target = root / path
        if target.is_file() and same(path, target.read_bytes(), data):
            continue  # equal pixels are left alone, so a different zlib does not rewrite a file
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        written.append(path)
    return written


def previews(repo: Path, out: Path) -> List[str]:
    """PNGs to look at: the icon as a wallet scales it, and each favicon size, also enlarged."""
    glyph = read_glyph(repo)
    colors = read_colors(repo)
    out.mkdir(parents=True, exist_ok=True)
    made = []
    icon = raster_icon(glyph, colors, ICON_SIZE)
    for size in (512, 96, 48, 24):
        name = "icon-%d.png" % size
        # Averaged down, as an image loader on a phone does it (no ringing around the gold).
        (icon if size == ICON_SIZE else icon.resize((size, size), Image.BILINEAR, reducing_gap=3.0)).save(out / name)
        made.append(name)
    for size in FAVICON_SIZES:
        frame = raster_icon(glyph, colors, size)
        name = "favicon-%d.png" % size
        frame.save(out / name)
        made.append(name)
        enlarged = "favicon-%d-enlarged.png" % size
        frame.resize((size * (256 // size), size * (256 // size)), Image.NEAREST).save(out / enlarged)
        made.append(enlarged)
    return made


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Draw the brand's files from the design module's constants.")
    parser.add_argument("--check", action="store_true", help="write nothing; exit 1 if a committed file differs")
    parser.add_argument("--root", type=Path, default=REPO, help="where the files are written or checked (default: this repository)")
    parser.add_argument("--previews", type=Path, help="also write PNG previews into this directory")
    args = parser.parse_args(argv)
    root = args.root.resolve()

    if args.check:
        problems = check(REPO, root)
        for problem in problems:
            print(problem, file=sys.stderr)
        if problems:
            print("a file that differs is rewritten by: python3 scripts/brand/render.py; anything else is fixed by hand", file=sys.stderr)
            return 1
        print("brand: every file is what the script draws")
    else:
        written = write(REPO, root)
        print("brand: wrote %s" % (", ".join(written) if written else "nothing (every file was already current)"))
    if args.previews:
        print("previews: %s" % ", ".join(previews(REPO, args.previews.resolve())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
