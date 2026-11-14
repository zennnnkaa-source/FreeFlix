# FairyStream

Android app for browsing and streaming series from fairyanime.net. Live scrape, auto-resolved streams, quality selection, and an ExoPlayer buffer window (−15s / +20s).

## Features

- Netflix-style home: featured carousel, continue watching, favorites, new arrivals, full grid
- Search by title, works with Thai text
- Cover art for every series (site image, og:image fallback, generated poster when neither exists)
- Favorites and watch history saved on device
- Auto-next episode, quality picker, landscape fullscreen playback
- Pauses playback when the app is stopped

## Open in Android Studio / Code Assist

1. Unzip the project
2. Open the `FairyStream` folder
3. Let Gradle sync (needs network once)
4. Run on a device or emulator (API 26+)

The Gradle wrapper jar is not bundled. Run `gradle wrapper --gradle-version 8.9` once locally if you want `./gradlew`.

## Build and release

Every push to `main` builds a release APK in GitHub Actions. On success the workflow:

1. Bumps `versionName` patch and `versionCode` in `version.properties`
2. Commits the bump with `[skip ci]`
3. Creates a GitHub Release `vX.Y.Z` with the APK attached

To bump minor or major manually, edit `version.properties` and push.

### Signing (recommended)

Without signing secrets the APK is signed with the runner's debug key. That key can change between runs, so updates may fail to install over an older build. Add these repository secrets to keep a stable signature:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Create a keystore once with `keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias fairystream`.

If `main` is protected, allow `github-actions[bot]` to push or the version commit step will fail.

## Notes

- Requires INTERNET
- Cloudflare or HTML changes may need selector updates in `FairyApi.kt`
- Streams are resolved live from the site, nothing is bundled
