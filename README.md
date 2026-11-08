# Remaino

A private, fast, dark-by-default Android keyboard with a rounded top toolbar. Open source.

## What works in v0.1
- Dark theme (shades of black) with a rounded top toolbar: grid icon, three suggestions, mic icon. Tap the grid icon to swap suggestions for icons
- Wide, short keys in a four-row layout: QWERTY, shift (double-tap for caps lock), backspace with hold-to-repeat, symbols page, round `?123` pill, emoji-over-comma key, globe, space bar, period and a light round Enter bubble
- Options in the app (all off by default except the mascot): number row, key preview bubble, mascot
- Mascot: while you delete, the toolbar turns into a lane where a small friend pushes a blinking cursor back
- Emoji sheet: one long scrolling sheet (Recent first, then Smileys, People, and so on) with icon tabs that jump to each section. Open it with the smile icon in the tray or by holding the emoji-over-comma key
- Clipboard manager: copied text is saved encrypted (Android Keystore) with pinning, delete, pause and clear. Sensitive copies and private fields are skipped. Items expire after a time you choose (pinned items never do)
- Word suggestions from a small built-in list plus the words you use most (counts only)
- Private fields (passwords): no suggestions, no learning, no counting, no clipboard saving
- App screen: setup steps, try box, options, clipboard settings, typing stats and encrypted backup
- Backup: password-protected file (AES-256-GCM, PBKDF2-SHA256). Clipboard history is included only if you tick the box when exporting. A forgotten password cannot be recovered

## Not built yet (icons show "Coming soon")
Voice typing, themes, Urdu layout, swipe typing, autocorrect, password-manager autofill chips. GIFs are planned after v1.

## Installing new builds over old ones
Debug builds from GitHub Actions are all signed with the shared key in `app/debug.keystore`, and the version number rises with every run, so a new APK updates the old one. If you installed a build from before this change, uninstall it once (the signing key was different). Release APKs use your own key, so uninstall the debug build before installing a release build.

## Privacy
See PRIVACY.md. No internet permission, no analytics, no accounts.

## Push to GitHub
```
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/creatorsera/remaino.git
git push -u origin main
```
GitHub Actions then builds the app on every push. Open the run under the Actions tab and download the `remaino-debug-apk` file to install it on your phone.

Add a license: on GitHub choose Add file > Create new file, name it `LICENSE`, and pick the Apache-2.0 template.

## Build locally
Open the folder in Android Studio (JDK 17), or run `gradle assembleDebug`.
To create the Gradle wrapper once: `gradle wrapper --gradle-version 9.6.0` and commit the files it creates.

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
   The Release workflow attaches `remaino-v0.1.0.apk` and a checksum to a GitHub Release.

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
| `EmojiData.kt` | Emoji lists and recents |
| `KeyboardPrefs.kt` | Options (number row, key preview) |
| `Backup.kt` | Encrypted export and import |
| `Clipboard.kt` | Encrypted clipboard history |
| `TypingStats.kt` | Word counts and book stats (numbers only) |
| `SettingsActivity.kt` | Setup and stats screen |
| `.github/workflows/` | CI build and release |

## Notes
- This first version was written without being compiled. If the first GitHub Actions run fails, open the failed step and share the error text to get it fixed.
- Toolchain: Android Gradle Plugin 9.4.0 with Gradle 9.6.0, compileSdk 36, built-in Kotlin. Keep these in step: Dependabot PRs that bump only one of them will fail the build, so merge them together or close them.
- Action versions in the workflows may have newer majors; Dependabot will open update PRs.


## Added in this version
- **Emoji search:** in the emoji sheet, tap the magnifier tab and type; matching emoji appear in the toolbar.
- **Text stickers:** open the tray, tap the sticker icon, type text, pick a colour and send. It is shared as a PNG, so the app you are typing in must accept images from keyboards.
- **Clipboard images:** images you copy are saved encrypted. Tap one in the clipboard to paste it (same image rule as stickers).
- **Roman Urdu to Urdu:** type a Roman Urdu word (for example `kya` or `shukriya`) and the Urdu spelling appears as a suggestion. About 700 common words are built in. Only exact matches are offered.
- **Floating keyboard:** turn it on in the app. It needs "Display over other apps". Drag the small bar at the top to move it. Some apps hide overlay windows; switch it off there.
- **Mascot at the cursor:** with "At cursor" selected and the overlay permission granted, the mascot sits next to the real caret. Without the permission, or in editors that do not report the caret, it stays in the band above the keys.

Sticker and image sharing, the floating window and the cursor overlay depend on the phone and the app you type in, so test them on your own device.
