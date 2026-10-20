# Security and privacy summary

## Passwords and Microsoft accounts
- Fold Word has **no sign-in screen and no password fields**. An automated test checks the source for password input fields.
- Word Online opens **Microsoft's own website in Chrome**. Microsoft handles sign-in, subscriptions and OneDrive.
- The optional *Sign-in check (beta)* screen shows Microsoft's site inside the app. It **never injects script, never adds a script bridge, and never reads Microsoft's cookies or pages**. Third-party cookies are off, and only Microsoft sign-in hosts are allowed. These rules are checked by an automated test.
- No Microsoft Graph, no Azure app registration, no client secrets, and no access tokens are stored by Fold Word.

## Your documents
- Offline documents are read and written only through **Android's file picker** (Storage Access Framework). Fold Word has **no storage permission**: it can only open the files and folders you pick.
- The offline editor runs from files inside the app. It is served on a private address, every other network request is blocked, and the page has a strict content policy (`default-src 'none'`). Document text is inserted as text, never as code, so a document cannot run scripts. All of this is checked by automated tests.
- **Nothing is uploaded automatically.** *Share a copy* and *Upload to OneDrive* only run when you tap them, and Fold Word explains first that uploading creates a separate copy.
- Fold Word contains **no networking code** of its own (checked by a test). It doesn't log document contents.
- Before replacing a file, Fold Word saves a **backup** of the previous version inside the app (the newest 20 are kept). After saving, it reads the file back to confirm it was written correctly.

## Stored inside the app (on this phone only)
- The **recent documents list**: file addresses, names, locations and times. No content.
- **Recovery drafts** of unsaved changes, and **backups** of replaced versions.
- These are in the app's private storage, are not backed up to the cloud (`allowBackup="false"`), and are deleted if you uninstall Fold Word. Settings shows how many there are and lets you delete them.

## Permissions
| Permission | Why |
|---|---|
| `INTERNET` | Only so Word Online can be opened (in Chrome) and for the beta sign-in screen |
| `ACCESS_NETWORK_STATE` | To show *Online / No internet* and explain when Word Online can't open |

There are no camera, microphone, location, contacts or storage permissions. Cleartext (http://) traffic is blocked, and only the phone's system certificate authorities are trusted.

## Analytics and ads
There are none.

## Third-party code and licences
- AndroidX (core, appcompat, browser, webkit, recyclerview): Apache License 2.0
- Material Components for Android: Apache License 2.0
- jsdom (used only for automated tests, not in the app): MIT
- The DOCX, Markdown and text engine is Fold Word's own code and needs no extra libraries.
