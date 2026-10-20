# Installing Fold Word on your Galaxy Z Fold 8

Everything below is done on the phone. No computer and no coding needed.

## 1. Get the latest build

1. Open **github.com/sumanpresi/FoldWord** in Chrome and sign in.
2. Tap the **Actions** tab.
3. Tap the newest run at the top that has a **green tick**. (Yellow dot = still building. Red cross = failed; don't install it.)
4. Scroll down to **Artifacts** and tap **fold-word-debug-apk**. Chrome downloads a ZIP file.
   - **fold-word-reports** is for checking test results; you don't need to install it.

## 2. Unpack it

1. Open **My Files** › **Downloads**.
2. Tap the downloaded ZIP (for example `fold-word-debug-apk.zip`) and choose **Extract**.
3. Inside the new folder you'll find **app-debug.apk**.

## 3. Install

1. **Uninstall the old Fold Word first** (long-press its icon › **Uninstall**).
   Each cloud build is signed with a new test key, so Android refuses to install over the old one ("App not installed").
   Your documents in phone folders are safe. Drafts, backups and the recent list inside the app are removed.
2. Tap **app-debug.apk** › **Install**.
3. If Android asks, allow **My Files** to install unknown apps (you can switch this off again afterwards in Settings › Apps › My Files).
4. Tap **Open**.

> **Before uninstalling**, if you have **unsaved drafts** you care about, open each one and **Save** it to a phone folder.

## 4. First-time setup (one minute)

1. Tap **Offline documents** › **Choose folder**. Pick **Documents** (or create a *Fold Word* folder) › **Use this folder** › **Allow**.
2. Optional, for Word Online's full toolbar: tap **Open Word in Chrome** on the *Get the full Word ribbon* card, then in Chrome tap **⋮** and tick **Desktop site**.
3. Optional: **Add one-tap Word to home screen**.

## 5. Quick test

1. **New document** › **Word document (.docx)** › type a sentence › make one word **bold** › **Save** › choose your folder › **Save**.
2. Close Fold Word, reopen it, tap the document under **Recent documents**: your text and bold word are there.
3. Switch on **Airplane mode** and repeat step 2: it still opens and saves. Tap **Open Microsoft Word Online**: you get an "Internet needed" message.

Then work through the full [test checklist](TEST_CHECKLIST.md).

## If something goes wrong

- **"App not installed"**: uninstall the old Fold Word first (step 3.1).
- **A file won't open**: Fold Word explains why (moved, permission lost, unsupported type). Open it again with **Open file**.
- **"This location doesn't allow changes"**: some apps (for example email attachments) give read-only access. Use **Save as** to keep a copy.
- Take a screenshot and send it to whoever is helping you.
