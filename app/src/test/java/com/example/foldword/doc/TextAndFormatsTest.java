package com.example.foldword.doc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** TXT and Markdown codecs, format detection and the save warnings. */
public class TextAndFormatsTest {

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    public void newTextDocumentCanBeCreatedEditedAndSaved() throws IOException {
        DocModel.Document d = new DocModel.Document();
        d.blocks.add(DocModel.Paragraph.of("First line"));
        byte[] saved = Formats.TXT.write(d, null, null);
        DocModel.Document reopened = Formats.TXT.read(saved).document;
        ((DocModel.Paragraph) reopened.blocks.get(0)).runs.get(0).text += " edited";
        reopened.blocks.add(DocModel.Paragraph.of("Second line"));
        assertEquals("First line edited\nSecond line\n", new String(Formats.TXT.write(reopened, null, null), StandardCharsets.UTF_8));
    }

    @Test
    public void textRoundTripIsExact() throws IOException {
        byte[] in = utf8("line one\n\n  indented\ttab\nunicode é 😀\n");
        assertArrayEquals(in, Formats.TXT.write(Formats.TXT.read(in).document, null, null));
    }

    @Test
    public void textEncodingsAreDetected() {
        assertEquals("café", ((DocModel.Paragraph) TextCodecs.readText(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'c', 'a', 'f', (byte) 0xC3, (byte) 0xA9}).blocks.get(0)).plainText());
        assertEquals("hi", ((DocModel.Paragraph) TextCodecs.readText(new byte[] {(byte) 0xFF, (byte) 0xFE, 'h', 0, 'i', 0}).blocks.get(0)).plainText());
        assertEquals("café", ((DocModel.Paragraph) TextCodecs.readText("café".getBytes(Charset.forName("windows-1252"))).blocks.get(0)).plainText());
    }

    @Test
    public void markdownRoundTripIsExact() throws IOException {
        String md = "# Title\n\nSome **bold**, *italic*, ~~strike~~ and <u>under</u> text\nsecond line of the paragraph\n\n"
            + "- one\n  - nested\n- two\n\n1. first\n2. second\n\n> quoted\n\n| a | b |\n| --- | --- |\n| **c** | d |\n";
        byte[] out = Formats.MARKDOWN.write(Formats.MARKDOWN.read(utf8(md)).document, null, null);
        assertEquals(md, new String(out, StandardCharsets.UTF_8));
    }

    @Test
    public void markdownIsParsedIntoStructure() throws IOException {
        DocModel.Document d = Formats.MARKDOWN.read(utf8("## Sub\n- item\n1. num\n**b** *i*\n")).document;
        assertEquals(DocModel.STYLE_H2, ((DocModel.Paragraph) d.blocks.get(0)).style);
        assertEquals(DocModel.LIST_BULLET, ((DocModel.Paragraph) d.blocks.get(1)).list);
        assertEquals(DocModel.LIST_NUMBER, ((DocModel.Paragraph) d.blocks.get(2)).list);
        DocModel.Paragraph p = (DocModel.Paragraph) d.blocks.get(3);
        assertTrue(p.runs.get(0).bold);
        assertTrue(p.runs.get(2).italic);
    }

    @Test
    public void markdownEscapesLiteralSymbols() throws IOException {
        DocModel.Document d = new DocModel.Document();
        d.blocks.add(DocModel.Paragraph.of("# not a heading *not italic* 1. not a list"));
        byte[] md = Formats.MARKDOWN.write(d, null, null);
        DocModel.Paragraph back = (DocModel.Paragraph) Formats.MARKDOWN.read(md).document.blocks.get(0);
        assertEquals(DocModel.STYLE_NORMAL, back.style);
        assertEquals("# not a heading *not italic* 1. not a list", back.plainText());
    }

    @Test
    public void savingFormattedTextAsPlainTextWarnsFirst() {
        List<String> warn = Formats.TXT.unsupportedIn(TestDocs.allFeatures());
        assertTrue(warn.toString(), warn.contains("Bold, italic, underline and strikethrough"));
        assertTrue(warn.toString(), warn.contains("Tables (saved as tab-separated lines)"));
        assertTrue(Formats.DOCX.unsupportedIn(TestDocs.allFeatures()).isEmpty());
        assertTrue(Formats.MARKDOWN.unsupportedIn(TestDocs.allFeatures()).contains("Font sizes"));
    }

    @Test
    public void formatsAreDetectedAndPdfIsViewOnly() {
        assertEquals(Formats.DOCX, Formats.detect("Report.DOCX", null));
        assertEquals(Formats.MARKDOWN, Formats.detect("notes.md", "application/octet-stream"));
        assertEquals(Formats.TXT, Formats.detect(null, "text/plain"));
        assertEquals(Formats.PDF, Formats.detect("x.pdf", null));
        assertNull("Old .doc files are not supported", Formats.detect("old.doc", "application/msword"));
        assertTrue(!Formats.PDF.canEdit && !Formats.PDF.canSave);
        try {
            Formats.PDF.read(new byte[0]);
            fail("PDF must not open in the editor");
        } catch (IOException expected) { /* ok */ }
    }

    @Test
    public void fileNamesAreMadeSafe() {
        assertEquals("a_b_c.docx", Formats.DOCX.fileName("a/b:c"));
        assertEquals("Document.txt", Formats.TXT.fileName("  "));
        assertEquals("Notes.md", Formats.MARKDOWN.fileName("Notes.txt"));
    }

    @Test
    public void jsonHandlesEscapesAndRejectsDeepNesting() {
        String tricky = "line sep \"q\" \\ </script>";
        StringBuilder sb = new StringBuilder();
        Json.quote(tricky, sb);
        assertTrue(!sb.toString().contains(" ") && !sb.toString().contains("</script>"));
        assertEquals(tricky, Json.parse(sb.toString()));
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100; i++) deep.append('[');
        try {
            Json.parse(deep.toString());
            fail("Deep nesting must be refused");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }
}
