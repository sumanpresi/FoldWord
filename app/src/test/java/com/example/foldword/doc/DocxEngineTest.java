package com.example.foldword.doc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** DOCX reading, writing and fidelity reporting. */
public class DocxEngineTest {

    private static DocxReader.Result reread(DocModel.Document d) throws IOException {
        return DocxReader.read(new ByteArrayInputStream(DocxWriter.write(d, null, null)));
    }

    @Test
    public void everySupportedFeatureSurvivesSaveAndReopen() throws IOException {
        DocModel.Document original = TestDocs.allFeatures();
        DocxReader.Result r = reread(original);
        // Column widths are added on first save (Word needs them); they are layout data, not content.
        for (DocModel.Block b : r.document.blocks) if (b instanceof DocModel.Table) ((DocModel.Table) b).columnWidths.clear();
        assertEquals(DocJson.toJson(original), DocJson.toJson(stripPage(r.document)));
        assertTrue("A file Fold Word wrote must reopen with nothing lost: " + r.lostFeatures, r.lostFeatures.isEmpty());
    }

    private static DocModel.Document stripPage(DocModel.Document d) {
        d.page = null;
        return d;
    }

    @Test
    public void secondSaveIsIdenticalToFirst() throws IOException {
        byte[] first = DocxWriter.write(TestDocs.allFeatures(), null, null);
        DocxReader.Result r = DocxReader.read(new ByteArrayInputStream(first));
        DocxReader.Result r2 = DocxReader.read(new ByteArrayInputStream(DocxWriter.write(r.document, r.reusableParts, r.styleIds)));
        assertEquals(DocJson.toJson(r.document), DocJson.toJson(r2.document));
    }

    @Test
    public void separateNumberedListsRestartAtOne() throws IOException {
        DocModel.Document d = new DocModel.Document();
        for (String text : new String[] {"a", "b", "-", "c"}) {
            DocModel.Paragraph p = DocModel.Paragraph.of(text);
            if (!"-".equals(text)) p.list = DocModel.LIST_NUMBER;
            d.blocks.add(p);
        }
        String numbering = part(DocxWriter.write(d, null, null), "word/numbering.xml");
        String document = part(DocxWriter.write(d, null, null), "word/document.xml");
        assertTrue(numbering.contains("w:numId=\"2\"") && numbering.contains("w:numId=\"3\""));
        assertTrue(numbering.contains("<w:startOverride w:val=\"1\"/>"));
        assertTrue(document.indexOf("<w:numId w:val=\"2\"/>") < document.indexOf("<w:numId w:val=\"3\"/>"));
    }

    @Test
    public void invalidXmlCharactersAreRemovedNotWritten() throws IOException {
        DocModel.Document d = new DocModel.Document();
        d.blocks.add(DocModel.Paragraph.of("bell\u0007 and nul\u0000 gone"));
        DocxReader.Result r = reread(d);
        assertEquals("bell and nul gone", ((DocModel.Paragraph) r.document.blocks.get(0)).plainText());
    }

    @Test
    public void shortTableRowsArePaddedSoWordAcceptsTheFile() throws IOException {
        DocModel.Document d = new DocModel.Document();
        d.blocks.add(DocJson.fromJson("{\"blocks\":[{\"t\":\"table\",\"rows\":[[[{\"runs\":[{\"text\":\"a\"}]}],[{\"runs\":[{\"text\":\"b\"}]}]],[[{\"runs\":[{\"text\":\"c\"}]}]]]}]}").blocks.get(0));
        DocModel.Table t = (DocModel.Table) reread(d).document.blocks.get(0);
        assertEquals(2, t.rows.get(1).size());
    }

    @Test
    public void unsupportedContentIsReportedNeverSilentlyDropped() throws IOException {
        String body = "<w:p><w:r><w:drawing/></w:r></w:p>"
            + "<w:p><w:hyperlink r:id=\"x\"><w:r><w:t>link text</w:t></w:r></w:hyperlink></w:p>"
            + "<w:p><w:ins w:id=\"1\"><w:r><w:t>inserted</w:t></w:r></w:ins><w:del w:id=\"2\"><w:r><w:delText>gone</w:delText></w:r></w:del></w:p>"
            + "<w:p><w:commentRangeStart w:id=\"0\"/><w:r><w:t>commented</w:t></w:r><w:commentRangeEnd w:id=\"0\"/><w:r><w:commentReference w:id=\"0\"/></w:r></w:p>"
            + "<w:p><w:r><w:rPr><w:color w:val=\"FF0000\"/></w:rPr><w:t>red</w:t></w:r><w:r><w:footnoteReference w:id=\"1\"/></w:r></w:p>"
            + "<w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"2\"/></w:tcPr><w:p/></w:tc></w:tr></w:tbl>"
            + "<w:p><m:oMath/></w:p>"
            + "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"h\"/></w:sectPr>";
        DocxReader.Result r = DocxReader.read(new ByteArrayInputStream(TestDocs.docx(body)));
        Set<String> lost = r.lostFeatures;
        for (String expected : new String[] {DocxReader.LOST_IMAGES, DocxReader.LOST_LINKS, DocxReader.LOST_TRACKED,
            DocxReader.LOST_COMMENTS, DocxReader.LOST_FONTS, DocxReader.LOST_FOOTNOTES, DocxReader.LOST_MERGED_CELLS,
            DocxReader.LOST_MATH, DocxReader.LOST_HEADERS}) {
            assertTrue("Missing warning: " + expected + " in " + lost, lost.contains(expected));
        }
        // Text that can be kept is kept.
        String all = DocJson.toJson(r.document);
        assertTrue(all.contains("link text") && all.contains("inserted") && all.contains("commented"));
        assertFalse("Deleted tracked text must not reappear", all.contains("gone"));
    }

    @Test
    public void plainWordDocumentHasNoWarnings() throws IOException {
        String body = TestDocs.p("Hello") + "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val=\"28\"/></w:rPr><w:t>World</w:t></w:r></w:p>"
            + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>";
        DocxReader.Result r = DocxReader.read(new ByteArrayInputStream(TestDocs.docx(body)));
        assertTrue(r.lostFeatures.toString(), r.lostFeatures.isEmpty());
        DocModel.Paragraph p = (DocModel.Paragraph) r.document.blocks.get(1);
        assertEquals(DocModel.ALIGN_CENTER, p.align);
        assertTrue(p.runs.get(0).bold);
        assertEquals(14, p.runs.get(0).sizePt);
        assertEquals(11906, r.document.page.width);
    }

    @Test
    public void headingsAndListsAreRecognisedFromStylesAndNumbering() throws IOException {
        String styles = "<?xml version=\"1.0\"?><w:styles " + TestDocs.W_NS + ">"
            + "<w:style w:type=\"paragraph\" w:styleId=\"berschrift1\"><w:name w:val=\"heading 1\"/></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"ListBullet\"><w:name w:val=\"List Bullet\"/><w:pPr><w:numPr><w:numId w:val=\"7\"/></w:numPr></w:pPr></w:style>"
            + "</w:styles>";
        String numbering = "<?xml version=\"1.0\"?><w:numbering " + TestDocs.W_NS + ">"
            + "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"bullet\"/></w:lvl></w:abstractNum>"
            + "<w:abstractNum w:abstractNumId=\"1\"><w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"decimal\"/></w:lvl></w:abstractNum>"
            + "<w:num w:numId=\"7\"><w:abstractNumId w:val=\"0\"/></w:num><w:num w:numId=\"8\"><w:abstractNumId w:val=\"1\"/></w:num>"
            + "</w:numbering>";
        String body = "<w:p><w:pPr><w:pStyle w:val=\"berschrift1\"/></w:pPr><w:r><w:t>Localised heading</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:pStyle w:val=\"ListBullet\"/></w:pPr><w:r><w:t>Style bullet</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"8\"/></w:numPr></w:pPr><w:r><w:t>Numbered</w:t></w:r></w:p>";
        DocxReader.Result r = DocxReader.read(new ByteArrayInputStream(TestDocs.docx(body, styles, numbering)));
        assertEquals(DocModel.STYLE_H1, ((DocModel.Paragraph) r.document.blocks.get(0)).style);
        assertEquals(DocModel.LIST_BULLET, ((DocModel.Paragraph) r.document.blocks.get(1)).list);
        assertEquals(DocModel.LIST_NUMBER, ((DocModel.Paragraph) r.document.blocks.get(2)).list);
        assertEquals("berschrift1", r.styleIds.get(DocModel.STYLE_H1));
        // Saving reuses the original style id, so the heading keeps its look.
        String doc = part(DocxWriter.write(r.document, r.reusableParts, r.styleIds), "word/document.xml");
        assertTrue(doc.contains("<w:pStyle w:val=\"berschrift1\"/>"));
    }

    @Test
    public void unsafeXmlIsRefused() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            TestDocs.put(z, "word/document.xml", "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY a \"aaaa\">]><w:document " + TestDocs.W_NS + "><w:body>" + TestDocs.p("&a;") + "</w:body></w:document>");
        }
        try {
            DocxReader.read(new ByteArrayInputStream(bos.toByteArray()));
            fail("A DOCTYPE must be refused");
        } catch (DocxReader.DocxException expected) {
            assertTrue(expected.getMessage().contains("unsafe"));
        }
    }

    @Test
    public void nonDocxFilesGiveAClearMessage() {
        try {
            DocxReader.read(new ByteArrayInputStream("this is an old .doc or encrypted file".getBytes(StandardCharsets.UTF_8)));
            fail("Expected an error");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("not a .docx"));
        }
    }

    @Test
    public void pathTraversalEntriesAreIgnored() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            TestDocs.put(z, "../../evil.xml", "x");
            TestDocs.put(z, "word/document.xml", "<?xml version=\"1.0\"?><w:document " + TestDocs.W_NS + "><w:body>" + TestDocs.p("safe") + "</w:body></w:document>");
        }
        assertFalse(DocxReader.unzip(new ByteArrayInputStream(bos.toByteArray())).containsKey("../../evil.xml"));
        assertEquals("safe", ((DocModel.Paragraph) DocxReader.read(new ByteArrayInputStream(bos.toByteArray())).document.blocks.get(0)).plainText());
    }

    @Test
    public void writtenPackageHasTheRequiredParts() throws IOException {
        byte[] data = DocxWriter.write(TestDocs.allFeatures(), null, null);
        for (String name : new String[] {"[Content_Types].xml", "_rels/.rels", "word/_rels/document.xml.rels",
            "word/document.xml", "word/styles.xml", "word/numbering.xml"}) {
            assertTrue("Missing " + name, part(data, name) != null);
        }
    }

    static String part(byte[] docx, String name) throws IOException {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().equals(name)) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = z.read(buf)) > 0) bos.write(buf, 0, n);
                    return new String(bos.toByteArray(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }
}
