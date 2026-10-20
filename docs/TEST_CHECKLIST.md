# Fold Word 2.0: test checklist

**Automated** items run on every GitHub build (see [CHECKS_REPORT.md](CHECKS_REPORT.md)).
**Phone** items must be checked by hand on the Galaxy Z Fold 8. They can't be automated without an Android emulator.

Device: ______  One UI: ______  Chrome: ______  Build run #: ______  Date: ______

## A. Build and install
- [ ] Automated: editor tests pass, unit tests pass, APK builds, APK artifact uploaded.
- [ ] Phone: the old Fold Word is uninstalled, the new APK installs, and the icon and name are "Fold Word".

## B. Home screen and layouts
- [ ] Unfolded: header on top; actions on the left; Recent documents on the right.
- [ ] Cover screen: one column (actions, recent, online).
- [ ] Fold and unfold on the home screen: the layout switches correctly.
- [ ] Rotate in both states: nothing is cut off.
- [ ] Settings › Display › Font size at maximum: text wraps and buttons stay usable.
- [ ] Light mode and dark mode: all labels are readable.
- [ ] Header chip shows **Online** with Wi-Fi, and **No internet** in Airplane mode (it updates without reopening).

## C. Offline documents (do these in Airplane mode)
- [ ] New document › Word: type, bold, italic, underline, heading, bullets, numbering, a table › Save › choose folder › Saved.
- [ ] Close the app completely (swipe it away) › reopen › Recent › document opens with all formatting.
- [ ] Restart the phone › reopen the document › same content.
- [ ] New document as .txt and .md: create, edit, save, reopen.
- [ ] Open file › Downloads › open a .docx made in Microsoft Word.
- [ ] Browse phone › choose a folder › open a subfolder › open a document › Back returns to the folder.
- [ ] Offline documents › first time asks for a folder › later goes straight to it.
- [ ] Browse › **New** creates a document directly in that folder and opens it.
- [ ] PDF opens view-only, all pages scroll, and Share works.
- [ ] Unsupported file (for example .doc) shows a clear message.
- [ ] Automated: create, edit, save and reopen TXT/MD/DOCX; formatting round trip; encodings.

## D. Saving safely
- [ ] First Save on an existing file asks **Replace "name"?**; later saves in that session don't ask again.
- [ ] Settings › Backups lists the previous version and can save it to the phone.
- [ ] A .docx with a picture or header shows the yellow banner listing what isn't kept; **Save** only offers **Save a copy**, and the original is unchanged (check its size and date in My Files).
- [ ] Save as › Plain text on a formatted document warns which formatting won't be stored.
- [ ] Save as to the same folder and name: Android adds "(1)" and doesn't overwrite.
- [ ] Rename and Delete each ask for confirmation, and the recent list updates.
- [ ] Automated: overwrite policy, copy names never equal the original, atomic write, failed write keeps the original, low-storage detection.

## E. Recovery
- [ ] Type without saving › press Home › force-stop Fold Word in Settings › reopen › the draft appears under Recent as "Unsaved changes" › open it › text restored.
- [ ] Open the same file again later › "Unsaved changes found" › Restore and Discard both work.
- [ ] Back with unsaved changes asks Save / Keep as draft / Discard.
- [ ] Fold and unfold **while typing in the editor**: the text, cursor and undo still work, and the document list appears or hides.
- [ ] Turn on Developer options › "Don't keep activities", open a document, type, press Home, return: the changes are restored.
- [ ] Automated: draft JSON round trip; damaged draft values are made safe.

## F. Permissions and errors
- [ ] In the folder picker, press Back without choosing: nothing happens and there's no crash.
- [ ] Move or delete a recent file in My Files, then tap it in Recent: "moved, renamed or deleted" message with Remove from list.
- [ ] Open an email attachment: it's read-only, so Save offers Save a copy.
- [ ] Fill the storage nearly full, then Save: "storage is full" message, and the draft is kept.

## G. Online mode
- [ ] Open Microsoft Word Online opens Chrome at word.cloud.microsoft (Microsoft's own sign-in).
- [ ] With Desktop site ticked in Chrome: the full ribbon appears (File, Home, Insert, Layout, References, Review, View).
- [ ] Browse my OneDrive files opens onedrive.live.com in Chrome.
- [ ] Airplane mode › Open Word Online shows **Internet needed** with Try again.
- [ ] From the editor with unsaved changes › menu › Open Word Online asks to save first.
- [ ] Upload to OneDrive explains that it makes a separate copy, and only then shows the share list.
- [ ] One-tap home screen Word icon opens Word in Chrome (in Airplane mode it shows a message instead).
- [ ] Settings › Sign-in check (beta) loads Microsoft sign-in; Edit in Chrome opens the full Chrome app.
- [ ] After reconnecting: nothing was uploaded and no local file changed by itself.

## H. Privacy (automated, see report)
- [ ] Only INTERNET and ACCESS_NETWORK_STATE permissions; no cleartext; backups off.
- [ ] No password fields; no networking or upload code; Microsoft pages never scripted or read.
- [ ] The editor page loads nothing from the network, and document text can't run scripts.

## Failures found
| Section | What happened | Screenshot? |
|---|---|---|
| | | |
