package com.example.foldword.doc;

import com.example.foldword.doc.DocModel.Block;
import com.example.foldword.doc.DocModel.Document;
import com.example.foldword.doc.DocModel.PageSetup;
import com.example.foldword.doc.DocModel.Paragraph;
import com.example.foldword.doc.DocModel.Run;
import com.example.foldword.doc.DocModel.Table;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts the document model to and from the JSON exchanged with the offline editor (assets/editor).
 * Unknown values are replaced by safe defaults, so a malformed message can never inject markup.
 */
public final class DocJson {

    private DocJson() {}

    private static final Set<String> STYLES = new HashSet<>(Arrays.asList(
        DocModel.STYLE_NORMAL, DocModel.STYLE_TITLE, DocModel.STYLE_H1, DocModel.STYLE_H2,
        DocModel.STYLE_H3, DocModel.STYLE_QUOTE));
    private static final Set<String> ALIGNS = new HashSet<>(Arrays.asList(
        DocModel.ALIGN_LEFT, DocModel.ALIGN_CENTER, DocModel.ALIGN_RIGHT, DocModel.ALIGN_JUSTIFY));
    private static final Set<String> LISTS = new HashSet<>(Arrays.asList(
        DocModel.LIST_NONE, DocModel.LIST_BULLET, DocModel.LIST_NUMBER));

    /** Hard limits so a corrupted draft cannot exhaust memory. */
    public static final int MAX_BLOCKS = 200_000;
    public static final int MAX_TABLE_CELLS = 100_000;

    public static String toJson(Document d) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("v", 1);
        List<Object> blocks = new ArrayList<>();
        for (Block b : d.blocks) {
            if (b instanceof Paragraph) blocks.add(para((Paragraph) b));
            else if (b instanceof Table) blocks.add(table((Table) b));
        }
        root.put("blocks", blocks);
        if (d.page != null) {
            PageSetup p = d.page;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("w", p.width); m.put("h", p.height); m.put("landscape", p.landscape);
            m.put("top", p.top); m.put("right", p.right); m.put("bottom", p.bottom); m.put("left", p.left);
            m.put("header", p.header); m.put("footer", p.footer); m.put("gutter", p.gutter);
            root.put("page", m);
        }
        return Json.write(root);
    }

    private static Map<String, Object> para(Paragraph p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "p");
        m.put("style", p.style);
        m.put("align", p.align);
        m.put("list", p.list);
        m.put("level", p.level);
        List<Object> runs = new ArrayList<>();
        for (Run r : p.runs) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("text", r.text);
            if (r.bold) rm.put("b", true);
            if (r.italic) rm.put("i", true);
            if (r.underline) rm.put("u", true);
            if (r.strike) rm.put("s", true);
            if (r.sizePt != 0) rm.put("size", r.sizePt);
            runs.add(rm);
        }
        m.put("runs", runs);
        return m;
    }

    private static Map<String, Object> table(Table t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "table");
        List<Object> rows = new ArrayList<>();
        for (List<List<Paragraph>> row : t.rows) {
            List<Object> cells = new ArrayList<>();
            for (List<Paragraph> cell : row) {
                List<Object> ps = new ArrayList<>();
                for (Paragraph p : cell) ps.add(para(p));
                cells.add(ps);
            }
            rows.add(cells);
        }
        m.put("rows", rows);
        if (!t.columnWidths.isEmpty()) m.put("widths", new ArrayList<Object>(t.columnWidths));
        if (t.styleId != null) m.put("tableStyle", t.styleId);
        return m;
    }

    public static Document fromJson(String json) {
        Map<String, Object> root = Json.obj(Json.parse(json));
        Document d = new Document();
        List<Object> blocks = Json.arr(root.get("blocks"));
        if (blocks.size() > MAX_BLOCKS) throw new IllegalArgumentException("Document too large");
        for (Object o : blocks) {
            Map<String, Object> m = Json.obj(o);
            if ("table".equals(Json.str(m, "t", ""))) d.blocks.add(readTable(m));
            else d.blocks.add(readPara(m));
        }
        if (root.get("page") instanceof Map) {
            Map<String, Object> m = Json.obj(root.get("page"));
            PageSetup p = new PageSetup();
            p.width = clamp(Json.num(m, "w", p.width), 1440, 31680);
            p.height = clamp(Json.num(m, "h", p.height), 1440, 31680);
            p.landscape = Json.bool(m, "landscape");
            p.top = clamp(Json.num(m, "top", p.top), 0, 31680);
            p.right = clamp(Json.num(m, "right", p.right), 0, 31680);
            p.bottom = clamp(Json.num(m, "bottom", p.bottom), 0, 31680);
            p.left = clamp(Json.num(m, "left", p.left), 0, 31680);
            p.header = clamp(Json.num(m, "header", p.header), 0, 31680);
            p.footer = clamp(Json.num(m, "footer", p.footer), 0, 31680);
            p.gutter = clamp(Json.num(m, "gutter", p.gutter), 0, 31680);
            d.page = p;
        }
        return d;
    }

    static Paragraph readPara(Map<String, Object> m) {
        Paragraph p = new Paragraph();
        p.style = pick(Json.str(m, "style", DocModel.STYLE_NORMAL), STYLES, DocModel.STYLE_NORMAL);
        p.align = pick(Json.str(m, "align", DocModel.ALIGN_LEFT), ALIGNS, DocModel.ALIGN_LEFT);
        p.list = pick(Json.str(m, "list", DocModel.LIST_NONE), LISTS, DocModel.LIST_NONE);
        p.level = clamp(Json.num(m, "level", 0), 0, 8);
        for (Object ro : Json.arr(m.get("runs"))) {
            Map<String, Object> rm = Json.obj(ro);
            String text = Json.str(rm, "text", "");
            if (text.isEmpty()) continue;
            Run r = new Run(text);
            r.bold = Json.bool(rm, "b");
            r.italic = Json.bool(rm, "i");
            r.underline = Json.bool(rm, "u");
            r.strike = Json.bool(rm, "s");
            r.sizePt = clamp(Json.num(rm, "size", 0), 0, 400);
            // Merge with the previous run when the formatting is identical.
            if (!p.runs.isEmpty() && p.runs.get(p.runs.size() - 1).sameFormat(r)) {
                p.runs.get(p.runs.size() - 1).text += r.text;
            } else {
                p.runs.add(r);
            }
        }
        return p;
    }

    private static Table readTable(Map<String, Object> m) {
        Table t = new Table();
        int cells = 0;
        for (Object ro : Json.arr(m.get("rows"))) {
            List<List<Paragraph>> row = new ArrayList<>();
            for (Object co : Json.arr(ro)) {
                if (++cells > MAX_TABLE_CELLS) throw new IllegalArgumentException("Table too large");
                List<Paragraph> cell = new ArrayList<>();
                for (Object po : Json.arr(co)) cell.add(readPara(Json.obj(po)));
                if (cell.isEmpty()) cell.add(new Paragraph());
                row.add(cell);
            }
            if (!row.isEmpty()) t.rows.add(row);
        }
        for (Object w : Json.arr(m.get("widths"))) {
            if (w instanceof Number) t.columnWidths.add(clamp(((Number) w).intValue(), 100, 31680));
        }
        Object ts = m.get("tableStyle");
        if (ts instanceof String && ((String) ts).matches("[A-Za-z0-9_\\- ]{1,80}")) t.styleId = (String) ts;
        return t;
    }

    private static String pick(String v, Set<String> allowed, String def) {
        return allowed.contains(v) ? v : def;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
