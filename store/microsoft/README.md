# The Microsoft Store's listing

What Partner Center → Kadans → a submission → Store listings asks for, per language (English, and French: the two
languages the package declares, `clients/app/desktopApp/msix/AppxManifest.xml`):

- **Description, What's new, Product features**: the Play texts fit (`store/play/<language>/full_description.txt`, the
  changelog, the short description), since both stores sell the same app.
- **Screenshots** (Desktop): `<language>/screenshots/`, in the listing's order: Home, Home in dark mode, the calendar, a
  todo with its focus cycle, a focus session, the budget month in dark mode, Settings in dark mode (three languages, 12 or
  24 hours, appearance). 2732 x 1536 (a 1366 x 768 window drawn at twice the scale, so sharp on a large screen; the
  Store asks for 1366 x 768 at least), 24-bit PNG.
- **Store logos → 1:1 App tile icon**: `app-tile-icon.png` (300 x 300, the same in both languages), drawn by
  `branding/generate.py`. The Store shows it before the package's own StoreLogo. The 2:3 poster and 1:1 box art are
  for games (and Xbox), and Kadans is neither: leave them empty.

The screenshots show the desktop app as it is, in a demo account filled by `tools/demo/seed_demo.py` (English on a 12-hour clock,
French on a 24-hour one): the window's content alone, no system frame, which is what the Windows app shows too. They
were drawn offscreen by Compose (`ImageComposeScene`, the real `App()` against a scratch server), so no desktop window
or system theme was involved: retake them the same way when a screen in them changes.
