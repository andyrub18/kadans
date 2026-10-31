# The website

`https://kadansplanning.com`: what Kadans is, the downloads, support, account deletion, and the privacy policy and
terms the app's paywall and the stores link to. Plain files in `public/`, served by the nginx in front of the API
(docs/DEPLOYMENT.md → The website). No server code, no cookies, no third party: the pages load their own style,
mark and (on the download page) one small script, nothing else (the Content-Security-Policy says so).

`public/` is generated: never edit it. Change one of these and run the generator from the repository root:

| File | What it holds |
|------|---------------|
| `texts.py` | Every page's words, in English, French and Kreyòl, and the addresses (Google Play, the releases, support) |
| `legal/privacy.<lang>.html`, `legal/terms.<lang>.html` | The privacy policy and the terms, one per language |
| `assets/style.css`, `assets/download.js` | The look, and the script that puts the visitor's download first |
| `generate.py` | The page layouts |

```bash
python3 site/generate.py           # writes site/public; commit it with the change
python3 site/generate.py --check   # what CI runs: fails when public/ is not what the generator makes
```

The mark comes from `branding/` (drawn by `branding/generate.py`). Screen and button names in the texts are the app's
own: keep them in step with `clients/app/shared/src/commonMain/kotlin/app/kadans/i18n`. The privacy policy states
what ARCHITECTURE → Data retention says: change both together.

Addresses: `/en/…`, `/fr/…`, `/ht/…`; one without a language (`/`, the app's `/privacy` and `/terms`) goes to the
visitor's. The download buttons point at `/files/<name>` and the desktop app's update check at
`/desktop/latest.json`; nginx sends both to the latest GitHub release, whose files keep the same names.
