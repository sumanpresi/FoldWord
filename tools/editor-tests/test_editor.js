// Tests the offline editor (assets/editor) in a simulated browser: round trips, browser markup, safety.
// Run: npm ci && npm test   (inside tools/editor-tests)
const { JSDOM } = require('jsdom');
const fs = require('fs'), path = require('path'), assert = require('assert');
const ED = path.join(__dirname, '..', '..', 'app', 'src', 'main', 'assets', 'editor', 'editor.html');
const OUT = path.join(__dirname, 'fixtures');
function canon(doc) { // fill defaults like DocJson.readPara does, so comparisons are fair
  const p = x => ({ t: 'p', style: x.style || 'normal', align: x.align || 'left', list: x.list || 'none', level: x.level || 0,
    runs: (x.runs || []).filter(r => r.text).map(r => { const o = { text: r.text }; ['b','i','u','s'].forEach(k => { if (r[k]) o[k] = true; }); if (r.size) o.size = r.size; return o; }) });
  const pad = rows => { const n = Math.max(...rows.map(r => r.length)); return rows.map(r => { const x = r.slice(); while (x.length < n) x.push([{ runs: [] }]); return x; }); };
  return { blocks: doc.blocks.map(b => b.t === 'table' ? { t: 'table', rows: pad(b.rows).map(r => r.map(c => c.map(p))), widths: b.widths, tableStyle: b.tableStyle } : p(b)), page: doc.page };
}
async function editor() {
  const dom = await JSDOM.fromFile(ED, { runScripts: 'dangerously', resources: 'usable', pretendToBeVisual: true });
  await new Promise(r => dom.window.addEventListener('load', r));
  return dom.window;
}
(async () => {
  let pass = 0;
  for (const name of ['simple.json', 'new.json']) {
    const w = await editor();
    const src = JSON.parse(fs.readFileSync(path.join(OUT, name), 'utf8'));
    w.FW.load(JSON.stringify(src));
    const back = JSON.parse(w.FW.serialize());
    assert.deepStrictEqual(canon(back), canon(src), name + ' round trip through the editor changed the document');
    console.log('PASS editor round trip', name); pass++;
  }
  // Browser-generated markup the editor must understand.
  {
    const w = await editor();
    const page = w.document.getElementById('page');
    page.innerHTML = '<div>Chrome div line</div><div><br></div>' +
      '<ul><li>a</li><ul><li>nested directly in ul</li></ul><li>b<ol><li>inner numbered</li></ol></li></ul>' +
      '<p>x <font size="6">big</font> <span style="font-weight: bold; font-style: italic">bi</span> <span style="text-decoration: underline line-through">us</span></p>' +
      '<h4>deep</h4><blockquote><p>q1</p><p>q2</p></blockquote><p style="text-align:center">c</p>' +
      '<table><tbody><tr><td>1</td><td><p>2a</p><p>2b</p></td></tr><tr><td>3</td></tr></tbody></table>loose text';
    const d = JSON.parse(w.FW.serialize());
    const summary = d.blocks.map(b => b.t === 'table' ? 'TABLE ' + JSON.stringify(b.rows.map(r => r.map(c => c.map(p => p.runs.map(x => x.text).join(''))))) :
      `${b.style}/${b.align}/${b.list}${b.level} ` + b.runs.map(r => r.text + (r.b ? '[b]' : '') + (r.i ? '[i]' : '') + (r.u ? '[u]' : '') + (r.s ? '[s]' : '') + (r.size ? '[' + r.size + 'pt]' : '')).join('|'));
    console.log(summary.join('\n'));
    const expect = [
      'normal/left/none0 Chrome div line', 'normal/left/none0 ',
      'normal/left/bullet0 a', 'normal/left/bullet1 nested directly in ul', 'normal/left/bullet0 b', 'normal/left/number1 inner numbered',
      'normal/left/none0 x |big[24pt]| |bi[b][i]| |us[u][s]', 'h3/left/none0 deep', 'quote/left/none0 q1', 'quote/left/none0 q2',
      'normal/center/none0 c', 'TABLE [[["1"],["2a","2b"]],[["3"]]]', 'normal/left/none0 loose text'];
    assert.deepStrictEqual(summary, expect);
    console.log('PASS browser markup is serialised correctly'); pass++;
  }
  // Document text must never become markup.
  {
    const w = await editor();
    const evil = '<img src=x onerror="window.pwned=1"><script>window.pwned=2</script>';
    w.FW.load(JSON.stringify({ v: 1, blocks: [{ t: 'p', runs: [{ text: evil }] }] }));
    const page = w.document.getElementById('page');
    assert.strictEqual(page.querySelectorAll('img,script').length, 0);
    assert.strictEqual(w.pwned, undefined);
    assert.strictEqual(JSON.parse(w.FW.serialize()).blocks[0].runs[0].text, evil);
    console.log('PASS document text cannot inject markup or script'); pass++;
  }
  // Invalid values from a damaged file fall back to safe defaults.
  {
    const w = await editor();
    w.FW.load(JSON.stringify({ v: 1, blocks: [{ t: 'p', style: '"><x', align: 'evil', list: 'weird', level: 99, runs: [{ text: 'ok', size: 9999 }] }] }));
    const d = JSON.parse(w.FW.serialize()).blocks[0];
    assert.strictEqual(d.style, 'normal'); assert.strictEqual(d.align, 'left'); assert.strictEqual(d.list, 'none');
    assert.strictEqual(d.runs[0].text, 'ok'); assert.strictEqual(d.runs[0].size, 400);
    console.log('PASS unsafe values are neutralised'); pass++;
  }
  // Insert table helper produces a table the serialiser understands.
  {
    const w = await editor();
    w.FW.load('{"v":1,"blocks":[{"t":"p","runs":[{"text":"before"}]}]}');
    w.FW._insertTable(2, 3);
    const d = JSON.parse(w.FW.serialize());
    assert.strictEqual(d.blocks[1].t, 'table'); assert.strictEqual(d.blocks[1].rows.length, 2); assert.strictEqual(d.blocks[1].rows[0].length, 3);
    console.log('PASS insert table'); pass++;
  }
  console.log(`\n${pass} editor checks passed`);
})().catch(e => { console.error('FAIL', e.message); process.exit(1); });
