# Google Play's store listing

What Play Console → Grow → Store presence → Main store listing asks for, kept here so it is reviewed and versioned
like the rest. One folder per language, in fastlane's layout (`fastlane supply` can upload it later):

| File | Play Console field | Limit |
|------|--------------------|-------|
| `title.txt` | App name | 30 |
| `short_description.txt` | Short description | 80 |
| `full_description.txt` | Full description | 4,000 |
| `changelogs/<versionCode>.txt` | Release notes ("What's new") of that upload | 500 |

`en-US` is the default language; `fr-FR` is added under "Manage translations". `ht` (Kreyòl) is ready for when Play
Console offers Haitian Creole in its list; until then Play shows Kreyòl speakers the French or English listing.

```bash
python3 tools/store/play.py check                       # every text within the limits (CI runs it)
python3 tools/store/play.py crop ~/Pictures/Screenshot*.png   # screenshots trimmed to Play's 2:1, as *-play.png
```

The graphics: the icon `branding/play-store-icon.png` (512 x 512) and the feature graphic
`branding/play-feature-graphic.png` (1024 x 500), both drawn by `branding/generate.py`. The screenshots (two to eight,
phone, portrait) are in `<language>/images/phoneScreenshots/`, in the listing's order (fastlane sorts by name); Play
shows a language without its own the default language's. They are taken in a demo account filled by
`tools/demo/seed_demo.py` (its time zone chosen so today's todos are still ahead), on a phone or on the emulator
(`adb shell wm size 1080x1920`, `wm density 420`, the status bar's demo mode for a clean clock), then passed through
`play.py crop`: within 2:1 and 24-bit PNG without transparency, as Play wants. Retake them when a screen in them
changes.

The texts state what the app does today; change them with the app (and the website, `site/texts.py`, says the same
things). The price and the trial follow OWNER-CHECKLIST → Subscriptions.
