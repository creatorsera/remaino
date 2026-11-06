# Keyboard

A private, fast, dark-by-default Android keyboard with a rounded top toolbar. Open source.

> Working name. Rename it later (see "Rename the app").

## What works in v0.1
- QWERTY typing, shift (double-tap for caps lock), symbols page, backspace with hold-to-repeat, space, double-space for a period, auto-capitalization
- Dark theme (shades of black), rounded top toolbar, round inverted Enter bubble
- One toolbar row: grid icon, three suggestions, mic icon. Tap the grid icon to swap suggestions for icons
- Word suggestions from a small built-in list plus the words you use most (counts only)
- Private fields (passwords): no suggestions, no learning, no counting
- Setup screen: enable keyboard, choose keyboard, try box, typing stats (words today, since install, and books)

## Not built yet (icons show "Coming soon")
Emoji, clipboard manager, voice typing, themes, Urdu layout, swipe typing, autocorrect, password-manager autofill chips, encrypted export/import. GIFs are planned after v1.

## Privacy
See PRIVACY.md. No internet permission, no analytics, no accounts.

## Push to GitHub
```
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/YOUR-NAME/keyboard.git
git push -u origin main
```
GitHub Actions then builds the app on every push. Open the run under the Actions tab and download the `keyboard-debug-apk` file to install it on your phone.

Add a license: on GitHub choose Add file > Create new file, name it `LICENSE`, and pick the Apache-2.0 template.

## Build locally
Open the folder in Android Studio (JDK 17), or run `gradle assembleDebug`.
To create the Gradle wrapper once: `gradle wrapper --gradle-version 8.10.2` and commit the files it creates.

## Release a signed APK
1. Create a signing key (once, on a computer):
   `keytool -genkeypair -v -keystore release.jks -alias keyboard -keyalg RSA -keysize 4096 -validity 10000`
2. Back up `release.jks` and its passwords in a password manager. If you lose them you cannot ship updates that install over old versions. Never commit the file.
3. Turn it into text: `base64 -w0 release.jks` (macOS: `base64 -i release.jks`)
4. In the repo: Settings > Secrets and variables > Actions, add these secrets:
   `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` (keyboard), `KEY_PASSWORD`
5. Release:
   ```
   git tag v0.1.0
   git push origin v0.1.0
   ```
   The Release workflow attaches `keyboard-v0.1.0.apk` and a checksum to a GitHub Release.

## Rename the app
- Change `namespace` and `applicationId` in `app/build.gradle.kts`
- Rename the folder `app/src/main/java/com/example/keyboard` and the `package` lines in each Kotlin file to match
- Update `android:settingsActivity` in `app/src/main/res/xml/method.xml`
- Change `app_name` in `app/src/main/res/values/strings.xml`

## Project layout
| File | Purpose |
|------|---------|
| `KeyboardService.kt` | The input method: handles typing, fields, suggestions |
| `KeyboardView.kt` | Draws the toolbar and keys, handles touch |
| `Layouts.kt`, `Theme.kt` | Key layouts and the dark theme |
| `SuggestionEngine.kt` | Prefix suggestions |
| `TypingStats.kt` | Word counts and book stats (numbers only) |
| `SettingsActivity.kt` | Setup and stats screen |
| `.github/workflows/` | CI build and release |

## Notes
- This first version was written without being compiled. If the first GitHub Actions run fails, open the failed step and share the error text to get it fixed.
- Action versions in the workflows may have newer majors; Dependabot will open update PRs.
