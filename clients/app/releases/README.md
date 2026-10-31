# Release notes for the desktop app

One optional file per version, `<version>.json`, read when the tag `v<version>` is pushed (docs/DEPLOYMENT.md →
Releasing the desktop app). Its content goes into `latest.json`, which every installed desktop app reads once a day:

```json
{
  "minimumVersion": "1.0.0",
  "notes": {
    "en": "Faster reminders, and two-factor by QR code.",
    "fr": "Des rappels plus rapides, et la double authentification par code QR.",
    "ht": "Rapèl pi rapid, ak otantifikasyon de etap ak kòd QR."
  }
}
```

- `notes`: what is new, one or two sentences, English at least, French and Kreyòl when you have them (the app falls
  back to English). The notification and Settings → This computer show them.
- `minimumVersion` (rare): the oldest version that still works with the server. Raise it only with a release whose
  server change older apps cannot follow; those apps then ask to update at every start. Never above the release.

No file: the release is announced with its version alone. The release workflow stops on a file it cannot read, so a
typo never reaches the apps.
