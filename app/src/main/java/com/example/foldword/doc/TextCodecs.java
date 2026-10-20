package com.example.foldword.doc;

import com.example.foldword.doc.DocModel.Block;
import com.example.foldword.doc.DocModel.Document;
import com.example.foldword.doc.DocModel.Paragraph;
import com.example.foldword.doc.DocModel.Run;
import com.example.foldword.doc.DocModel.Table;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plain text (.txt) and Markdown (.md) reading and writing. */
public final class TextCodecs {

    private TextCodecs() {}

    // ---------------------------------------------------------------- decoding

    /** Decodes UTF-8 (with or without BOM) or UTF-16 with BOM; falls back to Windows-1252. */
    public static String decode(byte[] data) {
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE);
        }
        int off = (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, off, data.length - off)).toString();
        } catch (CharacterCodingException e) {
            return new String(data, off, data.length - off, Charset.forName("windows-1252"));
        }
    }

    private static List<String> lines(String text) {
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= t.length(); i++) {
            if (i == t.length() || t.charAt(i) == '\n') {
                out.add(t.substring(start, i));
                start = i + 1;
            }
        }
        // A file ending with a newline does not get an extra empty paragraph.
        if (out.size() > 1 && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    // ---------------------------------------------------------------- plain text

    /** Every line becomes one paragraph, so saving gives back exactly the same lines. */
    public static Document readText(byte[] data) {
        Document d = new Document();
        for (String line : lines(decode(data))) d.blocks.add(Paragraph.of(line));
        if (d.blocks.isEmpty()) d.blocks.add(new Paragraph());
        return d;
    }

    public static byte[] writeText(Document d) {
        StringBuilder sb = new StringBuilder();
        int[] counters = new int[9];
        for (Block b : d.blocks) {
            if (b instanceof Paragraph) {
                Paragraph p = (Paragraph) b;
                sb.append(listPrefixText(p, counters)).append(p.plainText().replace("\n", "\n")).append('\n');
            } else if (b instanceof Table) {
                java.util.Arrays.fill(counters, 0);
                for (List<List<Paragraph>> row : ((Table) b).rows) {
                    List<String> cells = new ArrayList<>();
                    for (List<Paragraph> cell : row) cells.add(cellText(cell, " "));
                    sb.append(String.join("\t", cells)).append('\n');
                }
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String listPrefixText(Paragraph p, int[] counters) {
        if (DocModel.LIST_NONE.equals(p.list)) { java.util.Arrays.fill(counters, 0); return ""; }
        String indent = repeat("  ", p.level);
        if (DocModel.LIST_BULLET.equals(p.list)) return indent + "• ";
        counters[p.level]++;
        for (int l = p.level + 1; l < counters.length; l++) counters[l] = 0;
        return indent + counters[p.level] + ". ";
    }

    private static String cellText(List<Paragraph> cell, String joiner) {
        List<String> parts = new ArrayList<>();
        for (Paragraph p : cell) parts.add(p.plainText().replace('\n', ' ').replace('\t', ' '));
        return String.join(joiner, parts);
    }

    // ---------------------------------------------------------------- Markdown

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)\\d{1,9}[.)]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^>\\s?(.*)$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$");

    /**
     * Reads the common Markdown subset: headings, bullet and numbered lists, block quotes, pipe tables,
     * **bold**, *italic*, ~~strikethrough~~ and &lt;u&gt;underline&lt;/u&gt;. Other Markdown is kept as text.
     */
    public static Document readMarkdown(byte[] data) {
        Document d = new Document();
        List<String> ls = lines(decode(data));
        Paragraph open = null; // paragraph collecting soft-wrapped lines
        for (int i = 0; i < ls.size(); i++) {
            String line = ls.get(i);
            if (line.trim().isEmpty()) { open = null; continue; }

            // Pipe table: header row followed by a separator row.
            if (line.contains("|") && i + 1 < ls.size() && TABLE_SEP.matcher(ls.get(i + 1)).matches()) {
                Table t = new Table();
                t.rows.add(tableRow(line));
                i += 2;
                while (i < ls.size() && ls.get(i).contains("|") && !ls.get(i).trim().isEmpty()) t.rows.add(tableRow(ls.get(i++)));
                i--;
                d.blocks.add(t);
                open = null;
                continue;
            }
            Matcher m;
            if ((m = HEADING.matcher(line)).matches()) {
                Paragraph p = inline(m.group(2));
                int n = m.group(1).length();
                p.style = n == 1 ? DocModel.STYLE_H1 : n == 2 ? DocModel.STYLE_H2 : DocModel.STYLE_H3;
                d.blocks.add(p); open = null; continue;
            }
            if ((m = BULLET.matcher(line)).matches() && !line.trim().matches("[-*_]{3,}")) {
                Paragraph p = inline(m.group(2));
                p.list = DocModel.LIST_BULLET;
                p.level = Math.min(8, indentLevel(m.group(1)));
                d.blocks.add(p); open = null; continue;
            }
            if ((m = NUMBERED.matcher(line)).matches()) {
                Paragraph p = inline(m.group(2));
                p.list = DocModel.LIST_NUMBER;
                p.level = Math.min(8, indentLevel(m.group(1)));
                d.blocks.add(p); open = null; continue;
            }
            if ((m = QUOTE.matcher(line)).matches()) {
                if (open != null && DocModel.STYLE_QUOTE.equals(open.style)) {
                    appendLine(open, m.group(1));
                } else {
                    Paragraph p = inline(m.group(1));
                    p.style = DocModel.STYLE_QUOTE;
                    d.blocks.add(p); open = p;
                }
                continue;
            }
            if (open != null && DocModel.STYLE_NORMAL.equals(open.style)) {
                appendLine(open, line); // soft-wrapped line: keep it as a line break, so saving keeps the layout
            } else {
                Paragraph p = inline(line);
                d.blocks.add(p); open = p;
            }
        }
        if (d.blocks.isEmpty()) d.blocks.add(new Paragraph());
        return d;
    }

    private static void appendLine(Paragraph p, String line) {
        Paragraph extra = inline(line);
        p.runs.add(new Run("\n"));
        p.runs.addAll(extra.runs);
    }

    private static int indentLevel(String spaces) {
        int n = 0;
        for (char c : spaces.toCharArray()) n += c == '\t' ? 4 : 1;
        return n / 2;
    }

    private static List<List<Paragraph>> tableRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.substring(0, t.length() - 1);
        List<List<Paragraph>> row = new ArrayList<>();
        for (String cell : t.split("(?<!\\\\)\\|", -1)) {
            List<Paragraph> c = new ArrayList<>();
            c.add(inline(cell.trim().replace("\\|", "|")));
            row.add(c);
        }
        return row;
    }

    private static final Pattern INLINE = Pattern.compile(
        "(\\*\\*|__)(.+?)\\1|~~(.+?)~~|<u>(.+?)</u>|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\*)|(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)|\\\\([\\\\`*_{}\\[\\]()#+\\-.!|~<>])");

    /** Parses inline emphasis. Nested emphasis such as ***bold italic*** is supported. */
    static Paragraph inline(String text) {
        Paragraph p = new Paragraph();
        addInline(p, text, false, false, false, false);
        return p;
    }

    private static void addInline(Paragraph p, String text, boolean b, boolean i, boolean u, boolean s) {
        Matcher m = INLINE.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) add(p, text.substring(last, m.start()), b, i, u, s);
            if (m.group(2) != null) addInline(p, m.group(2), true, i, u, s);
            else if (m.group(3) != null) addInline(p, m.group(3), b, i, u, true);
            else if (m.group(4) != null) addInline(p, m.group(4), b, i, true, s);
            else if (m.group(5) != null) addInline(p, m.group(5), b, true, u, s);
            else if (m.group(6) != null) addInline(p, m.group(6), b, true, u, s);
            else if (m.group(7) != null) add(p, m.group(7), b, i, u, s);
            last = m.end();
        }
        if (last < text.length()) add(p, text.substring(last), b, i, u, s);
    }

    private static void add(Paragraph p, String text, boolean b, boolean i, boolean u, boolean s) {
        if (text.isEmpty()) return;
        Run r = new Run(text);
        r.bold = b; r.italic = i; r.underline = u; r.strike = s;
        if (!p.runs.isEmpty() && p.runs.get(p.runs.size() - 1).sameFormat(r)) p.runs.get(p.runs.size() - 1).text += text;
        else p.runs.add(r);
    }

    public static byte[] writeMarkdown(Document d) {
        StringBuilder sb = new StringBuilder();
        int[] counters = new int[9];
        String prevKind = "";
        for (Block b : d.blocks) {
            if (b instanceof Paragraph) {
                Paragraph p = (Paragraph) b;
                boolean isList = !DocModel.LIST_NONE.equals(p.list);
                // A bullet list followed directly by a numbered list is a new list: separate it.
                String kind = isList ? (p.level == 0 ? "list:" + p.list : prevKind.startsWith("list") ? prevKind : "list:" + p.list) : "para";
                if (sb.length() > 0 && !(isList && kind.equals(prevKind))) sb.append('\n');
                if (!isList) java.util.Arrays.fill(counters, 0);
                String prefix;
                if (isList) {
                    String indent = repeat("  ", p.level);
                    if (DocModel.LIST_BULLET.equals(p.list)) prefix = indent + "- ";
                    else {
                        counters[p.level]++;
                        for (int l = p.level + 1; l < 9; l++) counters[l] = 0;
                        prefix = indent + counters[p.level] + ". ";
                    }
                } else {
                    switch (p.style) {
                        case DocModel.STYLE_TITLE: case DocModel.STYLE_H1: prefix = "# "; break;
                        case DocModel.STYLE_H2: prefix = "## "; break;
                        case DocModel.STYLE_H3: prefix = "### "; break;
                        case DocModel.STYLE_QUOTE: prefix = "> "; break;
                        default: prefix = "";
                    }
                }
                String body = runsToMarkdown(p.runs, false);
                if (DocModel.STYLE_QUOTE.equals(p.style)) body = body.replace("\n", "\n> ");
                sb.append(prefix).append(body).append('\n');
                prevKind = kind;
            } else if (b instanceof Table) {
                Table t = (Table) b;
                if (sb.length() > 0) sb.append('\n');
                int cols = Math.max(1, t.columnCount());
                for (int r = 0; r < t.rows.size(); r++) {
                    List<List<Paragraph>> row = t.rows.get(r);
                    sb.append('|');
                    for (int c = 0; c < cols; c++) {
                        String cell = "";
                        if (c < row.size()) {
                            List<String> parts = new ArrayList<>();
                            for (Paragraph p : row.get(c)) parts.add(runsToMarkdown(p.runs, true));
                            cell = String.join("<br>", parts);
                        }
                        sb.append(' ').append(cell).append(" |");
                    }
                    sb.append('\n');
                    if (r == 0) {
                        sb.append('|');
                        for (int c = 0; c < cols; c++) sb.append(" --- |");
                        sb.append('\n');
                    }
                }
                prevKind = "table";
                java.util.Arrays.fill(counters, 0);
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String runsToMarkdown(List<Run> runs, boolean inTable) {
        StringBuilder sb = new StringBuilder();
        for (Run r : runs) {
            String t = escapeMarkdown(r.text, inTable);
            if (inTable) t = t.replace("\n", "<br>");
            if (t.trim().isEmpty()) { sb.append(t); continue; }
            // Keep surrounding spaces outside the markers, otherwise Markdown ignores them.
            int a = 0, z = t.length();
            while (a < z && t.charAt(a) == ' ') a++;
            while (z > a && t.charAt(z - 1) == ' ') z--;
            String core = t.substring(a, z);
            if (r.underline) core = "<u>" + core + "</u>";
            if (r.strike) core = "~~" + core + "~~";
            if (r.italic) core = "*" + core + "*";
            if (r.bold) core = "**" + core + "**";
            sb.append(t, 0, a).append(core).append(t.substring(z));
        }
        return sb.toString();
    }

    private static String escapeMarkdown(String s, boolean inTable) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean lineStart = i == 0 || s.charAt(i - 1) == '\n';
            if (c == '\\' || c == '*' || c == '_' || c == '~' || c == '`' || (c == '|' && inTable)
                || (c == '<' && i + 1 < s.length() && (s.charAt(i + 1) == 'u' || s.charAt(i + 1) == '/'))
                || (lineStart && (c == '#' || c == '>' || c == '-' || c == '+'))) {
                sb.append('\\');
            }
            sb.append(c);
        }
        String out = sb.toString();
        // "1. text" at a line start would become a list; escape the dot.
        return out.replaceAll("(?m)^(\\d+)\\.(\\s)", "$1\\\\.$2");
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
