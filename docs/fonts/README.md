# Brand fonts

Ondes uses two typefaces:

| Role | Typeface | Used for | Licence |
|---|---|---|---|
| Display | Space Grotesk | Titles, headlines, numbers, wordmark | [OFL-SpaceGrotesk.txt](OFL-SpaceGrotesk.txt) |
| UI | Manrope | Body text, labels, buttons | [OFL-Manrope.txt](OFL-Manrope.txt) |

Both fonts use the SIL Open Font License 1.1. No Reserved Font Name is declared.
Each font file also keeps its copyright and licence in its `name` table.

## Files

The app loads the fonts from `app/src/main/res/font/`. The Compose type scale is
in `app/src/main/java/ovh/battistella/ondes/ui/theme/Type.kt`.
`scripts/generate-store-assets.py` uses the same files for the feature graphic.

| File | Weight |
|---|---|
| `space_grotesk_regular.ttf` | 400 |
| `space_grotesk_medium.ttf` | 500 |
| `space_grotesk_bold.ttf` | 700 |
| `manrope_regular.ttf` | 400 |
| `manrope_medium.ttf` | 500 |

To keep the APK small, the files are static instances of the variable fonts,
cut down to Latin and Latin Extended-A, without hinting. Add a weight only when
the UI uses it.

## Regenerate

The source files are the variable TTFs from
[google/fonts](https://github.com/google/fonts): `ofl/spacegrotesk/SpaceGrotesk[wght].ttf`
and `ofl/manrope/Manrope[wght].ttf`. Install
[fonttools](https://github.com/fonttools/fonttools), then run this for each file
and weight:

```sh
fonttools varLib.instancer "SpaceGrotesk[wght].ttf" wght=700 --update-name-table -o tmp.ttf
pyftsubset tmp.ttf \
  --unicodes="U+0000-00FF,U+0100-017F,U+0192,U+0218-021B,U+02C6-02DD,U+2000-206F,U+20AC,U+2122,U+2190-2199,U+2212,U+FEFF,U+FFFD" \
  --layout-features=kern,liga,calt,locl,ccmp,mark,mkmk,tnum,lnum,case,frac,numr,dnom \
  --name-IDs='*' --notdef-outline --no-hinting --desubroutinize \
  --output-file=app/src/main/res/font/space_grotesk_bold.ttf
```

Keep the `tnum` feature: the player shows durations with tabular figures.
