# Checks report: Fold Word 2.0

This lists only checks that were actually run, and their results, as of 20 October 2026.

## 1. Cloud build (GitHub Actions run 39361307831, commit 3cddb18)

| Step | Result |
|---|---|
| Offline editor tests (Node 20 + jsdom) | ✅ passed |
| Install Android SDK 35 and build tools 35.0.0 | ✅ |
| `gradle testDebugUnitTest assembleDebug lintDebug` (Gradle 8.9, AGP 8.7.2, Kotlin 2.0.21, Java 17) | ✅ |
| Unit tests (real JUnit 4.13.2) | ✅ **38 run, 0 failed, 0 skipped** |
| APK artifact `fold-word-debug-apk` | ✅ uploaded (about 5.2 MB) |
| Test and lint reports artifact `fold-word-reports` | ✅ uploaded (not reviewed here: the artifact can't be downloaded from this environment) |

Warnings from GitHub: some actions run on a deprecated Node.js 20 runtime, and `setup-java@v4` is deprecated. Neither affects the build.

## 2. Unit tests (38), in `app/src/test/java/com/example/foldword/doc/`

- **DocxEngineTest (12):** every supported feature survives save and reopen, with nothing reported lost; a second save is identical to the first; separate numbered lists restart at 1; invalid XML characters are removed; short table rows are padded; unsupported content is reported (pictures, links, tracked changes, comments, colours, footnotes, merged cells, equations, headers) and kept text stays; plain Word documents show no warnings; localised heading styles and style-based lists are recognised and the original style id is reused; DOCTYPE/entity XML is refused; non-DOCX files get a clear message; ZIP path-traversal entries are ignored; all required package parts are written.
- **TextAndFormatsTest (10):** create, edit, save and reopen TXT; exact TXT round trip; UTF-8 BOM, UTF-16 and Windows-1252 detection; exact Markdown round trip; Markdown structure parsing; escaping of literal Markdown symbols; formatting-loss warnings for TXT and MD; format detection with PDF view-only and .doc refused; safe file names; JSON escaping and depth limit.
- **SafetyAndPolicyTest (10):** new documents ask where to save; files with unsupported content can only be saved as a copy; replacing needs confirmation; read-only locations offer Save As; copy names never equal the original; atomic write leaves no temporary file; a failed write keeps the original; full-storage errors are recognised; draft round trip (process recreation); damaged draft values are clamped.
- **AppSecurityStaticTest (6):** only INTERNET and ACCESS_NETWORK_STATE permissions, cleartext off, backups off; no password fields; no networking or upload code; WordWebActivity never injects script, adds a bridge or reads cookies; the editor page has `default-src 'none'` and no network calls; the network config blocks cleartext and user certificates.

The same 38 tests were also run in the build environment with a minimal JUnit stand-in, with the same result.

## 3. Editor tests (6), in `tools/editor-tests`

Round trip of two representative documents through the editor page with no changes; browser-generated markup (Chrome divs, lists nested directly in lists, `<font size>`, inline styles, deep headings, quotes, tables, loose text) is converted correctly; document text containing `<img onerror>` and `<script>` stays as text and never runs; invalid style, alignment, level and size values are made safe; inserting a table works.

## 4. Real-document checks (build environment)

- Word files with python-docx 1.2.0: one with only supported features, and one with a picture, header, footer, red text, Arial font, Heading 5, indentation, a merged cell and a comment.
- Reading: the simple file reported nothing lost; the complex file correctly listed all 8 kinds of lost content.
- Saving and reopening the simple file gave an identical model; python-docx read the styles, bold run, alignment and table correctly.
- LibreOffice opened Fold Word's saved files and converted them to text and PDF. The rendered page matched the original (headings, emphasis, alignment, bullets, numbering, quote, bordered table, Unicode and emoji), and numbered lists restarted correctly.
- Markdown and text written by Fold Word re-read byte-for-byte identically.

## 5. Not tested (requires the phone)

The screens themselves (home, editor, browse, PDF, settings), the Android file and folder pickers, permission denial, fold and unfold, rotation, font scaling, process recreation, low storage on a real device, Chrome and Word Online behaviour, Desktop-site persistence, the home-screen shortcut, and APK installation. Also untested: DOCX files made by Microsoft Word itself (only python-docx and Fold Word files were used), and the lint report, which wasn't reviewed.

These are covered by the phone sections of [TEST_CHECKLIST.md](TEST_CHECKLIST.md). Please record any failure there.
