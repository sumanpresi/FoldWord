# Fold Word

A document app for the **Samsung Galaxy Z Fold 8** with two clearly separate modes:

| | **Offline documents** | **Microsoft Word Online** |
|---|---|---|
| Internet needed | No | Yes |
| Microsoft account needed | No | Yes (Microsoft's own sign-in) |
| Where files live | Folders on your phone that you choose | Your OneDrive |
| Editor | Fold Word's own editor, inside the app | Microsoft's Word for the web, in Chrome |
| Formats | .docx, .txt, .md (edit) · .pdf (view only) | Everything Word for the web supports |

Fold Word is **not** made by Microsoft and is not a copy of Microsoft Word. The offline editor covers everyday writing (see [what it supports](docs/FORMATS_AND_LIMITATIONS.md)). For full Word features, use Word Online.

## What you can do

**Home screen**
- **New document**: Word (.docx), plain text (.txt) or Markdown (.md). Works offline.
- **Open file**: pick any supported file with Android's file picker, including Downloads.
- **Browse phone**: choose a folder and browse its documents and subfolders.
- **Offline documents**: your Fold Word folder (you pick it once, for example *Documents*).
- **Recent documents**: files you opened, plus any **unsaved drafts**.
- **Open Microsoft Word Online** and **Browse my OneDrive files**: open in Chrome.
- **Settings**: folder, privacy, backups, drafts, and the Word Online extras.

**Offline editor**
- Title, Heading 1–3, Quote, **bold**, *italic*, underline, strikethrough, font size.
- Left, centre, right and justified alignment; bullet and numbered lists with indent.
- Tables: insert, add or delete rows and columns.
- Undo and redo, Find (with match count), word count, document information.
- **Save**, **Save as** (choose .docx, .txt or .md and a location), **Rename**, **Delete** (with confirmation).
- **Share a copy** and **Upload to OneDrive** (always your choice; explained before it happens).
- Status bar: *Offline · no internet needed* and *Saved* / *Unsaved changes*.
- Unfolded screen: the document list on the left and the editor on the right. Cover screen: editor only.
- Bluetooth keyboard: Ctrl+S save, Ctrl+F find, Ctrl+B/I/U, Ctrl+Z/Y.

**Your work is protected**
- Fold Word never replaces an original without asking first, and keeps a **backup** of the previous version.
- A file with content the editor can't keep (for example pictures) is **never overwritten**: you save a copy instead, and Fold Word lists exactly what the copy won't include.
- Unsaved changes are kept in a **recovery draft** every 20 seconds and when you leave the app.
- Nothing is uploaded automatically. See [Security and privacy](docs/SECURITY_PRIVACY.md).

## Getting the app onto your phone

You don't need a computer. GitHub builds the app in the cloud. Step-by-step instructions: **[docs/INSTALL_FOLD8.md](docs/INSTALL_FOLD8.md)**.

## Documents

- [Install guide for the Galaxy Z Fold 8](docs/INSTALL_FOLD8.md)
- [Supported formats and known limitations](docs/FORMATS_AND_LIMITATIONS.md)
- [Security and privacy summary](docs/SECURITY_PRIVACY.md)
- [Test checklist](docs/TEST_CHECKLIST.md)
- [Report of checks actually run](docs/CHECKS_REPORT.md)

## For developers

- Package: `com.example.foldword`, minSdk 26, targetSdk 35, Kotlin 2.0.21, AGP 8.7.2, Gradle 8.9, Java 17.
- Build: `gradle testDebugUnitTest assembleDebug` (the GitHub workflow installs Gradle 8.9; no wrapper is committed).
- Offline engine (plain Java, no dependencies): `app/src/main/java/com/example/foldword/doc/`.
- Editor page: `app/src/main/assets/editor/` (tests in `tools/editor-tests`, run with `npm ci && npm test`).
- Unit tests: `app/src/test/java/com/example/foldword/doc/`.
- Workflow: `.github/workflows/build-android.yml` (editor tests, unit tests, APK, lint, reports).
