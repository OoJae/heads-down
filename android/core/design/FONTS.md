# Fonts in `core/design`

| File in `src/main/res/font/` | What | Source | License |
|---|---|---|---|
| `big_shoulders_display_extrabold.ttf` | Big Shoulders Display, a static weight-800 instance, subset to Latin: 129 glyphs, 18,380 bytes. A **modified** copy (see below). | [`ofl/bigshouldersdisplay/BigShouldersDisplay[wght].ttf`](https://raw.githubusercontent.com/google/fonts/main/ofl/bigshouldersdisplay/BigShouldersDisplay%5Bwght%5D.ttf) in google/fonts, version 2.002 | SIL Open Font License 1.1, [`licenses/BigShouldersDisplay-OFL.txt`](licenses/BigShouldersDisplay-OFL.txt). No Reserved Font Name. |
| `ibm_plex_mono_regular.ttf` | IBM Plex Mono Regular (400), version 2.3: 135,580 bytes. **Unmodified**, renamed on disk only. | [`ofl/ibmplexmono/IBMPlexMono-Regular.ttf`](https://raw.githubusercontent.com/google/fonts/main/ofl/ibmplexmono/IBMPlexMono-Regular.ttf) in google/fonts | SIL Open Font License 1.1, [`licenses/IBMPlexMono-OFL.txt`](licenses/IBMPlexMono-OFL.txt). Reserved Font Name "Plex". |
| `ibm_plex_mono_medium.ttf` | IBM Plex Mono Medium (500), version 2.3: 136,704 bytes. **Unmodified**, renamed on disk only. | [`ofl/ibmplexmono/IBMPlexMono-Medium.ttf`](https://raw.githubusercontent.com/google/fonts/main/ofl/ibmplexmono/IBMPlexMono-Medium.ttf) in google/fonts | The same. |

`res/font` accepts only font and XML files, so the two licence texts are in [`licenses/`](licenses/),
byte for byte as downloaded. Each font file also carries its copyright and licence in its own `name`
table (IDs 0, 13 and 14), which is why the subset below keeps every name record.

The fonts are named in code in one place: `HdFonts` in
[`HdType.kt`](src/main/kotlin/xyz/headsdown/core/design/HdType.kt).

## Hashes (sha256)

Downloaded on 2026-10-10 from `https://raw.githubusercontent.com/google/fonts/main/ofl/`:

| Download | sha256 |
|---|---|
| `bigshouldersdisplay/BigShouldersDisplay[wght].ttf` | `60e208dc276a1c35fc5b62e94f9fb959c40c11783a9eb7548175c14b1fbeb720` |
| `bigshouldersdisplay/OFL.txt` | `338f9c050f19daeda1d597243faf79f3a3d437c338af58cb7047617d0ce08771` |
| `ibmplexmono/IBMPlexMono-Regular.ttf` | `6a3412f058c7d8dfd9170c41e85ade48e5156ecb89356110ca57a0a27734af46` |
| `ibmplexmono/IBMPlexMono-Medium.ttf` | `a9b4c49bb299e05b5f6c481e7fb5e78943d2793249a0c8874ab574a2d1ea6755` |
| `ibmplexmono/OFL.txt` | `7e6b2818edbd8f6a01ae80641cc8f16a51080d08fb4e532be3a0b6f74adb07da` |

Shipped:

| File | sha256 |
|---|---|
| `src/main/res/font/big_shoulders_display_extrabold.ttf` | `de2f74231c6190d4f2e95c29d555555018bda93bdcb2ef2413167cbea2de121e` |
| `src/main/res/font/ibm_plex_mono_regular.ttf` | `6a3412f058c7d8dfd9170c41e85ade48e5156ecb89356110ca57a0a27734af46` |
| `src/main/res/font/ibm_plex_mono_medium.ttf` | `a9b4c49bb299e05b5f6c481e7fb5e78943d2793249a0c8874ab574a2d1ea6755` |
| `licenses/BigShouldersDisplay-OFL.txt` | `338f9c050f19daeda1d597243faf79f3a3d437c338af58cb7047617d0ce08771` |
| `licenses/IBMPlexMono-OFL.txt` | `7e6b2818edbd8f6a01ae80641cc8f16a51080d08fb4e532be3a0b6f74adb07da` |

`FontAssetsTest` hashes the five shipped files on every unit-test run and fails if one differs from
this table, or if a Plex file differs from its download: a subset, a rename of its internal names or a
format conversion of a font with a Reserved Font Name would need another name for the result.

## Provenance of the display face

The upstream file is a variable font whose default instance is Thin (weight 100). It is not shipped:
any code path that dropped the variation settings would draw hairline text. Instead one static
instance is cut at weight 800 and subset to the characters the display face is used for.

With fontTools 4.60.2 (`pip install fonttools==4.60.2`), in the directory of the download:

```sh
export SOURCE_DATE_EPOCH=1791590400   # 2026-10-10T00:00:00Z: fontTools stamps head.modified with it

python3 -m fontTools.varLib.instancer "BigShouldersDisplay[wght].ttf" wght=800 -o bsd-800.ttf

python3 -m fontTools.subset bsd-800.ttf \
  --unicodes="U+0020-007E,U+00A0,U+00B7,U+2013,U+2014,U+2018,U+2019,U+201C,U+201D,U+2026,U+2212" \
  --layout-features="kern,liga,case,ccmp,locl,mark,mkmk" \
  --name-IDs="*" --notdef-outline --no-hinting \
  --output-file=big_shoulders_display_extrabold.ttf
```

With `SOURCE_DATE_EPOCH` set as above the two commands are reproducible: they were run twice and gave
the same bytes (`bsd-800.ttf` is `ba52ec5a677fcba934ef16285ac9349af4adf2a9db89718d792622611f5fecdb`,
the subset is the hash in the table). Without it the result differs only in the `head.modified`
timestamp.

What the result is, and is not:

- `OS/2.usWeightClass` is 800, which is what Android reads; it is loaded as
  `Font(R.font.big_shoulders_display_extrabold, FontWeight.ExtraBold)`. The `name` records were kept
  as they were (`--name-IDs="*"`, and the instancer was not asked to rewrite them), so the family and
  style names inside the file still say "Big Shoulders Display Thin". Nothing on Android reads them.
- The characters: Basic Latin (U+0020 to U+007E), the no-break space, the middle dot, the en and em
  dashes, the curly quotes, the ellipsis and the minus sign. Anything else in a string drawn in the
  display face (an arrow, an accented letter) falls back to the platform font.
- Kerning (`kern`), ligatures (`liga`), case-sensitive forms (`case`) and localized forms (`locl`)
  were kept; the font has no `ccmp`, `mark` or `mkmk` rules left for these characters. Hinting was
  dropped: the face is used at 30sp and above.
- **No tabular figures.** A digit is about 0.47em wide and "1" about 0.26em, so a number changes
  width as it changes: numerals are left-aligned and never counted up frame by frame.
- **No check mark** (U+2713 is not in the family at all). A tick is drawn as a vector.

## Provenance of the mono face

`IBMPlexMono-Regular.ttf` and `IBMPlexMono-Medium.ttf` are copied as downloaded and given the
lower-case file names `res/font` requires. IBM Plex declares the Reserved Font Name "Plex", so the
files are not subset, not renamed internally and not converted to another format; the cost is 272,284 bytes
of font data before compression.

## If a font cannot be loaded

`HdFonts` is the only place that names the files. The stand-ins, should a face ever have to be
dropped, are `Font(DeviceFontFamilyName("sans-serif-condensed"), FontWeight.Bold)` for the display
face and `FontFamily.Monospace` for the mono face.
