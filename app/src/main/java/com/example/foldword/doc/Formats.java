package com.example.foldword.doc;

import com.example.foldword.doc.DocModel.Block;
import com.example.foldword.doc.DocModel.Document;
import com.example.foldword.doc.DocModel.Paragraph;
import com.example.foldword.doc.DocModel.Run;
import com.example.foldword.doc.DocModel.Table;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The file formats Fold Word knows, what it can do with each, and the conversions between them. */
public enum Formats {
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word document",
        true, true),
    TXT("txt", "text/plain", "Plain text", true, true),
    MARKDOWN("md", "text/markdown", "Markdown", true, true),
    PDF("pdf", "application/pdf", "PDF (view only)", false, false);

    public final String extension;
    public final String mimeType;
    public final String label;
    public final boolean canEdit;
    public final boolean canSave;

    Formats(String ext, String mime, String label, boolean canEdit, boolean canSave) {
        this.extension = ext; this.mimeType = mime; this.label = label;
        this.canEdit = canEdit; this.canSave = canSave;
    }

    /** MIME types offered in the system file picker. */
    public static String[] pickerMimeTypes() {
        return new String[] {DOCX.mimeType, "text/plain", "text/markdown", "text/x-markdown", "application/pdf",
            "application/octet-stream"};
    }

    /** Detects a format from a file name first, then from the MIME type. Returns null if unsupported. */
    public static Formats detect(String name, String mime) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".docx")) return DOCX;
        if (n.endsWith(".txt") || n.endsWith(".text") || n.endsWith(".log") || n.endsWith(".csv")) return TXT;
        if (n.endsWith(".md") || n.endsWith(".markdown")) return MARKDOWN;
        if (n.endsWith(".pdf")) return PDF;
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.equals(DOCX.mimeType)) return DOCX;
        if (m.equals("text/markdown") || m.equals("text/x-markdown")) return MARKDOWN;
        if (m.equals("application/pdf")) return PDF;
        if (m.startsWith("text/")) return TXT;
        return null;
    }

    /** Result of opening a file: the document plus anything that could not be represented. */
    public static final class Loaded {
        public final Document document;
        public final Set<String> lostFeatures;
        public final Map<String, byte[]> reusableParts;
        public final Map<String, String> styleIds;

        Loaded(Document d, Set<String> lost, Map<String, byte[]> parts, Map<String, String> ids) {
            document = d; lostFeatures = lost; reusableParts = parts; styleIds = ids;
        }
    }

    public Loaded read(byte[] data) throws IOException {
        switch (this) {
            case DOCX: {
                DocxReader.Result r = DocxReader.read(new ByteArrayInputStream(data));
                return new Loaded(r.document, r.lostFeatures, r.reusableParts, r.styleIds);
            }
            case TXT: return new Loaded(TextCodecs.readText(data), new LinkedHashSet<>(), null, null);
            case MARKDOWN: return new Loaded(TextCodecs.readMarkdown(data), new LinkedHashSet<>(), null, null);
            default: throw new IOException(label + " files can only be viewed, not edited.");
        }
    }

    public byte[] write(Document d, Map<String, byte[]> reuse, Map<String, String> ids) throws IOException {
        switch (this) {
            case DOCX: return DocxWriter.write(d, reuse, ids);
            case TXT: return TextCodecs.writeText(d);
            case MARKDOWN: return TextCodecs.writeMarkdown(d);
            default: throw new IOException(label + " files cannot be saved by Fold Word.");
        }
    }

    /**
     * Lists formatting in the document that this format cannot store. Used to warn before saving, for
     * example when a formatted document is saved as plain text.
     */
    public List<String> unsupportedIn(Document d) {
        Set<String> out = new LinkedHashSet<>();
        if (this == DOCX) return new ArrayList<>(out);
        for (Block b : d.blocks) {
            if (b instanceof Table) {
                if (this == TXT) out.add("Tables (saved as tab-separated lines)");
                for (List<List<Paragraph>> row : ((Table) b).rows)
                    for (List<Paragraph> cell : row)
                        for (Paragraph p : cell) collect(p, out, true);
            } else {
                collect((Paragraph) b, out, false);
            }
        }
        return new ArrayList<>(out);
    }

    private void collect(Paragraph p, Set<String> out, boolean inTable) {
        if (!DocModel.ALIGN_LEFT.equals(p.align)) out.add("Text alignment");
        if (this == TXT) {
            if (!DocModel.STYLE_NORMAL.equals(p.style)) out.add("Headings, title and quote styles");
            if (!DocModel.LIST_NONE.equals(p.list)) out.add("Lists (saved as • and 1. text)");
        } else if (this == MARKDOWN) {
            if (DocModel.STYLE_TITLE.equals(p.style)) out.add("Title style (saved as Heading 1)");
            if (inTable && (!DocModel.STYLE_NORMAL.equals(p.style) || !DocModel.LIST_NONE.equals(p.list))) {
                out.add("Headings and lists inside table cells");
            }
        }
        for (Run r : p.runs) {
            if (this == TXT && (r.bold || r.italic || r.underline || r.strike)) out.add("Bold, italic, underline and strikethrough");
            if (r.sizePt != 0) out.add("Font sizes");
        }
    }

    /** Suggests a file name with the right extension. */
    public String fileName(String base) {
        String b = base == null || base.trim().isEmpty() ? "Document" : base.trim();
        b = b.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        int dot = b.lastIndexOf('.');
        if (dot > 0 && detect(b, null) != null) b = b.substring(0, dot);
        if (b.length() > 120) b = b.substring(0, 120);
        return b + "." + extension;
    }
}
