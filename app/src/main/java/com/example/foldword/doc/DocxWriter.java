package com.example.foldword.doc;

import com.example.foldword.doc.DocModel.Block;
import com.example.foldword.doc.DocModel.Document;
import com.example.foldword.doc.DocModel.PageSetup;
import com.example.foldword.doc.DocModel.Paragraph;
import com.example.foldword.doc.DocModel.Run;
import com.example.foldword.doc.DocModel.Table;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes {@link DocModel} as a standard .docx file that Microsoft Word, Word for the web and
 * LibreOffice can open. When the document came from a .docx, its styles, theme and font table are
 * reused so headings and body text keep their original look.
 */
public final class DocxWriter {

    private DocxWriter() {}

    private static final String NS =
        "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"";

    /** Our own style ids, used when the original document has no matching style. */
    private static final Map<String, String> OWN_IDS = new LinkedHashMap<>();
    static {
        OWN_IDS.put(DocModel.STYLE_TITLE, "Title");
        OWN_IDS.put(DocModel.STYLE_H1, "Heading1");
        OWN_IDS.put(DocModel.STYLE_H2, "Heading2");
        OWN_IDS.put(DocModel.STYLE_H3, "Heading3");
        OWN_IDS.put(DocModel.STYLE_QUOTE, "Quote");
    }

    /**
     * @param reusableParts original "word/styles.xml", "word/theme/theme1.xml" and "word/fontTable.xml"
     *                      from {@link DocxReader.Result}, or null/empty for a new document
     * @param styleIds      semantic style -> original style id, or null/empty for a new document
     */
    public static byte[] write(Document doc, Map<String, byte[]> reusableParts, Map<String, String> styleIds)
        throws IOException {
        Map<String, byte[]> reuse = reusableParts == null ? new HashMap<>() : reusableParts;
        Map<String, String> ids = new HashMap<>();
        byte[] styles = reuse.get("word/styles.xml");
        String stylesXml;
        if (styles != null) {
            stylesXml = new String(styles, StandardCharsets.UTF_8);
            if (styleIds != null) ids.putAll(styleIds);
            // Add any heading/title/quote style the original lacks, so the saved file stays valid.
            StringBuilder extra = new StringBuilder();
            for (Map.Entry<String, String> e : OWN_IDS.entrySet()) {
                if (ids.containsKey(e.getKey())) continue;
                String id = e.getValue();
                if (stylesXml.contains("w:styleId=\"" + id + "\"")) id = "FW" + id; // avoid clashing ids
                ids.put(e.getKey(), id);
                extra.append(styleDefinition(e.getKey(), id));
            }
            if (extra.length() > 0) {
                int end = stylesXml.lastIndexOf("</w:styles>");
                if (end < 0) {
                    stylesXml = ownStyles();
                    ids.clear();
                    ids.putAll(OWN_IDS);
                } else {
                    stylesXml = stylesXml.substring(0, end) + extra + stylesXml.substring(end);
                }
            }
        } else {
            stylesXml = ownStyles();
            ids.putAll(OWN_IDS);
        }

        Numbering numbering = new Numbering();
        String documentXml = documentXml(doc, ids, numbering);

        boolean theme = reuse.containsKey("word/theme/theme1.xml") && styles != null;
        boolean fonts = reuse.containsKey("word/fontTable.xml") && styles != null;

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            put(z, "[Content_Types].xml", contentTypes(theme, fonts));
            put(z, "_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>");
            put(z, "word/_rels/document.xml.rels", documentRels(theme, fonts));
            put(z, "word/document.xml", documentXml);
            put(z, "word/styles.xml", stylesXml);
            put(z, "word/numbering.xml", numbering.xml());
            if (theme) putBytes(z, "word/theme/theme1.xml", reuse.get("word/theme/theme1.xml"));
            if (fonts) putBytes(z, "word/fontTable.xml", reuse.get("word/fontTable.xml"));
        }
        return bos.toByteArray();
    }

    public static void write(Document doc, Map<String, byte[]> reuse, Map<String, String> ids, OutputStream out)
        throws IOException {
        out.write(write(doc, reuse, ids));
    }

    private static void put(ZipOutputStream z, String name, String text) throws IOException {
        putBytes(z, name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void putBytes(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    private static String contentTypes(boolean theme, boolean fonts) {
        String base = "application/vnd.openxmlformats-officedocument.wordprocessingml.";
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
          .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
          .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
          .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
          .append("<Override PartName=\"/word/document.xml\" ContentType=\"").append(base).append("document.main+xml\"/>")
          .append("<Override PartName=\"/word/styles.xml\" ContentType=\"").append(base).append("styles+xml\"/>")
          .append("<Override PartName=\"/word/numbering.xml\" ContentType=\"").append(base).append("numbering+xml\"/>");
        if (theme) sb.append("<Override PartName=\"/word/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>");
        if (fonts) sb.append("<Override PartName=\"/word/fontTable.xml\" ContentType=\"").append(base).append("fontTable+xml\"/>");
        sb.append("</Types>");
        return sb.toString();
    }

    private static String documentRels(boolean theme, boolean fonts) {
        String t = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
          .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
          .append("<Relationship Id=\"rId1\" Type=\"").append(t).append("styles\" Target=\"styles.xml\"/>")
          .append("<Relationship Id=\"rId2\" Type=\"").append(t).append("numbering\" Target=\"numbering.xml\"/>");
        if (theme) sb.append("<Relationship Id=\"rId3\" Type=\"").append(t).append("theme\" Target=\"theme/theme1.xml\"/>");
        if (fonts) sb.append("<Relationship Id=\"rId4\" Type=\"").append(t).append("fontTable\" Target=\"fontTable.xml\"/>");
        sb.append("</Relationships>");
        return sb.toString();
    }

    // ---------------------------------------------------------------- document.xml

    private static String documentXml(Document doc, Map<String, String> ids, Numbering numbering) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
          .append("<w:document ").append(NS).append("><w:body>");
        String prevList = DocModel.LIST_NONE;
        int numberedNum = 0;
        for (Block b : doc.blocks) {
            if (b instanceof Paragraph) {
                Paragraph p = (Paragraph) b;
                if (DocModel.LIST_NUMBER.equals(p.list)) {
                    // A new numbered list starts at 1 whenever the previous paragraph was not part of it.
                    if (!DocModel.LIST_NUMBER.equals(prevList) || numberedNum == 0) numberedNum = numbering.newNumbered();
                }
                paragraph(sb, p, ids, numbering, numberedNum);
                prevList = p.list;
            } else if (b instanceof Table) {
                table(sb, (Table) b, ids, numbering);
                prevList = DocModel.LIST_NONE;
            }
        }
        if (doc.blocks.isEmpty() || doc.blocks.get(doc.blocks.size() - 1) instanceof Table) {
            sb.append("<w:p/>"); // Word requires the body (and a table) to be followed by a paragraph
        }
        PageSetup pg = doc.page == null ? new PageSetup() : doc.page;
        sb.append("<w:sectPr><w:pgSz w:w=\"").append(pg.width).append("\" w:h=\"").append(pg.height).append('"');
        if (pg.landscape) sb.append(" w:orient=\"landscape\"");
        sb.append("/><w:pgMar w:top=\"").append(pg.top).append("\" w:right=\"").append(pg.right)
          .append("\" w:bottom=\"").append(pg.bottom).append("\" w:left=\"").append(pg.left)
          .append("\" w:header=\"").append(pg.header).append("\" w:footer=\"").append(pg.footer)
          .append("\" w:gutter=\"").append(pg.gutter).append("\"/></w:sectPr>");
        sb.append("</w:body></w:document>");
        return sb.toString();
    }

    private static void paragraph(StringBuilder sb, Paragraph p, Map<String, String> ids, Numbering numbering,
                                  int numberedNum) {
        sb.append("<w:p>");
        StringBuilder pPr = new StringBuilder();
        if (!DocModel.STYLE_NORMAL.equals(p.style) && ids.containsKey(p.style)) {
            pPr.append("<w:pStyle w:val=\"").append(attr(ids.get(p.style))).append("\"/>");
        }
        if (!DocModel.LIST_NONE.equals(p.list)) {
            int numId = DocModel.LIST_BULLET.equals(p.list) ? Numbering.BULLET_NUM : numberedNum;
            if (numId <= 0) numId = numbering.newNumbered();
            pPr.append("<w:numPr><w:ilvl w:val=\"").append(Math.max(0, Math.min(8, p.level)))
               .append("\"/><w:numId w:val=\"").append(numId).append("\"/></w:numPr>");
        }
        String jc = jc(p.align);
        if (jc != null) pPr.append("<w:jc w:val=\"").append(jc).append("\"/>");
        if (pPr.length() > 0) sb.append("<w:pPr>").append(pPr).append("</w:pPr>");
        for (Run r : p.runs) run(sb, r);
        sb.append("</w:p>");
    }

    private static String jc(String align) {
        switch (align) {
            case DocModel.ALIGN_CENTER: return "center";
            case DocModel.ALIGN_RIGHT: return "right";
            case DocModel.ALIGN_JUSTIFY: return "both";
            default: return null;
        }
    }

    private static void run(StringBuilder sb, Run r) {
        if (r.text.isEmpty()) return;
        sb.append("<w:r>");
        if (r.hasFormatting()) {
            sb.append("<w:rPr>");
            if (r.bold) sb.append("<w:b/><w:bCs/>");
            if (r.italic) sb.append("<w:i/><w:iCs/>");
            if (r.strike) sb.append("<w:strike/>");
            if (r.sizePt > 0) {
                int half = Math.max(2, Math.min(3276, r.sizePt * 2));
                sb.append("<w:sz w:val=\"").append(half).append("\"/><w:szCs w:val=\"").append(half).append("\"/>");
            }
            if (r.underline) sb.append("<w:u w:val=\"single\"/>");
            sb.append("</w:rPr>");
        }
        StringBuilder text = new StringBuilder();
        String s = r.text;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\t' || c == '\r') {
                if (text.length() > 0) { t(sb, text); text.setLength(0); }
                if (c == '\t') sb.append("<w:tab/>");
                else if (c == '\n') sb.append("<w:br/>");
            } else {
                text.append(c);
            }
        }
        if (text.length() > 0) t(sb, text);
        sb.append("</w:r>");
    }

    private static void t(StringBuilder sb, CharSequence text) {
        sb.append("<w:t xml:space=\"preserve\">").append(escape(text.toString())).append("</w:t>");
    }

    private static void table(StringBuilder sb, Table t, Map<String, String> ids, Numbering numbering) {
        int cols = Math.max(1, t.columnCount());
        boolean keepWidths = t.columnWidths.size() == cols;
        int defaultWidth = Math.max(500, 9000 / cols);
        sb.append("<w:tbl><w:tblPr>");
        if (t.styleId != null) sb.append("<w:tblStyle w:val=\"").append(attr(t.styleId)).append("\"/>");
        sb.append("<w:tblW w:w=\"0\" w:type=\"auto\"/>");
        if (t.styleId == null) {
            sb.append("<w:tblBorders>");
            for (String side : new String[] {"top", "left", "bottom", "right", "insideH", "insideV"}) {
                sb.append("<w:").append(side).append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>");
            }
            sb.append("</w:tblBorders>");
        }
        sb.append("<w:tblLook w:val=\"04A0\" w:firstRow=\"1\" w:lastRow=\"0\" w:firstColumn=\"1\" w:lastColumn=\"0\" w:noHBand=\"0\" w:noVBand=\"1\"/>");
        sb.append("</w:tblPr><w:tblGrid>");
        for (int c = 0; c < cols; c++) {
            sb.append("<w:gridCol w:w=\"").append(keepWidths ? t.columnWidths.get(c) : defaultWidth).append("\"/>");
        }
        sb.append("</w:tblGrid>");
        for (List<List<Paragraph>> row : t.rows) {
            sb.append("<w:tr>");
            for (int c = 0; c < cols; c++) {
                int w = keepWidths ? t.columnWidths.get(c) : defaultWidth;
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(w).append("\" w:type=\"dxa\"/></w:tcPr>");
                List<Paragraph> cell = c < row.size() ? row.get(c) : null;
                if (cell == null || cell.isEmpty()) {
                    sb.append("<w:p/>"); // every cell must contain a paragraph
                } else {
                    String prev = DocModel.LIST_NONE;
                    int num = 0;
                    for (Paragraph p : cell) {
                        if (DocModel.LIST_NUMBER.equals(p.list) && !DocModel.LIST_NUMBER.equals(prev)) num = numbering.newNumbered();
                        paragraph(sb, p, ids, numbering, num);
                        prev = p.list;
                    }
                }
                sb.append("</w:tc>");
            }
            sb.append("</w:tr>");
        }
        sb.append("</w:tbl>");
    }

    // ---------------------------------------------------------------- numbering.xml

    /** One bullet list definition, plus a fresh numbered instance for each separate numbered list. */
    static final class Numbering {
        static final int BULLET_ABSTRACT = 1, NUMBER_ABSTRACT = 2, BULLET_NUM = 1;
        private int nextNum = 2;
        private final StringBuilder nums = new StringBuilder();

        int newNumbered() {
            int id = nextNum++;
            nums.append("<w:num w:numId=\"").append(id).append("\"><w:abstractNumId w:val=\"")
                .append(NUMBER_ABSTRACT).append("\"/>");
            for (int l = 0; l < 9; l++) {
                nums.append("<w:lvlOverride w:ilvl=\"").append(l).append("\"><w:startOverride w:val=\"1\"/></w:lvlOverride>");
            }
            nums.append("</w:num>");
            return id;
        }

        String xml() {
            String[] bullets = {"•", "◦", "▪"};
            StringBuilder sb = new StringBuilder();
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
              .append("<w:numbering ").append(NS).append(">");
            sb.append("<w:abstractNum w:abstractNumId=\"").append(BULLET_ABSTRACT).append("\"><w:multiLevelType w:val=\"hybridMultilevel\"/>");
            for (int l = 0; l < 9; l++) {
                sb.append("<w:lvl w:ilvl=\"").append(l).append("\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/>")
                  .append("<w:lvlText w:val=\"").append(bullets[l % 3]).append("\"/><w:lvlJc w:val=\"left\"/>")
                  .append("<w:pPr><w:ind w:left=\"").append(720 * (l + 1)).append("\" w:hanging=\"360\"/></w:pPr></w:lvl>");
            }
            sb.append("</w:abstractNum>");
            sb.append("<w:abstractNum w:abstractNumId=\"").append(NUMBER_ABSTRACT).append("\"><w:multiLevelType w:val=\"hybridMultilevel\"/>");
            for (int l = 0; l < 9; l++) {
                sb.append("<w:lvl w:ilvl=\"").append(l).append("\"><w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/>")
                  .append("<w:lvlText w:val=\"%").append(l + 1).append(".\"/><w:lvlJc w:val=\"left\"/>")
                  .append("<w:pPr><w:ind w:left=\"").append(720 * (l + 1)).append("\" w:hanging=\"360\"/></w:pPr></w:lvl>");
            }
            sb.append("</w:abstractNum>");
            sb.append("<w:num w:numId=\"").append(BULLET_NUM).append("\"><w:abstractNumId w:val=\"")
              .append(BULLET_ABSTRACT).append("\"/></w:num>");
            sb.append(nums);
            sb.append("</w:numbering>");
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- styles.xml for new documents

    private static String ownStyles() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
          .append("<w:styles ").append(NS).append(">")
          .append("<w:docDefaults><w:rPrDefault><w:rPr>")
          .append("<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/>")
          .append("<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"en-GB\"/>")
          .append("</w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>")
          .append("<w:spacing w:after=\"160\" w:line=\"259\" w:lineRule=\"auto\"/>")
          .append("</w:pPr></w:pPrDefault></w:docDefaults>")
          .append("<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>");
        for (Map.Entry<String, String> e : OWN_IDS.entrySet()) sb.append(styleDefinition(e.getKey(), e.getValue()));
        sb.append("</w:styles>");
        return sb.toString();
    }

    private static String styleDefinition(String semantic, String id) {
        String name, pPr, rPr;
        switch (semantic) {
            case DocModel.STYLE_TITLE:
                name = "Title"; pPr = "<w:spacing w:after=\"80\"/>"; rPr = "<w:sz w:val=\"56\"/><w:szCs w:val=\"56\"/>"; break;
            case DocModel.STYLE_H1:
                name = "heading 1"; pPr = "<w:keepNext/><w:spacing w:before=\"360\" w:after=\"80\"/><w:outlineLvl w:val=\"0\"/>";
                rPr = "<w:b/><w:bCs/><w:sz w:val=\"36\"/><w:szCs w:val=\"36\"/>"; break;
            case DocModel.STYLE_H2:
                name = "heading 2"; pPr = "<w:keepNext/><w:spacing w:before=\"160\" w:after=\"80\"/><w:outlineLvl w:val=\"1\"/>";
                rPr = "<w:b/><w:bCs/><w:sz w:val=\"30\"/><w:szCs w:val=\"30\"/>"; break;
            case DocModel.STYLE_H3:
                name = "heading 3"; pPr = "<w:keepNext/><w:spacing w:before=\"160\" w:after=\"80\"/><w:outlineLvl w:val=\"2\"/>";
                rPr = "<w:b/><w:bCs/><w:sz w:val=\"26\"/><w:szCs w:val=\"26\"/>"; break;
            default:
                name = "Quote"; pPr = "<w:spacing w:before=\"200\" w:after=\"160\"/><w:ind w:left=\"864\" w:right=\"864\"/><w:jc w:val=\"center\"/>";
                rPr = "<w:i/><w:iCs/>"; break;
        }
        return "<w:style w:type=\"paragraph\" w:styleId=\"" + attr(id) + "\"><w:name w:val=\"" + name
            + "\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/><w:pPr>" + pPr
            + "</w:pPr><w:rPr>" + rPr + "</w:rPr></w:style>";
    }

    // ---------------------------------------------------------------- escaping

    /** Escapes text for XML and removes characters XML 1.0 does not allow. */
    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default:
                    if (c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD)) {
                        sb.append(c);
                    } else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        sb.append(c).append(s.charAt(++i)); // keep emoji and other supplementary characters
                    }
                    // else: drop the invalid control character
            }
        }
        return sb.toString();
    }

    private static String attr(String s) {
        return escape(s);
    }
}
