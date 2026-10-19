# Fold Word

A small Android launcher for **Microsoft Word for the web** (`https://word.cloud.microsoft/`), made for the Samsung Galaxy Z Fold 8.

## What it does
- **Open Word:** opens Microsoft's official site in the Chrome app. This is the recommended way to edit. Turn on Chrome's *Desktop site* once for Word (⋮ > Desktop site) to get Word's full ribbon editor; Chrome remembers it. Microsoft handles sign-in, subscriptions, the editor, and OneDrive saving.
- **Browse my OneDrive files:** opens your OneDrive file list in Chrome.
- **Sign-in check (beta):** loads Word inside the app. Sign-in works, but Microsoft blocks document editing in embedded browsers, so editing is only supported through Open Word.
- **Continue where you left off:** remembers the last Word page visited in the beta screen (scheme, host and path only). You can forget it at any time.

## What it does not do
- It does not ask for, store, or see your Microsoft password.
- It does not recreate Word's editor or change Microsoft's pages.
- It does not read or store Microsoft cookies or tokens.
- It has no permissions except internet access.

## Requirements
- Android Studio (recent stable version, for example Koala or newer). It includes the Java (JDK 17) that this project uses.
- A Galaxy Z Fold 8 (or any Android 8.0+ phone) with USB debugging enabled.
- A Microsoft account with access to Word for the web.

## Build it (beginner steps)

1. **Install Android Studio** from developer.android.com/studio and open it once. Accept the default SDK installation, which includes Android SDK 35.
2. **Open the project.** Choose *File > Open* and select the `FoldWord` folder (the one containing `settings.gradle.kts`).
3. **Let Gradle sync.** Wait for the progress bar at the bottom to finish. The first sync downloads several hundred MB.
4. **Gradle wrapper (only if asked).** This project does not include the wrapper binary. If Android Studio reports a missing `gradlew`, install Gradle 8.9 and run this once in the project folder:
   ```
   gradle wrapper --gradle-version 8.9
   ```
5. **Run on your phone.** On the Fold 8, go to *Settings > About phone > Software information* and tap *Build number* seven times. Then go to *Developer options* and turn on *USB debugging*. Connect the phone with a USB cable, accept the prompt on the phone, and press the green ▶ button in Android Studio.
6. **Build an APK file to share or test later.** Choose *Build > Build App Bundle(s) / APK(s) > Build APK(s)*. The result is at `app/build/outputs/apk/debug/app-debug.apk`. This is a debug build for personal testing only.
7. **Work through `docs/TEST_CHECKLIST.md`** on the device before you rely on the app.

If the build fails, copy the first red error from the *Build* panel and check the versions in `build.gradle.kts` and `app/build.gradle.kts`.

## Release builds
Release builds must be signed with your own keystore. Create it with *Build > Generate Signed App Bundle / APK* in Android Studio. Keep the keystore file and its passwords **outside** this folder and never commit them. The `.gitignore` already blocks `*.jks`, `*.keystore`, and `*.apk`.

## Publishing caveats
Microsoft's terms restrict modifying or wrapping its service. This app only opens Microsoft's official page and does not modify it. It is not affiliated with Microsoft and does not use Microsoft logos. Check Microsoft's current terms before publishing to the Play Store.

## Project layout
```
settings.gradle.kts, build.gradle.kts, gradle.properties   build configuration
app/build.gradle.kts                                       app dependencies and SDK versions
app/src/main/AndroidManifest.xml                           permissions and activities
app/src/main/java/com/example/foldword/
  Config.kt            Word URL, host allowlist, URL sanitising
  Util.kt              opens links in a Custom Tab
  MainActivity.kt      launch screen
  WordWebActivity.kt   beta WebView
app/src/main/res/      layouts, strings, colours, icon, themes, network security
docs/TEST_CHECKLIST.md device test checklist
```
