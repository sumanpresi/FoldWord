package com.example.foldword.doc;

import com.example.foldword.doc.DocModel.Document;
import com.example.foldword.doc.DocModel.PageSetup;
import com.example.foldword.doc.DocModel.Paragraph;
import com.example.foldword.doc.DocModel.Run;
import com.example.foldword.doc.DocModel.Table;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Reads .docx (Office Open XML) files into {@link DocModel}. Uses only the ZIP and XML support built
 * into Android and Java. Every element it cannot represent is named in {@link Result#lostFeatures}, so
 * the app can warn before saving and refuse to overwrite the original.
 */
public final class DocxReader {

    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String MC = "http://schemas.openxmlformats.org/markup-compatibility/2006";

    /** Limits that protect against corrupt files and "zip bombs". */
    static final int MAX_ENTRIES = 5000;
    static final long MAX_ENTRY_BYTES = 80L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 300L * 1024 * 1024;

    public static final class Result {
        public final Document document;
        /** Human-readable names of content that the offline editor cannot keep. Empty = full fidelity. */
        public final Set<String> lostFeatures;
        /** Original styles, theme and font table, reused when saving so the look stays the same. */
        public final Map<String, byte[]> reusableParts;
        /** Semantic style -> the original document's style id (for example "h1" -> "Heading1"). */
        public final Map<String, String> styleIds;

        Result(Document d, Set<String> lost, Map<String, byte[]> parts, Map<String, String> ids) {
            document = d; lostFeatures = lost; reusableParts = parts; styleIds = ids;
        }
    }

    public static final class DocxException extends IOException {
        public DocxException(String m) { super(m); }
        public DocxException(String m, Throwable t) { super(m, t); }
    }

    // --- lost-feature labels (shown to the user) ---
    public static final String LOST_IMAGES = "Pictures, drawings and embedded objects";
    public static final String LOST_HEADERS = "Headers and footers";
    public static final String LOST_FOOTNOTES = "Footnotes and endnotes";
    public static final String LOST_COMMENTS = "Comments";
    public static final String LOST_TRACKED = "Tracked changes (insertions are kept as normal text; deletions are removed)";
    public static final String LOST_TEXTBOXES = "Text boxes and shapes";
    public static final String LOST_FIELDS = "Fields (page numbers, tables of contents, dates)";
    public static final String LOST_LINKS = "Hyperlinks (the text is kept)";
    public static final String LOST_FONTS = "Font names, colours and highlighting";
    public static final String LOST_CHAR_EFFECTS = "Superscript, subscript and other character effects";
    public static final String LOST_PARA_SPACING = "Paragraph spacing, indentation and tab stops";
    public static final String LOST_PARA_BORDERS = "Paragraph borders and shading";
    public static final String LOST_CUSTOM_STYLES = "Custom paragraph styles (saved as Normal text)";
    public static final String LOST_HEADING_LEVELS = "Heading levels 4 to 9 (saved as Heading 3)";
    public static final String LOST_LIST_FORMATS = "Letter and Roman-numeral list numbering (saved as 1, 2, 3)";
    public static final String LOST_MERGED_CELLS = "Merged table cells";
    public static final String LOST_NESTED_TABLES = "Tables inside tables (flattened)";
    public static final String LOST_CELL_SHADING = "Table cell shading and borders";
    public static final String LOST_SECTIONS = "Section breaks and columns";
    public static final String LOST_PAGE_BREAKS = "Page breaks (saved as line breaks)";
    public static final String LOST_BOOKMARKS = "Bookmarks";
    public static final String LOST_SYMBOLS = "Special symbol characters";
    public static final String LOST_MATH = "Equations";
    public static final String LOST_MACROS = "Macros";
    public static final String LOST_CONTROLS = "Content controls and form fields (the text is kept)";

    private final Set<String> lost = new LinkedHashSet<>();
    private final Map<String, String> styleToSemantic = new HashMap<>();
    private final Map<String, String> semanticToStyle = new LinkedHashMap<>();
    private final Map<String, String> styleNumId = new HashMap<>();
    private final Map<String, String> numToAbstract = new HashMap<>();
    private final Map<String, Map<Integer, String>> abstractFormats = new HashMap<>();

    private DocxReader() {}

    public static Result read(InputStream in) throws IOException {
        return new DocxReader().run(in);
    }

    private Result run(InputStream in) throws IOException {
        Map<String, byte[]> parts = unzip(in);
        if (parts.isEmpty()) {
            throw new DocxException("This is not a .docx file, or it is password-protected. "
                + "Old .doc files and encrypted documents are not supported offline.");
        }
        String mainPath = findMainDocument(parts);
        byte[] main = parts.get(mainPath);
        if (main == null) throw new DocxException("This file does not contain a Word document body.");

        for (String name : parts.keySet()) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.contains("vbaproject")) lost.add(LOST_MACROS);
            if (n.startsWith("word/media/") || n.startsWith("word/embeddings/")) lost.add(LOST_IMAGES);
        }

        String dir = mainPath.contains("/") ? mainPath.substring(0, mainPath.lastIndexOf('/') + 1) : "";
        byte[] styles = parts.get(dir + "styles.xml");
        if (styles != null) parseStyles(parse(styles));
        byte[] numbering = parts.get(dir + "numbering.xml");
        if (numbering != null) parseNumbering(parse(numbering));

        Document doc = new Document();
        org.w3c.dom.Document xml = parse(main);
        Element body = child(xml.getDocumentElement(), "body");
        if (body == null) throw new DocxException("The document body is missing.");
        readBlocks(body, doc.blocks, false);
        if (doc.blocks.isEmpty()) doc.blocks.add(new Paragraph());
        doc.page = page;

        Map<String, byte[]> reusable = new LinkedHashMap<>();
        if (styles != null) reusable.put("word/styles.xml", styles);
        byte[] theme = parts.get(dir + "theme/theme1.xml");
        if (theme != null) reusable.put("word/theme/theme1.xml", theme);
        byte[] fonts = parts.get(dir + "fontTable.xml");
        if (fonts != null) reusable.put("word/fontTable.xml", fonts);

        return new Result(doc, lost, reusable, semanticToStyle);
    }

    // ---------------------------------------------------------------- package

    static Map<String, byte[]> unzip(InputStream in) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        int count = 0;
        try (ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = zin.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) throw new DocxException("The file has too many parts to open safely.");
                String name = e.getName().replace('\\', '/');
                while (name.startsWith("/")) name = name.substring(1);
                if (e.isDirectory() || name.contains("..")) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                long size = 0;
                while ((n = zin.read(buf)) > 0) {
                    size += n;
                    total += n;
                    if (size > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                        throw new DocxException("The file is too large to open safely offline.");
                    }
                    bos.write(buf, 0, n);
                }
                out.put(name, bos.toByteArray());
            }
        } catch (java.util.zip.ZipException ze) {
            throw new DocxException("This file is damaged or is not a .docx document.", ze);
        }
        return out;
    }

    private String findMainDocument(Map<String, byte[]> parts) throws IOException {
        byte[] rels = parts.get("_rels/.rels");
        if (rels != null) {
            org.w3c.dom.Document x = parse(rels);
            for (Node n = x.getDocumentElement().getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element)) continue;
                Element r = (Element) n;
                if (r.getAttribute("Type").endsWith("/officeDocument")) {
                    String t = r.getAttribute("Target");
                    while (t.startsWith("/")) t = t.substring(1);
                    if (!t.contains("..")) return t;
                }
            }
        }
        return "word/document.xml";
    }

    static org.w3c.dom.Document parse(byte[] xml) throws IOException {
        // DOCX never needs a DOCTYPE. Refusing one blocks entity-expansion and external-entity attacks.
        String head = new String(xml, 0, Math.min(xml.length, 4096), StandardCharsets.ISO_8859_1)
            .toUpperCase(Locale.ROOT);
        if (head.contains("<!DOCTYPE") || head.contains("<!ENTITY")) {
            throw new DocxException("This file contains unsafe XML and was not opened.");
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setExpandEntityReferences(false);
            trySetFeature(f, "http://apache.org/xml/features/disallow-doctype-decl", true);
            trySetFeature(f, "http://xml.org/sax/features/external-general-entities", false);
            trySetFeature(f, "http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new DocxException("Part of this document could not be read.", e);
        }
    }

    private static void trySetFeature(DocumentBuilderFactory f, String name, boolean value) {
        try { f.setFeature(name, value); } catch (Exception ignored) { /* not all parsers support it */ }
    }

    // ---------------------------------------------------------------- styles and numbering

    private void parseStyles(org.w3c.dom.Document x) {
        for (Element s : children(x.getDocumentElement(), "style")) {
            if (!"paragraph".equals(attr(s, "type"))) continue;
            String id = attr(s, "styleId");
            Element nameEl = child(s, "name");
            String name = nameEl == null ? id : attr(nameEl, "val");
            String sem = semanticFor(name == null ? "" : name.toLowerCase(Locale.ROOT).trim(), id);
            if (sem != null) {
                styleToSemantic.put(id, sem);
                if (!semanticToStyle.containsKey(sem) && !sem.startsWith("~")) semanticToStyle.put(sem, id);
            }
            Element pPr = child(s, "pPr");
            Element numPr = pPr == null ? null : child(pPr, "numPr");
            Element numId = numPr == null ? null : child(numPr, "numId");
            if (numId != null) styleNumId.put(id, attr(numId, "val"));
        }
    }

    /** Returns a semantic style, "~normal" for known harmless styles, "~h3" for deep headings, or null. */
    private static String semanticFor(String name, String id) {
        switch (name) {
            case "normal": case "list paragraph": case "no spacing": case "body text": case "default paragraph font":
                return "~normal";
            case "title": return DocModel.STYLE_TITLE;
            case "heading 1": return DocModel.STYLE_H1;
            case "heading 2": return DocModel.STYLE_H2;
            case "heading 3": return DocModel.STYLE_H3;
            case "quote": case "intense quote": return DocModel.STYLE_QUOTE;
            default:
                if (name.matches("heading [4-9]")) return "~h3";
                if (name.startsWith("list bullet") || name.startsWith("list number")) return "~normal";
                return null;
        }
    }

    private void parseNumbering(org.w3c.dom.Document x) {
        for (Element a : children(x.getDocumentElement(), "abstractNum")) {
            Map<Integer, String> lv = new HashMap<>();
            for (Element l : children(a, "lvl")) {
                Element fmt = child(l, "numFmt");
                lv.put(parseInt(attr(l, "ilvl"), 0), fmt == null ? "decimal" : attr(fmt, "val"));
            }
            abstractFormats.put(attr(a, "abstractNumId"), lv);
        }
        for (Element n : children(x.getDocumentElement(), "num")) {
            Element a = child(n, "abstractNumId");
            if (a != null) numToAbstract.put(attr(n, "numId"), attr(a, "val"));
        }
    }

    // ---------------------------------------------------------------- body

    private void readBlocks(Element container, List<DocModel.Block> out, boolean inTable) {
        for (Node n = container.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            if (!W.equals(e.getNamespaceURI())) {
                if (MC.equals(e.getNamespaceURI())) lost.add(LOST_TEXTBOXES);
                continue;
            }
            switch (e.getLocalName()) {
                case "p": out.add(readParagraph(e)); break;
                case "tbl":
                    if (inTable) {
                        lost.add(LOST_NESTED_TABLES);
                        flattenTable(e, out);
                    } else {
                        out.add(readTable(e));
                    }
                    break;
                case "sdt": {
                    lost.add(LOST_CONTROLS);
                    Element c = child(e, "sdtContent");
                    if (c != null) readBlocks(c, out, inTable);
                    break;
                }
                case "customXml": readBlocks(e, out, inTable); break;
                case "sectPr": readSection(e, true); break;
                case "bookmarkStart": case "bookmarkEnd": case "proofErr": break;
                case "commentRangeStart": case "commentRangeEnd": lost.add(LOST_COMMENTS); break;
                case "ins": case "del": case "moveFrom": case "moveTo":
                    lost.add(LOST_TRACKED);
                    if (!e.getLocalName().equals("del") && !e.getLocalName().equals("moveFrom")) readBlocks(e, out, inTable);
                    break;
                case "altChunk": lost.add(LOST_IMAGES); break;
                default: break;
            }
        }
    }

    private PageSetup page;

    private void readSection(Element sectPr, boolean last) {
        if (!last) lost.add(LOST_SECTIONS);
        for (Element c : children(sectPr)) {
            String ln = c.getLocalName();
            if ("headerReference".equals(ln) || "footerReference".equals(ln)) lost.add(LOST_HEADERS);
            if ("cols".equals(ln) && parseInt(attr(c, "num"), 1) > 1) lost.add(LOST_SECTIONS);
        }
        if (last) {
            PageSetup p = new PageSetup();
            Element sz = child(sectPr, "pgSz");
            if (sz != null) {
                p.width = parseInt(attr(sz, "w"), p.width);
                p.height = parseInt(attr(sz, "h"), p.height);
                p.landscape = "landscape".equals(attr(sz, "orient"));
            }
            Element m = child(sectPr, "pgMar");
            if (m != null) {
                p.top = Math.abs(parseInt(attr(m, "top"), p.top));
                p.right = parseInt(attr(m, "right"), p.right);
                p.bottom = Math.abs(parseInt(attr(m, "bottom"), p.bottom));
                p.left = parseInt(attr(m, "left"), p.left);
                p.header = parseInt(attr(m, "header"), p.header);
                p.footer = parseInt(attr(m, "footer"), p.footer);
                p.gutter = parseInt(attr(m, "gutter"), p.gutter);
            }
            page = p;
        }
    }

    private Paragraph readParagraph(Element p) {
        Paragraph para = new Paragraph();
        Element pPr = child(p, "pPr");
        String numId = null;
        int ilvl = 0;
        if (pPr != null) {
            for (Element c : children(pPr)) {
                switch (c.getLocalName()) {
                    case "pStyle": {
                        String id = attr(c, "val");
                        String sem = styleToSemantic.get(id);
                        if (sem == null && !styleToSemantic.isEmpty()) {
                            lost.add(LOST_CUSTOM_STYLES);
                        } else if (sem == null) {
                            sem = guessFromId(id);
                            if (sem == null) lost.add(LOST_CUSTOM_STYLES);
                        }
                        if ("~h3".equals(sem)) { lost.add(LOST_HEADING_LEVELS); para.style = DocModel.STYLE_H3; }
                        else if (sem != null && !sem.startsWith("~")) para.style = sem;
                        if (numId == null && styleNumId.containsKey(id)) numId = styleNumId.get(id);
                        break;
                    }
                    case "jc": para.align = alignFor(attr(c, "val")); break;
                    case "numPr": {
                        Element n = child(c, "numId");
                        Element l = child(c, "ilvl");
                        if (n != null) numId = attr(n, "val");
                        if (l != null) ilvl = parseInt(attr(l, "val"), 0);
                        break;
                    }
                    case "spacing": case "ind": case "tabs": case "contextualSpacing":
                        // Spacing that only says "no extra space" is harmless; anything else is reported.
                        if (!"contextualSpacing".equals(c.getLocalName())) lost.add(LOST_PARA_SPACING);
                        break;
                    case "pBdr": case "shd": lost.add(LOST_PARA_BORDERS); break;
                    case "sectPr": readSection(c, false); break;
                    case "pageBreakBefore": lost.add(LOST_PAGE_BREAKS); break;
                    case "framePr": lost.add(LOST_TEXTBOXES); break;
                    default: break; // keepNext, widowControl, rPr of the paragraph mark, rsid: no visible loss
                }
            }
        }
        if (numId != null && !"0".equals(numId)) {
            String fmt = listFormat(numId, ilvl);
            if ("bullet".equals(fmt)) para.list = DocModel.LIST_BULLET;
            else if (!"none".equals(fmt)) {
                para.list = DocModel.LIST_NUMBER;
                if (!"decimal".equals(fmt) && !"decimalZero".equals(fmt)) lost.add(LOST_LIST_FORMATS);
            }
            para.level = Math.max(0, Math.min(8, ilvl));
        }
        readInline(p, para);
        return para;
    }

    private static String guessFromId(String id) {
        String s = id.toLowerCase(Locale.ROOT);
        if (s.equals("title")) return DocModel.STYLE_TITLE;
        if (s.equals("heading1")) return DocModel.STYLE_H1;
        if (s.equals("heading2")) return DocModel.STYLE_H2;
        if (s.equals("heading3")) return DocModel.STYLE_H3;
        if (s.matches("heading[4-9]")) return "~h3";
        if (s.equals("quote") || s.equals("intensequote")) return DocModel.STYLE_QUOTE;
        if (s.equals("normal") || s.equals("listparagraph")) return "~normal";
        return null;
    }

    private String listFormat(String numId, int ilvl) {
        String abs = numToAbstract.get(numId);
        Map<Integer, String> lv = abs == null ? null : abstractFormats.get(abs);
        if (lv == null) return "bullet";
        String f = lv.get(ilvl);
        return f == null ? "decimal" : f;
    }

    private static String alignFor(String v) {
        switch (v) {
            case "center": return DocModel.ALIGN_CENTER;
            case "right": case "end": return DocModel.ALIGN_RIGHT;
            case "both": case "distribute": return DocModel.ALIGN_JUSTIFY;
            default: return DocModel.ALIGN_LEFT;
        }
    }

    /** Reads runs inside a paragraph, descending into links, tracked insertions, fields and controls. */
    private void readInline(Element container, Paragraph para) {
        for (Node n = container.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            if (!W.equals(e.getNamespaceURI())) {
                String ns = e.getNamespaceURI() == null ? "" : e.getNamespaceURI();
                if (MC.equals(ns)) lost.add(LOST_TEXTBOXES);
                else if (ns.contains("/math")) lost.add(LOST_MATH);
                continue;
            }
            switch (e.getLocalName()) {
                case "r": readRun(e, para); break;
                case "hyperlink": lost.add(LOST_LINKS); readInline(e, para); break;
                case "ins": case "moveTo": lost.add(LOST_TRACKED); readInline(e, para); break;
                case "del": case "moveFrom": lost.add(LOST_TRACKED); break;
                case "fldSimple": lost.add(LOST_FIELDS); readInline(e, para); break;
                case "sdt": {
                    lost.add(LOST_CONTROLS);
                    Element c = child(e, "sdtContent");
                    if (c != null) readInline(c, para);
                    break;
                }
                case "smartTag": case "customXml": case "dir": case "bdo": readInline(e, para); break;
                case "bookmarkStart":
                    if (!attr(e, "name").startsWith("_")) lost.add(LOST_BOOKMARKS);
                    break;
                case "commentRangeStart": case "commentRangeEnd": lost.add(LOST_COMMENTS); break;
                default: break; // pPr, proofErr, bookmarkEnd, permStart...
            }
        }
    }

    private boolean inFieldInstruction;

    private void readRun(Element r, Paragraph para) {
        Run fmt = new Run("");
        Element rPr = child(r, "rPr");
        if (rPr != null) {
            for (Element c : children(rPr)) {
                switch (c.getLocalName()) {
                    case "b": fmt.bold = on(c); break;
                    case "i": fmt.italic = on(c); break;
                    case "u": fmt.underline = !"none".equals(attr(c, "val")); break;
                    case "strike": case "dstrike": fmt.strike = on(c); break;
                    case "sz": {
                        int half = parseInt(attr(c, "val"), 0);
                        if (half > 0) fmt.sizePt = Math.max(1, Math.min(400, Math.round(half / 2f)));
                        break;
                    }
                    case "rFonts": case "color": case "highlight": case "shd":
                        if (!("color".equals(c.getLocalName()) && "auto".equals(attr(c, "val")))) lost.add(LOST_FONTS);
                        break;
                    case "vertAlign": case "caps": case "smallCaps": case "outline": case "shadow": case "emboss":
                    case "imprint": case "position": case "spacing": case "w": case "kern": case "effect":
                    case "em": case "bdr": case "vanish":
                        if (on(c)) lost.add(LOST_CHAR_EFFECTS);
                        break;
                    case "rStyle": {
                        String v = attr(c, "val");
                        String lv = v.toLowerCase(Locale.ROOT);
                        if (lv.contains("hyperlink")) lost.add(LOST_LINKS);
                        else if (lv.contains("comment")) lost.add(LOST_COMMENTS);
                        else if (lv.contains("footnote") || lv.contains("endnote")) lost.add(LOST_FOOTNOTES);
                        else lost.add(LOST_CHAR_EFFECTS);
                        break;
                    }
                    default: break; // bCs, iCs, szCs, lang, noProof, rtl...
                }
            }
        }
        StringBuilder text = new StringBuilder();
        for (Element c : children(r)) {
            switch (c.getLocalName()) {
                case "t": if (!inFieldInstruction) text.append(c.getTextContent()); break;
                case "tab": case "ptab": text.append('\t'); break;
                case "br":
                    if ("page".equals(attr(c, "type")) || "column".equals(attr(c, "type"))) lost.add(LOST_PAGE_BREAKS);
                    text.append('\n');
                    break;
                case "cr": text.append('\n'); break;
                case "noBreakHyphen": text.append('-'); break;
                case "softHyphen": break;
                case "lastRenderedPageBreak": break;
                case "sym": lost.add(LOST_SYMBOLS); break;
                case "drawing": case "pict": case "object": lost.add(LOST_IMAGES); break;
                case "footnoteReference": case "endnoteReference": lost.add(LOST_FOOTNOTES); break;
                case "commentReference": lost.add(LOST_COMMENTS); break;
                case "fldChar": {
                    lost.add(LOST_FIELDS);
                    String t = attr(c, "fldCharType");
                    if ("begin".equals(t)) inFieldInstruction = true;      // skip the field code itself
                    else if ("separate".equals(t) || "end".equals(t)) inFieldInstruction = false; // keep shown result
                    break;
                }
                case "instrText": lost.add(LOST_FIELDS); break;
                case "delText": break;
                default: break; // rPr handled above
            }
        }
        // Non-W content inside a run (for example mc:AlternateContent wrapping a shape).
        for (Node n = r.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && MC.equals(n.getNamespaceURI())) lost.add(LOST_TEXTBOXES);
        }
        if (text.length() == 0) return;
        fmt.text = text.toString();
        List<Run> runs = para.runs;
        if (!runs.isEmpty() && runs.get(runs.size() - 1).sameFormat(fmt)) runs.get(runs.size() - 1).text += fmt.text;
        else runs.add(fmt);
    }

    private Table readTable(Element tbl) {
        Table t = new Table();
        Element tblPr = child(tbl, "tblPr");
        if (tblPr != null) {
            Element st = child(tblPr, "tblStyle");
            if (st != null && attr(st, "val").matches("[A-Za-z0-9_\\- ]{1,80}")) t.styleId = attr(st, "val");
            if (child(tblPr, "shd") != null) lost.add(LOST_CELL_SHADING);
        }
        Element grid = child(tbl, "tblGrid");
        if (grid != null) {
            for (Element g : children(grid, "gridCol")) t.columnWidths.add(Math.max(100, parseInt(attr(g, "w"), 1000)));
        }
        for (Element tr : children(tbl, "tr")) {
            List<List<Paragraph>> row = new ArrayList<>();
            for (Element tc : children(tr, "tc")) {
                Element tcPr = child(tc, "tcPr");
                if (tcPr != null) {
                    Element span = child(tcPr, "gridSpan");
                    if ((span != null && parseInt(attr(span, "val"), 1) > 1) || child(tcPr, "vMerge") != null
                        || child(tcPr, "hMerge") != null) lost.add(LOST_MERGED_CELLS);
                    if (child(tcPr, "shd") != null || child(tcPr, "tcBorders") != null) lost.add(LOST_CELL_SHADING);
                }
                List<DocModel.Block> blocks = new ArrayList<>();
                readBlocks(tc, blocks, true);
                List<Paragraph> cell = new ArrayList<>();
                for (DocModel.Block b : blocks) if (b instanceof Paragraph) cell.add((Paragraph) b);
                if (cell.isEmpty()) cell.add(new Paragraph());
                row.add(cell);
            }
            if (!row.isEmpty()) t.rows.add(row);
        }
        if (t.columnWidths.size() != t.columnCount()) t.columnWidths.clear();
        return t;
    }

    /** A table nested in a table cell: keep its text as paragraphs in the outer cell. */
    private void flattenTable(Element tbl, List<DocModel.Block> out) {
        for (Element tr : children(tbl, "tr")) {
            for (Element tc : children(tr, "tc")) readBlocks(tc, out, true);
        }
    }

    // ---------------------------------------------------------------- small DOM helpers

    static Element child(Element parent, String localName) {
        if (parent == null) return null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && W.equals(n.getNamespaceURI()) && localName.equals(n.getLocalName())) {
                return (Element) n;
            }
        }
        return null;
    }

    static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && W.equals(n.getNamespaceURI()) && localName.equals(n.getLocalName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && W.equals(n.getNamespaceURI())) out.add((Element) n);
        }
        return out;
    }

    static String attr(Element e, String localName) {
        String v = e.getAttributeNS(W, localName);
        if (v == null || v.isEmpty()) v = e.getAttribute("w:" + localName);
        return v == null ? "" : v;
    }

    /** OOXML on/off value: missing or anything except false/0/off means on. */
    static boolean on(Element e) {
        String v = attr(e, "val");
        return !("false".equals(v) || "0".equals(v) || "off".equals(v) || "none".equals(v));
    }

    static int parseInt(String s, int def) {
        try {
            return s == null || s.isEmpty() ? def : (int) Math.round(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
