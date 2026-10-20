package com.example.foldword.doc;

import java.util.ArrayList;
import java.util.List;

/**
 * Fold Word's own document model. It deliberately holds only what the offline editor can show and
 * edit reliably, so nothing is "half supported". Anything a source file contains beyond this model is
 * reported by the readers as a lost feature, never dropped silently.
 */
public final class DocModel {

    private DocModel() {}

    /** A whole document: an ordered list of paragraphs and tables. */
    public static final class Document {
        public final List<Block> blocks = new ArrayList<>();
        /** Page size and margins copied from a DOCX file, or null for defaults. */
        public PageSetup page;

        public boolean isEmpty() {
            for (Block b : blocks) {
                if (b instanceof Paragraph && !((Paragraph) b).plainText().isEmpty()) return false;
                if (b instanceof Table) return false;
            }
            return true;
        }
    }

    public interface Block {}

    /** Paragraph-level styles the editor understands. */
    public static final String STYLE_NORMAL = "normal";
    public static final String STYLE_TITLE = "title";
    public static final String STYLE_H1 = "h1";
    public static final String STYLE_H2 = "h2";
    public static final String STYLE_H3 = "h3";
    public static final String STYLE_QUOTE = "quote";

    public static final String ALIGN_LEFT = "left";
    public static final String ALIGN_CENTER = "center";
    public static final String ALIGN_RIGHT = "right";
    public static final String ALIGN_JUSTIFY = "justify";

    public static final String LIST_NONE = "none";
    public static final String LIST_BULLET = "bullet";
    public static final String LIST_NUMBER = "number";

    public static final class Paragraph implements Block {
        public String style = STYLE_NORMAL;
        public String align = ALIGN_LEFT;
        public String list = LIST_NONE;
        /** List nesting level, 0 to 8. */
        public int level = 0;
        public final List<Run> runs = new ArrayList<>();

        public String plainText() {
            StringBuilder sb = new StringBuilder();
            for (Run r : runs) sb.append(r.text);
            return sb.toString();
        }

        public static Paragraph of(String text) {
            Paragraph p = new Paragraph();
            if (!text.isEmpty()) p.runs.add(new Run(text));
            return p;
        }
    }

    /** A piece of text with one set of character formatting. "\n" is a line break, "\t" a tab. */
    public static final class Run {
        public String text;
        public boolean bold, italic, underline, strike;
        /** Font size in points, or 0 for the paragraph default. */
        public int sizePt;

        public Run(String text) { this.text = text; }

        public boolean sameFormat(Run o) {
            return bold == o.bold && italic == o.italic && underline == o.underline
                && strike == o.strike && sizePt == o.sizePt;
        }

        public boolean hasFormatting() { return bold || italic || underline || strike || sizePt != 0; }
    }

    /** A simple grid table. Each cell holds one or more paragraphs. */
    public static final class Table implements Block {
        public final List<List<List<Paragraph>>> rows = new ArrayList<>();
        /** Column widths in twentieths of a point, copied from DOCX when the column count still matches. */
        public final List<Integer> columnWidths = new ArrayList<>();
        /** Original DOCX table style id, kept so a saved table looks the same. */
        public String styleId;

        public int columnCount() {
            int n = 0;
            for (List<List<Paragraph>> r : rows) n = Math.max(n, r.size());
            return n;
        }
    }

    /** DOCX page size and margins, in twentieths of a point. */
    public static final class PageSetup {
        public int width = 11906, height = 16838; // A4
        public boolean landscape;
        public int top = 1440, right = 1440, bottom = 1440, left = 1440, header = 708, footer = 708, gutter = 0;
    }
}
