# The mark and the wordmark

A dark slab seen from a low angle, hovering over a thin line of light. As a silhouette, a bar over
a line.

| File | What it is | Use it |
|---|---|---|
| `mark.svg` | The mark in colour on its pit tile: the centre of the app's launcher icon. | Wherever the app is shown as an icon. It is the vector twin of `site/icon.png`. |
| `mark-mono.svg` | The silhouette, in `currentColor`. | Small and one-colour: beside text, in a status bar, as a stamp. |
| `wordmark.svg` | HEADS over DOWN with the seam line between them, chalk over gold. | On the pit, a slab or any dark surface. |
| `wordmark-mono.svg` | The same outlines in `currentColor`. | On paper or a light surface. Set the colour of the element that holds it. |

The letters are outlines of Big Shoulders Display at weight 800 (SIL Open Font License 1.1,
`android/core/design/licenses/BigShouldersDisplay-OFL.txt`), so no font is needed to show them.

## These files are drawn, not edited

`scripts/brand/render.py` writes every file here, and with them `site/icon.png`,
`site/favicon.ico`, `dashboard/src/app/icon.svg` and the mark row of `site/index.html`, from the
constants of the Android design module (`android/core/design`: `HdGlyph`, `colors.xml`, the display
font). To change the mark, change it there and run the script.

```sh
python3 scripts/brand/render.py                 # write every file
python3 scripts/brand/render.py --check         # exit 1 if a committed file differs
python3 -m unittest discover -s scripts/brand   # the tests of the script and of the identity page
```

It needs Python 3.9 or later with Pillow 11 and fontTools 4.60. Nothing runs it automatically:
run `--check` before a change to the design module's mark, palette or display font is merged.

## Rules the files follow

- Gold is the line of light and the word under it. It never fills a shape that holds text.
- On a light surface use the one-colour files: the gold light is 1.6 to 1 on the pale background.
- No shadow, no glow, no gradient but the one under the slab, and nothing animates.
