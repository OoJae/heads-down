"""
The brand's files cannot drift from the design module, and the identity page loads nothing from
anywhere else.

    python3 -m unittest discover -s scripts/brand

Needs what render.py needs (Python 3.9+, Pillow 11, fontTools 4.60). Touches nothing in the
repository: every test that changes a file works on a copy in a temporary directory.
"""

import io
import math
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import render  # noqa: E402

from PIL import Image  # noqa: E402

REPO = render.REPO
SCRIPT = HERE / "render.py"

# Everything `--check` looks at outside the design module.
CHECKED = [
    render.MARK_SVG,
    render.MARK_MONO_SVG,
    render.WORDMARK_SVG,
    render.WORDMARK_MONO_SVG,
    render.SITE_ICON,
    render.SITE_FAVICON,
    render.SITE_INDEX,
    render.DASHBOARD_ICON,
    render.DASHBOARD_LAYOUT,
    render.DASHBOARD_CSS,
]


def run_check(root: Path) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, str(SCRIPT), "--check", "--root", str(root)], capture_output=True, text=True)


class CopyOfTheRepository(unittest.TestCase):
    """A temporary directory holding a copy of every file the check reads outside the design module."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        for path in CHECKED:
            (self.root / path).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPO / path, self.root / path)

    def tearDown(self):
        self._tmp.cleanup()

    def fresh(self):
        """A new copy, for a test that drifts one file after another."""
        self.tearDown()
        self.setUp()

    def assertDrift(self, path: str, says: str = ""):
        problems = render.check(REPO, self.root)
        self.assertTrue(any(p.startswith(path) for p in problems), "%s was changed and the check said: %s" % (path, problems))
        if says:
            self.assertTrue(any(says in p for p in problems), problems)
        result = run_check(self.root)
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn(path, result.stderr)


class TheCommittedFiles(unittest.TestCase):
    def test_every_committed_file_is_what_the_script_draws(self):
        self.assertEqual([], render.check(REPO, REPO))

    def test_the_check_exits_zero_on_the_repository(self):
        result = run_check(REPO)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_the_script_names_no_colour_of_the_palette_itself(self):
        # One literal is the script's own: the face lifted for the two smallest favicon sizes.
        literals = set(re.findall(r"#[0-9A-Fa-f]{6}\b", SCRIPT.read_text(encoding="utf-8")))
        self.assertEqual({render.SMALL_FACE}, literals)


class ADriftedFileFailsTheCheck(CopyOfTheRepository):
    def test_an_untouched_copy_passes(self):
        self.assertEqual([], render.check(REPO, self.root))
        self.assertEqual(0, run_check(self.root).returncode)

    def test_a_moved_point_in_the_mark(self):
        for path in (render.MARK_SVG, render.MARK_MONO_SVG, render.DASHBOARD_ICON):
            with self.subTest(path):
                self.fresh()
                target = self.root / path
                text = target.read_text()
                moved = text.replace("h20", "h21", 1) if "h20" in text else text.replace("h50", "h51", 1)
                self.assertNotEqual(text, moved)
                target.write_text(moved)
                self.assertDrift(path, "differs")

    def test_another_colour_in_the_wordmark(self):
        target = self.root / render.WORDMARK_SVG
        target.write_text(target.read_text().replace(render.read_colors()["hd_seam"], "#FFCC00"))
        self.assertDrift(render.WORDMARK_SVG, "differs")

    def test_one_pixel_of_the_icon(self):
        target = self.root / render.SITE_ICON
        with Image.open(target) as original:
            image = original.convert("RGB")
        r, g, b = image.getpixel((256, 160))
        image.putpixel((256, 160), (r, g, b ^ 1))
        image.save(target)
        self.assertDrift(render.SITE_ICON, "differs")

    def test_the_same_pixels_saved_another_way_are_the_same_icon(self):
        target = self.root / render.SITE_ICON
        with Image.open(target) as original:
            image = original.copy()
        image.save(target, format="PNG", compress_level=1)
        self.assertNotEqual((REPO / render.SITE_ICON).read_bytes(), target.read_bytes())
        self.assertEqual([], render.check(REPO, self.root))

    def test_a_favicon_scaled_from_one_picture(self):
        target = self.root / render.SITE_FAVICON
        with Image.open(REPO / render.SITE_ICON) as icon:
            largest = icon.convert("RGBA").resize((64, 64))
        largest.save(target, format="ICO", sizes=[(s, s) for s in render.FAVICON_SIZES])
        self.assertDrift(render.SITE_FAVICON, "differs")

    def test_a_missing_file(self):
        (self.root / render.WORDMARK_MONO_SVG).unlink()
        self.assertDrift(render.WORDMARK_MONO_SVG, "missing")

    def test_a_hand_edit_inside_the_mark_row(self):
        target = self.root / render.SITE_INDEX
        text = target.read_text()
        self.assertIn('width="64" height="64"', text)
        target.write_text(text.replace('width="64" height="64"', 'width="48" height="48"'))
        self.assertDrift(render.SITE_INDEX, "differs")

    def test_a_sentence_of_the_page_is_not_this_script_s_business(self):
        target = self.root / render.SITE_INDEX
        text = target.read_text()
        self.assertIn("On 10 October 2026", text)
        target.write_text(text.replace("On 10 October 2026", "On 11 October 2026"))
        self.assertEqual([], render.check(REPO, self.root))

    def test_writing_touches_only_the_mark_row_of_the_page(self):
        target = self.root / render.SITE_INDEX
        before = target.read_text()
        edited = before.replace("On 10 October 2026", "On 11 October 2026").replace('width="64" height="64"', 'width="48" height="48"')
        target.write_text(edited)
        self.assertEqual([render.SITE_INDEX], render.write(REPO, self.root))
        self.assertEqual(before.replace("On 10 October 2026", "On 11 October 2026"), target.read_text())

    def test_writing_repairs_every_drawn_file(self):
        for path in (render.MARK_SVG, render.SITE_ICON, render.SITE_FAVICON, render.DASHBOARD_ICON):
            (self.root / path).write_bytes(b"not what the script draws")
        self.assertEqual(sorted([render.MARK_SVG, render.SITE_ICON, render.SITE_FAVICON, render.DASHBOARD_ICON]), render.write(REPO, self.root))
        self.assertEqual([], render.check(REPO, self.root))
        self.assertEqual([], render.write(REPO, self.root))

    def test_a_dashboard_mark_that_is_not_the_glyph(self):
        target = self.root / render.DASHBOARD_LAYOUT
        text = target.read_text()
        self.assertIn(render.read_glyph()["HULL"], text)
        target.write_text(text.replace(render.read_glyph()["HULL"], "M2,5h20v8h-20z"))
        self.assertDrift(render.DASHBOARD_LAYOUT, "HdGlyph.HULL")

    def test_a_token_that_is_not_the_palette_s(self):
        for path, old in ((render.SITE_INDEX, "--ember: #E8622A"), (render.DASHBOARD_CSS, "--gold: #7d5100")):
            with self.subTest(path):
                self.fresh()
                target = self.root / path
                text = target.read_text()
                self.assertIn(old, text)
                target.write_text(text.replace(old, old[:-6] + "FF6A1A"))
                self.assertDrift(path, "HdArgb")

    def test_a_hairline_that_is_not_the_ink_at_twelve_percent(self):
        target = self.root / render.DASHBOARD_CSS
        text = target.read_text()
        self.assertIn("--outline: rgb(239 234 224 / 0.12)", text)
        target.write_text(text.replace("--outline: rgb(239 234 224 / 0.12)", "--outline: rgb(239 234 224 / 0.2)"))
        self.assertDrift(render.DASHBOARD_CSS, "hairline")


class TheIdentityPage(unittest.TestCase):
    """site/index.html is the address the app gives a wallet as its identity."""

    @classmethod
    def setUpClass(cls):
        cls.html = (REPO / render.SITE_INDEX).read_text(encoding="utf-8")
        cls.head = cls.html[: cls.html.index("</head>")]

    def test_it_references_no_other_origin_for_anything_it_loads(self):
        self.assertEqual([], render.external_loads(self.html))
        self.assertEqual([], render.site_problems(self.html))
        # The same thing as a plain search: outside its links, the page names no URL at all.
        without_links = re.sub(r'<a href="https://[^"]+">', "<a>", self.html)
        self.assertEqual([], re.findall(r"https?://|(?<![:\w])//[\w.-]+\.\w+", without_links))

    def test_everything_it_loads_is_its_icon(self):
        loads = sorted(set(re.findall(r'(?:src|href)="([^"]+)"', re.sub(r"<a [^>]*>", "", self.html))))
        self.assertEqual(["favicon.ico", "icon.png"], loads)
        for name in loads:
            self.assertTrue((REPO / "site" / name).is_file(), name)
        self.assertNotIn("@import", self.html)
        self.assertNotIn("url(", self.html)
        self.assertNotIn("@font-face", self.html)
        self.assertNotIn("<script", self.html.lower())

    def test_the_scan_sees_a_load_when_there_is_one(self):
        cases = {
            '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=X">': ["https://fonts.googleapis.com/css2?family=X"],
            '<img alt="" src="//cdn.example.org/a.png">': ["//cdn.example.org/a.png"],
            "<script src='https://example.org/a.js'></script>": ["https://example.org/a.js"],
            "<style>body { background: url(https://example.org/a.png) }</style>": ["https://example.org/a.png"],
            '<style>@import "https://example.org/a.css";</style>': ["https://example.org/a.css"],
            '<img src="/icon.png" alt="">': ["/icon.png"],
            '<img src="data:image/png;base64,AAAA" alt="">': ["data:image/png;base64,AAAA"],
            # A link is not a load, and a file beside the page is not another origin.
            '<a href="https://ore.com">ORE</a>': [],
            '<img src="icon.png" alt=""><link rel="icon" href="favicon.ico">': [],
        }
        for html, expected in cases.items():
            self.assertEqual(expected, render.external_loads(html), html)
        self.assertIn("has a <script>", render.site_problems(self.html + "<script>1</script>"))
        self.assertIn("has a <form>", render.site_problems(self.html + '<form action="x"></form>'))

    def test_its_policy_lets_it_load_its_own_images_and_nothing_else(self):
        policy = re.search(r'<meta http-equiv="Content-Security-Policy" content="([^"]*)">', self.head)
        self.assertIsNotNone(policy)
        self.assertEqual("default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'", policy.group(1))
        # Before anything it governs: only the character set comes first.
        self.assertLess(self.head.index("Content-Security-Policy"), self.head.index("<title>"))
        self.assertLess(self.head.index("Content-Security-Policy"), self.head.index("<link"))
        self.assertLess(self.head.index("Content-Security-Policy"), self.head.index("<style>"))

    def test_its_tokens_are_the_palette_dark_first_and_light_when_asked(self):
        palette = render.read_palette()
        self.assertEqual([], render.token_problems(self.html, render.SITE_TOKENS, palette))
        self.assertEqual([], render.hairline_problems(self.html, "--hairline", palette))
        light = self.head.index("@media (prefers-color-scheme: light)")
        for variable, role in render.SITE_TOKENS.items():
            first, second = [m.start() for m in re.finditer(re.escape(variable) + ":", self.head)]
            self.assertLess(first, light, variable)
            self.assertGreater(second, light, variable)
        self.assertIn("color-scheme: dark light", self.head)
        self.assertIn('<meta name="color-scheme" content="dark light">', self.head)
        metas = re.findall(r'<meta name="theme-color" media="\(prefers-color-scheme: (dark|light)\)" content="(#[0-9A-F]{6})">', self.head)
        self.assertEqual([("dark", palette["dark"]["PIT"]), ("light", palette["light"]["PIT"])], metas)

    def test_no_colour_is_written_outside_the_tokens(self):
        style = self.head[self.head.index("<style>") : self.head.index("</style>")]
        rules = style[style.index("body {") :]
        self.assertEqual([], re.findall(r"#[0-9A-Fa-f]{3,8}\b|rgb\(", rules))
        self.assertNotIn("image-rendering", style)
        # The wordmark takes its two inks from the tokens, so it follows the palette.
        row = self.html[self.html.index(render.MARK_ROW_BEGIN) : self.html.index(render.MARK_ROW_END)]
        self.assertEqual([], re.findall(r'fill="|#[0-9A-Fa-f]{6}', row))
        self.assertIn(".wm-ink { fill: var(--chalk); }", style)
        self.assertIn(".wm-seam { fill: var(--seam); }", style)

    def test_a_link_is_ink_over_a_seam_line_and_the_tag_is_ash(self):
        style = self.head[self.head.index("<style>") : self.head.index("</style>")]
        link = re.search(r"\n  a \{([^}]*)\}", style).group(1)
        self.assertIn("color: var(--chalk)", link)
        self.assertIn("text-decoration-color: var(--seam)", link)
        self.assertIn("text-decoration-thickness: 2px", link)
        tag = re.search(r"\.tag \{([^}]*)\}", style).group(1)
        self.assertIn("color: var(--ash)", tag)
        self.assertIn("monospace", tag)
        self.assertIn("text-transform: uppercase", tag)
        self.assertNotIn("var(--ember)", style[style.index("body {") :])

    def test_the_mark_row_is_decoration_and_the_heading_says_the_name(self):
        row = self.html[self.html.index(render.MARK_ROW_BEGIN) : self.html.index(render.MARK_ROW_END)]
        self.assertIn('<img src="icon.png" width="64" height="64" alt="">', row)
        self.assertIn('aria-hidden="true"', row)
        self.assertLess(self.html.index(render.MARK_ROW_END), self.html.index("<h1>Heads Down"))


class TheIcon(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.glyph = render.read_glyph()
        cls.colors = render.read_colors()
        with Image.open(REPO / render.SITE_ICON) as icon:
            cls.icon = icon.copy()
        with Image.open(REPO / render.SITE_FAVICON) as ico:
            cls.favicon_format = ico.format
            cls.favicon = {size: ico.ico.getimage(size).convert("RGBA") for size in sorted(ico.ico.sizes())}

    def color(self, name):
        return render.rgb(self.colors[name])

    def test_it_is_512_square_and_opaque(self):
        self.assertEqual((512, 512), self.icon.size)
        self.assertEqual("RGB", self.icon.mode)

    def test_the_mark_sits_where_the_launcher_draws_it(self):
        pit, face, gold = self.color("hd_icon_background"), self.color("hd_icon_face"), self.color("hd_icon_seam")
        px = self.icon.load()
        # The face: (78,128) to (434,199), its top edge a lighter line.
        self.assertEqual(self.color("hd_icon_edge"), px[78, 128])
        self.assertEqual(face, px[78, 198])
        self.assertEqual(face, px[433, 136])
        for outside in ((77, 160), (434, 160), (256, 127)):
            self.assertEqual(pit, px[outside], outside)
        # The underside: from the face down to y 270, narrowing to x 128..384, shadow to gold.
        self.assertEqual(pit, px[256, 270])
        self.assertEqual(pit, px[126, 269])
        self.assertEqual(pit, px[386, 269])
        top, bottom = px[256, 199], px[256, 269]
        self.assertLess(sum(top), sum(bottom))
        self.assertLessEqual(max(abs(a - b) for a, b in zip(bottom, gold)), 2)
        self.assertLessEqual(max(abs(a - b) for a, b in zip(top, self.color("hd_icon_underside_shadow"))), 2)
        # The seam: (114,341) to (398,377).
        for inside in ((114, 341), (397, 376), (256, 359)):
            self.assertEqual(gold, px[inside], inside)
        for outside in ((113, 341), (398, 376), (256, 340), (256, 377)):
            self.assertEqual(pit, px[outside], outside)

    def test_it_is_symmetric_and_survives_a_circular_mask(self):
        pit = self.color("hd_icon_background")
        self.assertEqual(self.icon.tobytes(), self.icon.transpose(Image.FLIP_LEFT_RIGHT).tobytes())
        px = self.icon.load()
        farthest = max(math.hypot(x + 0.5 - 256, y + 0.5 - 256) for y in range(512) for x in range(512) if px[x, y] != pit)
        self.assertLess(farthest, 256 * 0.9)
        # The corners a mask cuts off are plain pit.
        for corner in ((0, 0), (511, 0), (0, 511), (511, 511)):
            self.assertEqual(pit, px[corner])

    def test_the_favicon_holds_four_sizes_each_drawn_on_its_own_grid(self):
        self.assertEqual("ICO", self.favicon_format)
        self.assertEqual([(s, s) for s in render.FAVICON_SIZES], sorted(self.favicon))
        pit, gold = self.color("hd_icon_background"), self.color("hd_icon_seam")
        for size in render.FAVICON_SIZES:
            frame = self.favicon[(size, size)]
            self.assertEqual((size, size), frame.size)
            self.assertEqual((255, 255), frame.getchannel("A").getextrema(), "opaque at %d" % size)
            g = render.icon_geometry(self.glyph, size)
            px = frame.convert("RGB").load()
            # Whole pixels: the seam is solid gold from edge to edge, with plain pit against it.
            for y in range(g["seam_top"], g["seam_bottom"]):
                for x in range(g["seam_left"], size - g["seam_left"]):
                    self.assertEqual(gold, px[x, y], (size, x, y))
            self.assertEqual(pit, px[g["seam_left"] - 1, g["seam_top"]])
            self.assertEqual(pit, px[g["seam_left"], g["seam_top"] - 1])
            self.assertEqual(pit, px[g["seam_left"], g["seam_bottom"]])
            # The face is lifted at 32 px and below, and the launcher's own shade above.
            face = render.rgb(render.SMALL_FACE) if size <= render.SMALL_MAX else self.color("hd_icon_face")
            self.assertEqual(face, px[size // 2, g["face_bottom"] - 1], size)
            self.assertEqual(face, px[g["face_left"], g["face_bottom"] - 1], size)
            self.assertEqual(pit, px[g["face_left"] - 1, g["face_bottom"] - 1], size)
            self.assertEqual(frame.tobytes(), frame.transpose(Image.FLIP_LEFT_RIGHT).tobytes())
            # Its own drawing: not the 512 px icon scaled down.
            scaled = self.icon.resize((size, size), Image.LANCZOS).convert("RGBA")
            self.assertNotEqual(scaled.tobytes(), frame.tobytes())

    def test_the_geometry_at_512_is_the_launcher_s_centre(self):
        self.assertEqual(
            {"face_left": 78, "face_top": 128, "face_bottom": 199, "edge_height": 7, "under_bottom": 270, "under_bottom_left": 128, "seam_left": 114, "seam_top": 341, "seam_bottom": 377},
            render.icon_geometry(self.glyph, 512),
        )


class TheVectors(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.glyph = render.read_glyph()
        cls.colors = render.read_colors()

    def read(self, path):
        return (REPO / path).read_text(encoding="utf-8")

    def test_the_marks_carry_the_design_module_s_paths_verbatim(self):
        mono = self.read(render.MARK_MONO_SVG)
        tab = self.read(render.DASHBOARD_ICON)
        for svg in (mono, tab):
            self.assertEqual([self.glyph["HULL"], self.glyph["SEAM"]], re.findall(r'<path d="([^"]+)"', svg))
            self.assertIn('viewBox="0 0 24 24"', svg)
        self.assertEqual({"currentColor"}, set(re.findall(r'fill="([^"]+)"', mono)))
        self.assertIn('<rect width="24" height="24" rx="3" fill="%s"/>' % self.colors["hd_pit"], tab)
        self.assertEqual([self.colors["hd_pit"], self.colors["hd_chalk"], self.colors["hd_seam"]], re.findall(r'fill="([^"]+)"', tab))

        mark = self.read(render.MARK_SVG)
        self.assertEqual(
            [self.glyph["LAUNCHER_FACE"], self.glyph["LAUNCHER_EDGE"], self.glyph["LAUNCHER_UNDERSIDE"], self.glyph["LAUNCHER_SEAM"]],
            re.findall(r'<path d="([^"]+)"', mark),
        )
        self.assertIn('viewBox="18 18 72 72"', mark)

    def test_the_dashboard_header_draws_the_same_two_paths(self):
        layout = self.read(render.DASHBOARD_LAYOUT)
        self.assertEqual([self.glyph["HULL"], self.glyph["SEAM"]], re.findall(r'<path className="brand-\w+" d="([^"]+)"', layout))
        self.assertNotIn("brand-dot", layout)
        css = self.read(render.DASHBOARD_CSS)
        self.assertNotIn("brand-dot", css)
        self.assertNotIn("@keyframes", css)
        self.assertNotIn("animation", css)
        self.assertNotIn("box-shadow: 0 6px", css)

    def test_the_wordmark_needs_no_font_and_comes_in_two_inks(self):
        colour = self.read(render.WORDMARK_SVG)
        mono = self.read(render.WORDMARK_MONO_SVG)
        for svg in (colour, mono):
            self.assertNotIn("<text", svg)
            self.assertNotIn("font", svg)
            self.assertIn("<title id=\"t\">Heads Down</title>", svg)
        self.assertEqual([self.colors["hd_chalk"], self.colors["hd_seam"]], re.findall(r'fill="([^"]+)"', colour))
        self.assertEqual(["currentColor"], re.findall(r'fill="([^"]+)"', mono))
        # The same outlines in both, and on the identity page.
        outlines = "".join(re.findall(r' d="([^"]+)"', colour))
        self.assertEqual(outlines, "".join(re.findall(r' d="([^"]+)"', mono)))
        row = self.read(render.SITE_INDEX)
        row = row[row.index(render.MARK_ROW_BEGIN) : row.index(render.MARK_ROW_END)]
        self.assertEqual(outlines, "".join(re.findall(r' d="([^"]+)"', row)))

    def test_the_wordmark_is_set_as_the_app_sets_it(self):
        mark = render.wordmark()
        # Nine letters: five over the line, four under it, each a closed outline or more.
        self.assertGreaterEqual(mark.heads.count("Z"), 5)
        self.assertGreaterEqual(mark.down.count("Z"), 4)
        # Capitals are 160 units tall; the line is 12 thick with 30 above and below it.
        line = [float(v) for v in re.match(r"M0 ([\d.]+)H([\d.]+)V([\d.]+)H0Z", mark.line).groups()]
        self.assertAlmostEqual(12.0, line[2] - line[0], places=3)
        self.assertAlmostEqual(mark.width, line[1], places=3)
        self.assertAlmostEqual(mark.height / 2, (line[0] + line[2]) / 2, delta=1.0)
        # Both words start at the left edge and neither runs past the line.
        for outline in (mark.heads, mark.down):
            xs = [float(x) for x in re.findall(r"[MLHQ ]\s*(-?[\d.]+)(?= -?[\d.]|[A-Z])", outline)]
            self.assertGreaterEqual(min(xs), -0.05)
        self.assertGreater(mark.width, 400)
        self.assertLess(mark.width, 440)

    def test_the_path_grammar_is_the_mark_s_and_no_more(self):
        self.assertEqual([(2, 5), (22, 5), (22, 9), (19, 13), (5, 13), (2, 9)], render.polygon(self.glyph["HULL"]))
        self.assertEqual([(4, 17), (20, 17), (20, 19), (4, 19)], render.polygon(self.glyph["SEAM"]))
        with self.assertRaises(SystemExit):
            render.polygon("M0,0c1,1 2,2 3,3z")
        with self.assertRaises(SystemExit):
            render.polygon("M0,0h4v4")


class TheDashboardFonts(unittest.TestCase):
    """IBM Plex has a Reserved Font Name: the files are IBM's own, byte for byte, with their licence."""

    FONTS = REPO / "dashboard/src/app/fonts"
    SHA256 = {
        "IBMPlexMono-Regular-Latin1.woff2": "e8993d946649b9d01abb1ed06d574b19d8ea3e66b5c3948602db335c44c18e56",
        "IBMPlexMono-Medium-Latin1.woff2": "41201b658a328b9d00368215c2f1102770f80b15952ab82631e4006255e6365d",
        "LICENSE.txt": "7e6b2818edbd8f6a01ae80641cc8f16a51080d08fb4e532be3a0b6f74adb07da",
    }

    def test_the_files_are_as_downloaded(self):
        import hashlib

        self.assertEqual(sorted(self.SHA256), sorted(p.name for p in self.FONTS.iterdir()))
        for name, expected in self.SHA256.items():
            self.assertEqual(expected, hashlib.sha256((self.FONTS / name).read_bytes()).hexdigest(), name)
        # The licence is the one the app ships for the same family.
        app = REPO / "android/core/design/licenses/IBMPlexMono-OFL.txt"
        self.assertEqual(app.read_bytes(), (self.FONTS / "LICENSE.txt").read_bytes())

    def test_the_style_sheet_loads_them_from_beside_itself_and_nothing_from_elsewhere(self):
        css = (REPO / render.DASHBOARD_CSS).read_text(encoding="utf-8")
        self.assertEqual(
            ["./fonts/IBMPlexMono-Regular-Latin1.woff2", "./fonts/IBMPlexMono-Medium-Latin1.woff2"],
            re.findall(r'url\("([^"]+)"\)', css),
        )
        self.assertEqual(["400", "500"], re.findall(r"@font-face \{[^}]*font-weight: (\d+);", css))
        self.assertNotIn("@import", css)
        self.assertNotIn("http", css)
        # The body stays the system's own face; the mono family is named in one variable.
        self.assertIn("font-family: var(--sans);", css[css.index("body {") : css.index("a {")])
        self.assertEqual(1, css.count('"IBM Plex Mono", ui-monospace'))


if __name__ == "__main__":
    unittest.main()
