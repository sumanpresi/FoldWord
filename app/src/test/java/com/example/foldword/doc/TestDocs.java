package com.example.foldword.doc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds small .docx packages by hand, so tests control exactly which XML a file contains. */
final class TestDocs {

    private TestDocs() {}

    static final String W_NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
        + "xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" "
        + "xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"";

    /** A .docx whose body is the given XML. */
    static byte[] docx(String bodyXml) throws IOException {
        return docx(bodyXml, null, null);
    }

    static byte[] docx(String bodyXml, String stylesXml, String numberingXml) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            put(z, "[Content_Types].xml", "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
            put(z, "_rels/.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            put(z, "word/document.xml", "<?xml version=\"1.0\"?><w:document " + W_NS + "><w:body>" + bodyXml + "</w:body></w:document>");
            if (stylesXml != null) put(z, "word/styles.xml", stylesXml);
            if (numberingXml != null) put(z, "word/numbering.xml", numberingXml);
        }
        return bos.toByteArray();
    }

    static void put(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    static String p(String text) {
        return "<w:p><w:r><w:t xml:space=\"preserve\">" + text + "</w:t></w:r></w:p>";
    }

    /** A document that uses every feature the offline editor supports. */
    static DocModel.Document allFeatures() {
        DocModel.Document d = new DocModel.Document();
        DocModel.Paragraph title = DocModel.Paragraph.of("Quarterly report");
        title.style = DocModel.STYLE_TITLE;
        d.blocks.add(title);
        for (String s : new String[] {DocModel.STYLE_H1, DocModel.STYLE_H2, DocModel.STYLE_H3, DocModel.STYLE_QUOTE}) {
            DocModel.Paragraph h = DocModel.Paragraph.of("Style " + s);
            h.style = s;
            d.blocks.add(h);
        }
        DocModel.Paragraph mixed = new DocModel.Paragraph();
        mixed.runs.add(new DocModel.Run("plain "));
        DocModel.Run b = new DocModel.Run("bold"); b.bold = true; mixed.runs.add(b);
        DocModel.Run i = new DocModel.Run(" italic"); i.italic = true; mixed.runs.add(i);
        DocModel.Run u = new DocModel.Run(" underline"); u.underline = true; mixed.runs.add(u);
        DocModel.Run s = new DocModel.Run(" strike"); s.strike = true; mixed.runs.add(s);
        DocModel.Run big = new DocModel.Run(" 24pt"); big.sizePt = 24; mixed.runs.add(big);
        DocModel.Run brk = new DocModel.Run("\nsecond line\twith tab"); mixed.runs.add(brk);
        d.blocks.add(mixed);
        for (String a : new String[] {DocModel.ALIGN_CENTER, DocModel.ALIGN_RIGHT, DocModel.ALIGN_JUSTIFY}) {
            DocModel.Paragraph p = DocModel.Paragraph.of("Aligned " + a);
            p.align = a;
            d.blocks.add(p);
        }
        for (int k = 0; k < 2; k++) {
            DocModel.Paragraph li = DocModel.Paragraph.of("Bullet " + k);
            li.list = DocModel.LIST_BULLET;
            li.level = k;
            d.blocks.add(li);
        }
        for (int k = 0; k < 2; k++) {
            DocModel.Paragraph li = DocModel.Paragraph.of("Number " + k);
            li.list = DocModel.LIST_NUMBER;
            d.blocks.add(li);
        }
        DocModel.Table t = new DocModel.Table();
        for (int r = 0; r < 2; r++) {
            java.util.List<java.util.List<DocModel.Paragraph>> row = new java.util.ArrayList<>();
            for (int c = 0; c < 3; c++) {
                java.util.List<DocModel.Paragraph> cell = new java.util.ArrayList<>();
                cell.add(DocModel.Paragraph.of("r" + r + "c" + c));
                row.add(cell);
            }
            t.rows.add(row);
        }
        d.blocks.add(t);
        d.blocks.add(DocModel.Paragraph.of("Unicode: é 中文 😀 & <tags> \"quotes\""));
        return d;
    }
}
