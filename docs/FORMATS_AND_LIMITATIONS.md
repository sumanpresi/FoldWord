# Supported formats and known limitations

## Capability by format

| Format | Create | Open | Edit | Save | Save as | Notes |
|---|---|---|---|---|---|---|
| **Word .docx** | ✅ | ✅ | ✅ | ✅ (see rules below) | ✅ | Fold Word's own DOCX engine |
| **Plain text .txt** | ✅ | ✅ | ✅ | ✅ | ✅ | UTF-8, UTF-16 and Windows-1252 are detected |
| **Markdown .md** | ✅ | ✅ | ✅ | ✅ | ✅ | Common Markdown subset |
| **PDF .pdf** | ❌ | ✅ view | ❌ | ❌ | ❌ | Android's built-in PDF renderer; password-protected PDFs can't be opened |
| Old Word .doc | ❌ | ❌ | ❌ | ❌ | ❌ | Open it in Word Online instead |
| RTF, ODT | ❌ | ❌ | ❌ | ❌ | ❌ | Not supported in this version |

Word Online (in Chrome) supports whatever Microsoft's Word for the web supports. It needs internet and your files must be in OneDrive.

## What the offline DOCX engine keeps

Paragraphs · Title · Heading 1, 2, 3 · Quote · **bold** · *italic* · underline · strikethrough · font size · left/centre/right/justified alignment · bullet and numbered lists (9 levels) · line breaks and tabs · simple tables (rows, columns, column widths, table style) · page size, orientation and margins · the original document's styles, theme and fonts list.

These were checked by saving and reopening (unit tests), by opening Fold Word's files with python-docx, and by opening them in LibreOffice and rendering them to PDF.

## What it does not keep

When you open a .docx containing any of the following, Fold Word shows a yellow banner listing exactly which ones. **It will not overwrite that file.** Save creates a copy (for example *Report (edited).docx*), and the copy will not contain:

- Pictures, drawings and embedded objects
- Headers and footers
- Footnotes and endnotes
- Comments
- Tracked changes (inserted text is kept as normal text; deleted text is removed)
- Text boxes and shapes
- Fields such as page numbers, tables of contents and dates (the displayed text is kept)
- Hyperlinks (the text is kept, the link is not)
- Font names, colours and highlighting
- Superscript, subscript and other character effects
- Paragraph spacing, indentation and tab stops
- Paragraph borders and shading
- Custom paragraph styles (saved as normal text)
- Heading levels 4–9 (saved as Heading 3)
- Letter and Roman-numeral list numbering (saved as 1, 2, 3)
- Merged table cells, cell shading and borders, tables inside tables (flattened)
- Section breaks, columns and page breaks (saved as line breaks)
- Bookmarks, special symbol characters, equations, content controls, macros

Known visual differences, even in fully supported files: bullet symbols and list indents use Fold Word's standard list style.

## Markdown

Read and written: `#`, `##`, `###` headings · `-`, `*`, `+` and `1.` lists with indentation · `>` quotes · pipe tables · `**bold**` · `*italic*` / `_italic_` · `~~strike~~` · `<u>underline</u>` · line breaks inside a paragraph.
Other Markdown (links, images, code blocks, HTML) is kept as plain text. Font sizes, alignment and the Title style can't be stored in Markdown; Fold Word warns before saving.

## Plain text

Each line is one paragraph, and saving gives back exactly the same lines. Formatting, headings, lists and tables can't be stored; Fold Word warns before saving and writes lists as "• " / "1. " and tables as tab-separated lines.

## Other limitations

- No images in the offline editor (pasting a picture inserts nothing; pasting text keeps the text only).
- No spell-check suggestions beyond Android's keyboard.
- No printing from the offline editor. Use **Share a copy** to another app, or Word Online.
- Files over 50 MB can't be edited offline.
- No automatic sync between phone files and OneDrive. Uploading creates a separate copy.
- Each cloud build has a new signing key, so updates require uninstalling first.
