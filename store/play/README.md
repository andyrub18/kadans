# Google Play's store listing

What Play Console → Grow → Store presence → Main store listing asks for, kept here so it is reviewed and versioned
like the rest. One folder per language, in fastlane's layout (`fastlane supply` can upload it later):

| File | Play Console field | Limit |
|------|--------------------|-------|
| `title.txt` | App name | 30 |
| `short_description.txt` | Short description | 80 |
| `full_description.txt` | Full description | 4,000 |
| `changelogs/<versionCode>.txt` | Release notes ("What's new") of that upload | 500 |

`en-US` is the default language; `fr-FR` and `es-419` (Spanish as Latin America writes it, Play's "Spanish (Latin
America)") are added under "Manage translations". `ht` (Kreyòl) is ready for when Play Console offers Haitian Creole
in its list (it did not on 2026-11-25); until then Play shows Kreyòl speakers the French or English listing, so `ht`
has its texts and no screenshots. `es-419` has no changelog of the uploads made before the app spoke Spanish.

```bash
python3 tools/store/play.py check                       # every text within the limits (CI runs it)
python3 tools/store/play.py crop ~/Pictures/Screenshot*.png   # screenshots trimmed to Play's 2:1, as *-play.png
```

The graphics: the icon `branding/play-store-icon.png` (512 x 512) and the feature graphic
`branding/play-feature-graphic.png` (1024 x 500), both drawn by `branding/generate.py`. The screenshots (two to eight,
phone, portrait) are in `<language>/images/phoneScreenshots/`, in the listing's order (fastlane sorts by name), one
set for each language Play lists: English, French, Spanish. They are taken in a demo account filled by
`tools/demo/seed_demo.py` (English and Spanish on a 12-hour clock, French on a 24-hour one), on the emulator
(`adb shell wm size 1080x1920`, `wm density 420`, the status bar's demo mode for a clean clock), then passed through
`play.py crop`: within 2:1 and 24-bit PNG without transparency, as Play wants. Retake them when a screen in them
changes.

The eight, as of 1.3 (2026-11-25, the new look): Home, the widget on a home screen, a reminder ringing with its
buttons, the question "How should reminders reach you?" (like an alarm, like a notification), a focus session, the
calendar, the budget month, its limits with the recurring salary and "Import a statement". The todo's own page left
the set to make room for the sounds; the Microsoft Store's set has it. How they are made on the emulator:

- **The day.** Home wants today's todos still ahead, and the script puts the first one 45 minutes from now. Taken in
  the evening, the account and the phone are given a time zone where the day is starting (`seed_demo.py --time-zone
  Asia/Dhaka`, the emulator started with `-timezone Asia/Dhaka`): nothing in the eight shows the zone. An account is
  made and filled just before its pictures, one language at a time, each on an app wiped with `pm clear` (the
  question about sounds is asked once per phone). One of the day's todos is marked done through the API, so the
  ring on Home shows progress.
- **The focus session** is six minutes in when it is taken (a quarter of the ring): started first, the calendar and
  the budget are taken while it runs, then the todo's "Open focus session" leads back to it.
- **The question about sounds** comes by itself the first time Home is seen again after a todo was opened.
- **The widget** is placed from Settings → "Add to my home screen", then made taller by its handles (a long press on
  it) so that it lists the day and tomorrow. It stays on the launcher from one language to the next and takes the
  account's language.
- **The reminder** is a one-time todo with a cycle created a minute or two ahead through the API while the app is on
  Home, caught a second after it rings, as Android shows it over the app. The word beside its title ("now") is
  Android's own: the emulator's system language is set to the listing's (`settings put system system_locales
  fr-FR`, `es-US` for Spanish, then a restart), and the status bar's demo clock to the todo's time.

The texts state what the app does today; change them with the app (and the website, `site/texts.py`, says the same
things). The short description names no currency: "a budget in your currencies", as the website does, since naming
two of the six told the readers of the four others that the app was not theirs; the full description lists them. The price and the trial follow OWNER-CHECKLIST → Subscriptions.
