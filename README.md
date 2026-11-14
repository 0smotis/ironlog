# IronLog

Personal workout tracker (a STRONG-style app with more customization and incentives), packaged as an Android app.

## How updates reach the phone

| What changed | How it gets to the phone | Taps |
|---|---|---|
| The page — `www/index.html` (features, screens, exercises, sounds) | The app checks `www/version.json` through GitHub's API (private repo, using the read-only token saved in IronLog → Settings → App updates), downloads the new page in the background and switches to it the next time the app is in the background. A version that fails to start is rolled back. | None |
| The Android app — `android/`, `package.json`, `capacitor.config.json` | GitHub Actions builds a signed APK and publishes it as a release; Obtainium offers the update. | ~3 |

After editing `www/index.html`, run `node tools/stamp-version.mjs "what changed"` so phones see the new version, then commit and push.

## One-time setup

1. Repo secrets (Settings → Secrets and variables → Actions): `IRONLOG_KEYSTORE_BASE64` and `IRONLOG_KEYSTORE_PASSWORD`. Keep the same key forever — a different key means Android refuses to update the app in place.
2. A fine-grained GitHub token with **Contents: Read-only** on this repo only (GitHub → Settings → Developer settings → Fine-grained tokens). It goes in two places on the phone, never in the repo:
   - IronLog → Settings → App updates → GitHub access token
   - Obtainium → Settings → GitHub personal access token
3. On the phone: install [Obtainium](https://github.com/ImranR98/Obtainium), Add App → `https://github.com/0smotis/ironlog`.
4. When the token expires, make a new one and paste it in both places. Until then updates pause; the app keeps working.

## Layout

- `www/` — the IronLog page (single file) and its `version.json`
- `android/` — Capacitor Android shell; native code in `android/app/src/main/java/com/ironlog/app`
  (lock-screen workout notification, rest alarm, reminders, live updater)
- `tools/stamp-version.mjs` — bumps the page version
- `.github/workflows/android.yml` — APK build + release
