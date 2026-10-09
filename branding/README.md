# The Kadans mark

A clock dial whose hands spell **K**. Two hands make the stem, pointing to 12 and 6; two make the arms, pointing to
1 and 5 o'clock, all from the centre, which is where a K's arms meet its stem. A clock has three hands; the fourth
is symbolic (travel watches with a second time zone have four). The twelve evenly spaced hour marks are the cadence
in Kadans: a rhythm for your time and your money.

Chosen by the owner on 2026-10-09, among five candidates (ring, watch, dial, disc, coin): the dial, the hands on the
clock's true centre, and the "Wind" animation for the splash.

## Files

Everything is drawn by `generate.py` from one geometry (a 100 x 100 square centred on 50, 50). Never edit a
generated file; change the numbers at the top of the script and run it again from the repository root:

```bash
python3 branding/generate.py      # needs ImageMagick (`convert`) for the PNG and ICO files
```

The app also draws the mark in Compose (`clients/app/shared/src/commonMain/kotlin/app/kadans/ui/brand/KadansMark.kt`),
for the desktop's window, tray and splash: keep its numbers in step with the script's.

| File | What it is |
|------|------------|
| `kadans-mark.svg` | The mark, in the app's purple, on nothing |
| `kadans-mark-white.svg` | The same in white, for purple or dark grounds |
| `kadans-mark-small.svg` | The small mark: no hour marks, bolder strokes, for 24 px and under |
| `kadans-icon.svg` | The app icon: the white dial on a purple disc |
| `play-store-icon.png` | 512 x 512 for Play Console (a full square: Google applies its own mask) |
| Android `res/` (generated) | Adaptive launcher icon with Android 13's themed version, PNG icons for Android 7, the splash's animated icon (Android 12+) and its still mark (before), the notification icon (in `shared`) |
| `clients/app/desktopApp/icons/` | The packaged desktop app's icon (`.png` for Linux, `.ico` for Windows) |

Not yet: the macOS `.icns` (made on a Mac, with the DMG) and the iPhone app's icons (with the iPhone app).

## Rules

- **Colour**: the app's purple, `#6750A4` (Material 3 baseline primary), and white. On purple or dark, the mark is
  white; on light, purple. The app's dark theme uses its own lighter purple, `#D0BCFF`.
- **Sizes**: the full dial from 32 px up; the small mark below that (the notification and the tray).
- **Space**: keep a tenth of the mark's width clear around it.
- **Never**: redraw it by hand, stretch it, rotate it, or move the hands. The K is the hands' only position at rest.

## The splash: "Wind"

Every hand starts at 12 and winds forward at its own speed (the upper arm twice round, the others once) before
settling into the K: time running, then the name. One second, eased with `cubic-bezier(0.2, 0.78, 0.25, 1)`.
Android 12 and later play it in the system splash (`avd_splash.xml`, held until it has finished, and skipped when
animations are off); the desktop plays the same in the app as it opens (`KadansSplash`). Before Android 12 the splash
is the finished mark on purple.
