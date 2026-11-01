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
phone, portrait): taken on a phone in a demo account filled by `tools/demo/seed_demo.py`, in each language, then
cropped with `play.py crop`. Suggested screens: Home with today's todos, a todo with its rhythm and reminders, a focus
session, the budget month, a reminder notification.

The texts state what the app does today; change them with the app (and the website, `site/texts.py`, says the same
things). The price and the trial follow OWNER-CHECKLIST → Subscriptions.
