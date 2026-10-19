# Fold Word: test checklist

Tick each item on a real Galaxy Z Fold 8. Record the Android version and One UI version at the top.

**Device:** ______________ **Android / One UI:** ______________ **Chrome version:** ______________ **Date:** ______

## A. Build and install
- [ ] Gradle sync finishes with no errors in Android Studio.
- [ ] Build > Build APK(s) succeeds. `app/build/outputs/apk/debug/app-debug.apk` exists.
- [ ] The APK installs on the Fold 8 and the app opens.
- [ ] The app name is "Fold Word" and the W icon shows in the app drawer.

## B. Launch screen
- [ ] Cover screen (folded): the layout is readable and both buttons are reachable with one thumb.
- [ ] Unfolded: the layout uses the wider margins, nothing is stretched or cut off.
- [ ] Rotating the phone keeps the layout correct.
- [ ] The splash screen shows the blue W and no flicker on launch.
- [ ] The privacy note is visible and readable.

## C. Primary path: Open Word (browser)
- [ ] "Open Word" opens https://word.cloud.microsoft/ in a Chrome Custom Tab, with the address shown.
- [ ] Microsoft's sign-in page appears and works. Use a real test account you own. The app never asks for or stores the password.
- [ ] After sign-in, you can open or create a document.
- [ ] Typing, formatting, and inserting a table work in the document.
- [ ] Changes save to OneDrive (check the "saved" status in Word, then reopen the file).
- [ ] Your existing Microsoft session is still active the next time you tap "Open Word" (no need to sign in again).
- [ ] Back closes the Custom Tab and returns to Fold Word.

## D. Beta path: Open inside Fold Word
- [ ] The beta screen loads word.cloud.microsoft with the progress bar.
- [ ] Sign-in works, or the failure is recorded below with the exact message and the page it stopped on.
- [ ] Editing works and does not freeze.
- [ ] Any page outside the allowlist (for example, an external link) opens in the browser, not in the app.
- [ ] The Upload or Insert picture option opens the phone's file picker, and the chosen file uploads.
- [ ] Download or export: blob links show the "saved in the browser" message. HTTPS downloads open in the browser.
- [ ] Back goes to the previous Word page. At the first page it exits the screen.
- [ ] Reload works. Open in browser (menu) opens the current page in Chrome.
- [ ] Airplane mode: the friendly error message appears and Reload recovers after reconnecting.
- [ ] Fold and unfold while editing: the page does not reload, and typing is not lost.
- [ ] Rotate while the menu is open: no crash.

## E. Recent shortcut
- [ ] After visiting Word in the beta screen, "Continue where you left off" appears on the launch screen.
- [ ] The card shows only the path, with no query string or token-like text.
- [ ] "Continue" opens the saved page in the browser.
- [ ] "Forget" hides the card and clears the saved address. It stays hidden after restarting the app.

## F. Security and privacy
- [ ] The app asks for no permissions other than INTERNET (check App info > Permissions: none shown).
- [ ] Opening a plain http:// link (for example, from a test page) is blocked or leaves the app. It does not load inside the WebView.
- [ ] The app has no password fields of its own. Search the code: `grep -ri password app/src` should only find comments and strings about privacy.
- [ ] Backups are off (`allowBackup="false"` in the manifest).
- [ ] No keystore, password, or `.apk` file is committed to Git (`git status` is clean of these).
- [ ] The release build is signed with your own keystore, which is stored outside the repository.

## G. Regression after updates
- [ ] Repeat sections C and D after every Chrome update, Word web change, or Fold Word version change.
- [ ] Note any new Microsoft host needed for sign-in, then add it to `Config.kt` only after review.

## Beta failures to record
| Step | Exact message or behaviour | Page/host (no tokens) | Browser works? |
|------|----------------------------|-----------------------|----------------|
|      |                            |                       |                |
