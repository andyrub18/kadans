# The Microsoft Store's listing

What Partner Center → Kadans → a submission → Store listings asks for, per language: English, French and Spanish
(Latin America's, `es-419`, as on Google Play), the three languages the package declares
(`clients/app/desktopApp/msix/AppxManifest.xml`; Spanish from the first package built after 2026-11-25, which is
1.3.0's). The app speaks Kreyòl too, and the Store has no listing for it: `ht` is not in the Store's list of
languages, and a package that names a code outside that list can fail certification (read on 2026-11-25:
learn.microsoft.com, "App package requirements for MSIX app" → Supported languages). So the manifest leaves it out,
and `tools/release/test_msix.py` holds the three.

- **Description, What's new, Product features**: the Play texts fit (`store/play/<language>/full_description.txt`, the
  changelog, the short description), since both stores sell the same app; Spanish's are in `store/play/es-419/`.
- **Screenshots** (Desktop): `<language>/screenshots/`, in the listing's order: Home, Home in dark mode, the calendar, a
  todo with its focus cycle, a focus session, the budget month in dark mode, Settings in dark mode (the four languages,
  12 or 24 hours, appearance, the time zone, sounds). 2732 x 1536 (a 1366 x 768 window drawn at twice the scale, so
  sharp on a large screen; the Store asks for 1366 x 768 at least), 24-bit PNG.
- **Store logos → 1:1 App tile icon**: `app-tile-icon.png` (300 x 300, the same in both languages), drawn by
  `branding/generate.py`. The Store shows it before the package's own StoreLogo. The 2:3 poster and 1:1 box art are
  for games (and Xbox), and Kadans is neither: leave them empty.

The screenshots show the desktop app as it is (retaken 2026-11-25 with the new look), in a demo account filled by
`tools/demo/seed_demo.py` (English and Spanish on a 12-hour clock, French on a 24-hour one), one of the day's todos
marked done so that Home's ring shows progress, the focus session six minutes in: the window's content alone, no
system frame, which is what the Windows app shows too. They were drawn offscreen by Compose (`ImageComposeScene`, the
real `App()` against a scratch server), so no desktop window or system theme was involved: retake them the same way
when a screen in them changes. The app takes the computer's time zone, and the account follows it: taken in the
evening, the Java process is given a zone where the day is starting (`-Duser.timezone=Asia/Dhaka`, the account
seeded with the same), so that today's todos are still ahead; Settings, the one screen that names the zone, is drawn
by a second run in the zone it should show.
