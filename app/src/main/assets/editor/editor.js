/*
 * Fold Word offline editor.
 * Converts between Fold Word's JSON document model (see DocJson.java) and an editable page.
 * All document text is inserted with DOM text nodes, never as HTML, so a document cannot run script.
 */
(function () {
  'use strict';

  var page = document.getElementById('page');
  var bridge = window.FoldBridge || null; // provided by the Android app
  var STYLES = ['normal', 'title', 'h1', 'h2', 'h3', 'quote'];
  var ALIGNS = ['left', 'center', 'right', 'justify'];
  var FONT_TAG_PT = { 1: 8, 2: 10, 3: 12, 4: 14, 5: 18, 6: 24, 7: 36 };
  var BLOCK_TAGS = { P: 1, DIV: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1, BLOCKQUOTE: 1, PRE: 1,
                     UL: 1, OL: 1, LI: 1, TABLE: 1, HR: 1, SECTION: 1, ARTICLE: 1 };

  function notify(name, arg) {
    try { if (bridge && bridge[name]) bridge[name](arg === undefined ? '' : String(arg)); } catch (e) { /* ignore */ }
  }

  // ------------------------------------------------------------------ render: model -> page

  function renderRuns(el, runs) {
    var any = false;
    (runs || []).forEach(function (r) {
      var text = typeof r.text === 'string' ? r.text : '';
      if (!text) return;
      any = true;
      var host = el, wrap;
      function nest(tag) { var n = document.createElement(tag); host.appendChild(n); host = n; }
      if (r.size) { wrap = document.createElement('span'); wrap.style.fontSize = Math.max(1, Math.min(400, r.size | 0)) + 'pt'; host.appendChild(wrap); host = wrap; }
      if (r.b) nest('b');
      if (r.i) nest('i');
      if (r.u) nest('u');
      if (r.s) nest('s');
      var parts = text.split('\n');
      parts.forEach(function (part, k) {
        if (k > 0) host.appendChild(document.createElement('br'));
        if (part) host.appendChild(document.createTextNode(part));
      });
    });
    if (!any || el.lastChild && el.lastChild.nodeName === 'BR') el.appendChild(document.createElement('br'));
  }

  function paragraphElement(p) {
    var style = STYLES.indexOf(p.style) >= 0 ? p.style : 'normal';
    var el;
    if (style === 'h1' || style === 'h2' || style === 'h3') el = document.createElement(style);
    else if (style === 'quote') el = document.createElement('blockquote');
    else { el = document.createElement('p'); if (style === 'title') el.className = 'fw-title'; }
    if (ALIGNS.indexOf(p.align) > 0) el.style.textAlign = p.align;
    renderRuns(el, p.runs);
    return el;
  }

  function renderBlocks(target, blocks) {
    var listStack = []; // [{el, type}] by level
    function closeLists() { listStack = []; }
    (blocks || []).forEach(function (b) {
      if (b && b.t === 'table') {
        closeLists();
        target.appendChild(tableElement(b));
        return;
      }
      b = b || {};
      var list = b.list === 'bullet' || b.list === 'number' ? b.list : 'none';
      if (list === 'none') {
        closeLists();
        target.appendChild(paragraphElement(b));
        return;
      }
      var level = Math.max(0, Math.min(8, b.level | 0));
      var tag = list === 'bullet' ? 'UL' : 'OL';
      // Trim deeper levels, and restart a level whose list type changed.
      while (listStack.length > level + 1) listStack.pop();
      if (listStack.length === level + 1 && listStack[level].el.nodeName !== tag) listStack.pop();
      while (listStack.length < level + 1) {
        var l = document.createElement(tag);
        var parentList = listStack.length ? listStack[listStack.length - 1].el : null;
        if (parentList) {
          var host = parentList.lastElementChild;
          if (!host) { host = document.createElement('li'); parentList.appendChild(host); }
          host.appendChild(l);
        } else {
          target.appendChild(l);
        }
        listStack.push({ el: l });
      }
      var li = document.createElement('li');
      if (ALIGNS.indexOf(b.align) > 0) li.style.textAlign = b.align;
      renderRuns(li, b.runs);
      listStack[level].el.appendChild(li);
    });
  }

  function tableElement(t) {
    var table = document.createElement('table');
    var tbody = document.createElement('tbody');
    table.appendChild(tbody);
    if (Array.isArray(t.widths)) table.dataset.widths = JSON.stringify(t.widths.filter(function (w) { return typeof w === 'number'; }));
    if (typeof t.tableStyle === 'string') table.dataset.tableStyle = t.tableStyle;
    var cols = 0;
    (t.rows || []).forEach(function (r) { cols = Math.max(cols, (r || []).length); });
    (t.rows || []).forEach(function (row) {
      var tr = document.createElement('tr');
      for (var c = 0; c < Math.max(1, cols); c++) {
        var td = document.createElement('td');
        var cell = (row || [])[c] || [];
        if (!cell.length) cell = [{ runs: [] }];
        renderBlocks(td, cell);
        tr.appendChild(td);
      }
      tbody.appendChild(tr);
    });
    return table;
  }

  // ------------------------------------------------------------------ serialize: page -> model

  function fmtFrom(el, f) {
    var n = { b: f.b, i: f.i, u: f.u, s: f.s, size: f.size };
    var tag = el.nodeName;
    if (tag === 'B' || tag === 'STRONG') n.b = true;
    if (tag === 'I' || tag === 'EM') n.i = true;
    if (tag === 'U' || tag === 'INS') n.u = true;
    if (tag === 'S' || tag === 'STRIKE' || tag === 'DEL') n.s = true;
    if (tag === 'FONT' && el.getAttribute('size')) n.size = FONT_TAG_PT[parseInt(el.getAttribute('size'), 10)] || n.size;
    var st = el.style;
    if (st) {
      var fw = st.fontWeight;
      if (fw === 'bold' || parseInt(fw, 10) >= 600) n.b = true;
      else if (fw === 'normal' || (parseInt(fw, 10) > 0 && parseInt(fw, 10) < 600)) n.b = false;
      if (st.fontStyle === 'italic') n.i = true; else if (st.fontStyle === 'normal') n.i = false;
      var td = (st.textDecorationLine || st.textDecoration || '');
      if (td.indexOf('underline') >= 0) n.u = true;
      if (td.indexOf('line-through') >= 0) n.s = true;
      var fs = st.fontSize;
      if (fs) {
        var m = /^([\d.]+)(pt|px)$/.exec(fs);
        if (m) n.size = Math.round(m[2] === 'px' ? parseFloat(m[1]) * 0.75 : parseFloat(m[1]));
      }
    }
    return n;
  }

  function pushRun(runs, text, f) {
    if (!text) return;
    var last = runs[runs.length - 1];
    if (last && !!last.b === !!f.b && !!last.i === !!f.i && !!last.u === !!f.u && !!last.s === !!f.s && (last.size || 0) === (f.size || 0)) {
      last.text += text;
      return;
    }
    var r = { text: text };
    if (f.b) r.b = true;
    if (f.i) r.i = true;
    if (f.u) r.u = true;
    if (f.s) r.s = true;
    if (f.size) r.size = f.size;
    runs.push(r);
  }

  /** Collects runs from inline content. Nested lists are skipped (they become their own paragraphs). */
  function collectRuns(node, f, runs) {
    for (var c = node.firstChild; c; c = c.nextSibling) collectNode(c, f, runs);
  }

  function collectNode(c, f, runs) {
    if (c.nodeType === 3) {
      pushRun(runs, c.nodeValue.replace(/ /g, ' ').replace(/[\r\n]+/g, ' '), f);
    } else if (c.nodeType === 1) {
      var tag = c.nodeName;
      if (tag === 'BR') { pushRun(runs, '\n', f); return; }
      if (tag === 'UL' || tag === 'OL' || tag === 'TABLE' || tag === 'SCRIPT' || tag === 'STYLE') return;
      if (BLOCK_TAGS[tag] && runs.length && !/\n$/.test(runs[runs.length - 1].text)) pushRun(runs, '\n', f);
      collectRuns(c, fmtFrom(c, f), runs);
    }
  }

  function trimRuns(runs) {
    // A trailing line break is only the browser's placeholder for an empty line.
    while (runs.length) {
      var last = runs[runs.length - 1];
      if (last.text === '\n') { runs.pop(); continue; }
      if (/\n$/.test(last.text)) last.text = last.text.replace(/\n$/, '');
      break;
    }
    return runs;
  }

  function alignOf(el) {
    var a = (el.style && el.style.textAlign) || el.getAttribute('align') || '';
    if (a === 'start') a = 'left';
    if (a === 'end') a = 'right';
    return ALIGNS.indexOf(a) >= 0 ? a : 'left';
  }

  function styleOf(el) {
    switch (el.nodeName) {
      case 'H1': return 'h1';
      case 'H2': return 'h2';
      case 'H3': case 'H4': case 'H5': case 'H6': return 'h3';
      case 'BLOCKQUOTE': return 'quote';
      default: return el.classList && el.classList.contains('fw-title') ? 'title' : 'normal';
    }
  }

  function paragraphFrom(el, style, list, level) {
    return { t: 'p', style: style, align: alignOf(el), list: list || 'none', level: level || 0,
             runs: trimRuns(collectRuns2(el)) };
  }

  function collectRuns2(el) {
    var runs = [];
    collectRuns(el, fmtFrom(el, {}), runs);
    return runs;
  }

  function hasBlockChild(el) {
    for (var c = el.firstElementChild; c; c = c.nextElementSibling) if (BLOCK_TAGS[c.nodeName]) return true;
    return false;
  }

  function serializeList(list, level, out) {
    var type = list.nodeName === 'OL' ? 'number' : 'bullet';
    for (var c = list.firstChild; c; c = c.nextSibling) {
      if (c.nodeType !== 1) {
        if (c.nodeType === 3 && c.nodeValue.trim()) out.push({ t: 'p', style: 'normal', align: 'left', list: type, level: level, runs: [{ text: c.nodeValue.trim() }] });
        continue;
      }
      if (c.nodeName === 'UL' || c.nodeName === 'OL') { serializeList(c, Math.min(8, level + 1), out); continue; }
      out.push(paragraphFrom(c, 'normal', type, level));
      for (var n = c.firstElementChild; n; n = n.nextElementSibling) {
        if (n.nodeName === 'UL' || n.nodeName === 'OL') serializeList(n, Math.min(8, level + 1), out);
      }
    }
  }

  function serializeTable(table) {
    var rows = [];
    var trs = table.querySelectorAll('tr');
    for (var i = 0; i < trs.length; i++) {
      if (trs[i].closest('table') !== table) continue; // skip rows of nested tables
      var row = [];
      for (var c = trs[i].firstElementChild; c; c = c.nextElementSibling) {
        if (c.nodeName !== 'TD' && c.nodeName !== 'TH') continue;
        var cell = [];
        serializeContainer(c, cell, true);
        if (!cell.length) cell.push({ t: 'p', style: 'normal', align: 'left', list: 'none', level: 0, runs: [] });
        row.push(cell);
      }
      if (row.length) rows.push(row);
    }
    var t = { t: 'table', rows: rows };
    try { if (table.dataset.widths) t.widths = JSON.parse(table.dataset.widths); } catch (e) { /* ignore */ }
    if (table.dataset.tableStyle) t.tableStyle = table.dataset.tableStyle;
    return t;
  }

  function serializeContainer(container, out, inTable) {
    var loose = null; // inline content directly inside the container
    function flushLoose() {
      if (loose) { out.push({ t: 'p', style: 'normal', align: 'left', list: 'none', level: 0, runs: trimRuns(loose) }); loose = null; }
    }
    for (var c = container.firstChild; c; c = c.nextSibling) {
      if (c.nodeType === 3) {
        if (c.nodeValue.replace(/\s/g, '')) { loose = loose || []; pushRun(loose, c.nodeValue.replace(/ /g, ' '), {}); }
        continue;
      }
      if (c.nodeType !== 1) continue;
      var tag = c.nodeName;
      if (!BLOCK_TAGS[tag]) {
        if (tag === 'BR') { if (loose) pushRun(loose, '\n', {}); else if (!inTable) out.push({ t: 'p', style: 'normal', align: 'left', list: 'none', level: 0, runs: [] }); continue; }
        loose = loose || [];
        collectNode(c, {}, loose);
        continue;
      }
      flushLoose();
      if (tag === 'UL' || tag === 'OL') serializeList(c, 0, out);
      else if (tag === 'TABLE') {
        if (inTable) serializeContainer(c.querySelector('tbody') || c, out, true); // nested table: flatten
        else out.push(serializeTable(c));
      } else if (tag === 'HR') continue;
      else if ((tag === 'DIV' || tag === 'BLOCKQUOTE' || tag === 'SECTION' || tag === 'ARTICLE') && hasBlockChild(c)) {
        var inner = [];
        serializeContainer(c, inner, inTable);
        if (tag === 'BLOCKQUOTE') inner.forEach(function (p) { if (p.t === 'p' && p.style === 'normal' && p.list === 'none') p.style = 'quote'; });
        Array.prototype.push.apply(out, inner);
      } else out.push(paragraphFrom(c, styleOf(c), 'none', 0));
    }
    flushLoose();
  }

  function serialize() {
    var blocks = [];
    serializeContainer(page, blocks, false);
    if (!blocks.length) blocks.push({ t: 'p', style: 'normal', align: 'left', list: 'none', level: 0, runs: [] });
    var doc = { v: 1, blocks: blocks };
    if (state.page) doc.page = state.page;
    return JSON.stringify(doc);
  }

  // ------------------------------------------------------------------ editing commands

  var state = { page: null, dirty: false };

  function selectionBlock() {
    var sel = window.getSelection();
    if (!sel || !sel.rangeCount) return null;
    var n = sel.anchorNode;
    while (n && n !== page) {
      if (n.nodeType === 1 && /^(P|H1|H2|H3|H4|H5|H6|BLOCKQUOTE|LI|DIV|TD|TH)$/.test(n.nodeName)) return n;
      n = n.parentNode;
    }
    return null;
  }

  function currentCell() {
    var b = selectionBlock();
    return b && b.closest ? b.closest('td,th') : null;
  }

  function changed() {
    if (!state.dirty) { state.dirty = true; notify('onDirty'); }
    scheduleStats();
  }

  function exec(cmd, value) {
    page.focus();
    document.execCommand(cmd, false, value === undefined ? null : value);
    changed();
    refreshToolbar();
  }

  function setStyle(style) {
    page.focus();
    var tag = { normal: 'p', title: 'p', h1: 'h1', h2: 'h2', h3: 'h3', quote: 'blockquote' }[style] || 'p';
    document.execCommand('formatBlock', false, tag);
    var b = selectionBlock();
    if (b) {
      if (style === 'title') b.classList.add('fw-title'); else b.classList.remove('fw-title');
    }
    changed();
    refreshToolbar();
  }

  function setSize(pt) {
    if (!pt) return;
    page.focus();
    document.execCommand('styleWithCSS', false, false);
    document.execCommand('fontSize', false, '7');
    var fonts = page.querySelectorAll('font[size="7"]');
    for (var i = 0; i < fonts.length; i++) {
      var span = document.createElement('span');
      span.style.fontSize = pt + 'pt';
      while (fonts[i].firstChild) span.appendChild(fonts[i].firstChild);
      fonts[i].parentNode.replaceChild(span, fonts[i]);
    }
    changed();
  }

  function clearFormatting() {
    exec('removeFormat');
    setStyle('normal');
  }

  function insertTable(rows, cols) {
    rows = Math.max(1, Math.min(50, rows | 0));
    cols = Math.max(1, Math.min(20, cols | 0));
    var t = { t: 'table', rows: [] };
    for (var r = 0; r < rows; r++) {
      var row = [];
      for (var c = 0; c < cols; c++) row.push([{ runs: [] }]);
      t.rows.push(row);
    }
    var el = tableElement(t);
    var after = document.createElement('p');
    after.appendChild(document.createElement('br'));
    var b = selectionBlock();
    var top = b;
    while (top && top.parentNode !== page) top = top.parentNode;
    if (top && top.parentNode === page) { page.insertBefore(el, top.nextSibling); page.insertBefore(after, el.nextSibling); }
    else { page.appendChild(el); page.appendChild(after); }
    placeCaret(el.querySelector('td'));
    changed();
  }

  function placeCaret(el) {
    if (!el) return;
    var range = document.createRange();
    range.selectNodeContents(el);
    range.collapse(true);
    var sel = window.getSelection();
    sel.removeAllRanges();
    sel.addRange(range);
  }

  function tableOp(op) {
    var cell = currentCell();
    if (!cell) return;
    var tr = cell.parentNode, table = cell.closest('table');
    var idx = Array.prototype.indexOf.call(tr.children, cell);
    function emptyCell() { var td = document.createElement('td'); var p = document.createElement('p'); p.appendChild(document.createElement('br')); td.appendChild(p); return td; }
    if (op === 'rowAdd') {
      var nr = document.createElement('tr');
      for (var i = 0; i < tr.children.length; i++) nr.appendChild(emptyCell());
      tr.parentNode.insertBefore(nr, tr.nextSibling);
    } else if (op === 'colAdd') {
      table.querySelectorAll('tr').forEach(function (r) { var ref = r.children[idx]; r.insertBefore(emptyCell(), ref ? ref.nextSibling : null); });
      delete table.dataset.widths;
    } else if (op === 'rowDel') {
      if (table.querySelectorAll('tr').length <= 1) table.remove(); else tr.remove();
    } else if (op === 'colDel') {
      if (tr.children.length <= 1) table.remove();
      else { table.querySelectorAll('tr').forEach(function (r) { if (r.children[idx]) r.children[idx].remove(); }); delete table.dataset.widths; }
    }
    changed();
    refreshToolbar();
  }

  // ------------------------------------------------------------------ toolbar state

  var styleSel = document.getElementById('style');
  var sizeSel = document.getElementById('size');

  function refreshToolbar() {
    var btns = document.querySelectorAll('#ribbon button[data-cmd]');
    for (var i = 0; i < btns.length; i++) {
      var on = false;
      try { on = document.queryCommandState(btns[i].dataset.cmd); } catch (e) { /* ignore */ }
      btns[i].classList.toggle('on', !!on);
    }
    var b = selectionBlock();
    var st = 'normal';
    if (b) {
      var top = b.nodeName === 'LI' || b.nodeName === 'TD' || b.nodeName === 'TH' ? null : b;
      if (top) st = styleOf(top);
    }
    styleSel.value = st;
    var inCell = !!currentCell();
    document.querySelectorAll('#ribbon .tbl').forEach(function (x) { x.disabled = !inCell; });
  }

  document.querySelectorAll('#ribbon button').forEach(function (b) {
    b.addEventListener('mousedown', function (e) { e.preventDefault(); }); // keep the text selection
    b.addEventListener('click', function () {
      if (b.dataset.cmd) exec(b.dataset.cmd);
      else if (b.dataset.act === 'clear') clearFormatting();
      else if (b.dataset.act === 'table') document.getElementById('tablePop').hidden = false;
      else tableOp(b.dataset.act);
    });
  });
  styleSel.addEventListener('change', function () { setStyle(styleSel.value); });
  sizeSel.addEventListener('change', function () { setSize(parseInt(sizeSel.value, 10)); sizeSel.value = ''; });
  document.getElementById('tOk').addEventListener('click', function () {
    document.getElementById('tablePop').hidden = true;
    insertTable(parseInt(document.getElementById('tRows').value, 10), parseInt(document.getElementById('tCols').value, 10));
  });
  document.getElementById('tCancel').addEventListener('click', function () { document.getElementById('tablePop').hidden = true; });

  document.addEventListener('selectionchange', refreshToolbar);
  page.addEventListener('input', changed);

  // Paste and drop as plain text, so outside formatting or pictures cannot sneak in unsaved.
  function insertPlain(text) {
    document.execCommand('insertText', false, text.replace(/\r\n?/g, '\n'));
    changed();
  }
  page.addEventListener('paste', function (e) {
    e.preventDefault();
    insertPlain((e.clipboardData || window.clipboardData).getData('text/plain') || '');
  });
  page.addEventListener('drop', function (e) {
    e.preventDefault();
    var t = e.dataTransfer && e.dataTransfer.getData('text/plain');
    if (t) insertPlain(t);
  });

  // Keyboard shortcuts for Bluetooth keyboards. Ctrl+S is handled by the app.
  page.addEventListener('keydown', function (e) {
    if (!(e.ctrlKey || e.metaKey)) return;
    var k = e.key.toLowerCase();
    if (k === 's') { e.preventDefault(); notify('onSaveShortcut'); }
    else if (k === 'f') { e.preventDefault(); notify('onFindShortcut'); }
    else if (k === 'z' && e.shiftKey) { e.preventDefault(); exec('redo'); }
    else if (k === 'y') { e.preventDefault(); exec('redo'); }
  });

  // ------------------------------------------------------------------ stats

  var statsTimer = 0;
  function scheduleStats() {
    clearTimeout(statsTimer);
    statsTimer = setTimeout(function () {
      var text = page.innerText || page.textContent || '';
      var words = (text.trim().match(/\S+/g) || []).length;
      notify('onStats', JSON.stringify({ words: words, chars: text.replace(/\s/g, '').length }));
    }, 400);
  }

  // ------------------------------------------------------------------ public API for the app

  window.FW = {
    load: function (json, readOnly) {
      var doc = typeof json === 'string' ? JSON.parse(json) : json;
      state.page = doc && doc.page ? doc.page : null;
      while (page.firstChild) page.removeChild(page.firstChild);
      renderBlocks(page, (doc && doc.blocks) || []);
      if (!page.firstChild) { var p = document.createElement('p'); p.appendChild(document.createElement('br')); page.appendChild(p); }
      page.contentEditable = readOnly ? 'false' : 'true';
      state.dirty = false;
      try { document.execCommand('defaultParagraphSeparator', false, 'p'); } catch (e) { /* ignore */ }
      refreshToolbar();
      scheduleStats();
      return true;
    },
    serialize: serialize,
    markSaved: function () { state.dirty = false; },
    isDirty: function () { return state.dirty; },
    undo: function () { exec('undo'); },
    redo: function () { exec('redo'); },
    focus: function () { page.focus(); },
    // Exposed for tests.
    _renderBlocks: renderBlocks,
    _insertTable: insertTable
  };

  notify('onReady');
})();
