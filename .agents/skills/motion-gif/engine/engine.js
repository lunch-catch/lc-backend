/*
 * 장면 엔진이다. window.SLIDE_SPEC 의 장면을 1600x900 무대에 그리고 timeline 을 loop 길이로 되풀이한다.
 * 시간은 requestAnimationFrame 으로만 흐르므로 녹화기가 시계를 멈추고 프레임 단위로 넘길 수 있다.
 * 요소는 parent 를 주면 그 요소의 왼쪽 위를 기준으로 놓인다. 분류나 정해진 틀은 없다.
 */
(function () {
  var W = 1600, H = 900, NS = 'http://www.w3.org/2000/svg';
  var spec = window.SLIDE_SPEC;
  var SPEED = spec.speed || 0.35;
  var LOOP = spec.loop || 15000;

  var ICONS = {
    scan: '<path d="M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3"/><rect x="8" y="8" width="8" height="8" rx="1.5"/>',
    chart: '<path d="M4 20h16M5 16l4-5 4 3 6-8"/><path d="M16 6h3v3"/>',
    upload: '<path d="M12 15V4M7.5 8.5 12 4l4.5 4.5M5 14v5h14v-5"/>',
    play: '<rect x="4" y="4" width="16" height="16" rx="3"/><path d="M10 9v6l5-3z"/>',
    shield: '<path d="M12 3 5 6v5c0 4.5 3 8 7 10 4-2 7-5.5 7-10V6z"/><path d="m9 12 2 2 4-4"/>',
    gauge: '<path d="M5 18a8 8 0 1 1 14 0"/><path d="m12 14 4-5"/>',
    db: '<ellipse cx="12" cy="6" rx="7" ry="3"/><path d="M5 6v12c0 1.7 3.1 3 7 3s7-1.3 7-3V6M5 12c0 1.7 3.1 3 7 3s7-1.3 7-3"/>',
    server: '<rect x="4" y="4" width="16" height="7" rx="1.5"/><rect x="4" y="13" width="16" height="7" rx="1.5"/><path d="M8 7.5h.01M8 16.5h.01"/>',
    clock: '<circle cx="12" cy="12" r="8"/><path d="M12 8v4l3 2"/>',
    bolt: '<path d="M13 3 5 14h6l-1 7 8-11h-6z"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 20c1.5-4 4.5-6 8-6s6.5 2 8 6"/>',
    robot: '<rect x="5" y="8" width="14" height="11" rx="3"/><path d="M12 4v4M9 13h.01M15 13h.01M9.5 16h5"/>',
    queue: '<rect x="3" y="9" width="4" height="6" rx="1"/><rect x="10" y="9" width="4" height="6" rx="1"/><rect x="17" y="9" width="4" height="6" rx="1"/>',
    coin: '<circle cx="12" cy="12" r="8"/><path d="M14.5 9.5c-.5-1-1.4-1.5-2.5-1.5-1.4 0-2.5.8-2.5 2s1 1.7 2.5 2 2.5.8 2.5 2-1.1 2-2.5 2c-1.1 0-2-.5-2.5-1.5M12 6.5v11"/>',
    bell: '<path d="M6 16V11a6 6 0 0 1 12 0v5l2 2H4z"/><path d="M10 20a2 2 0 0 0 4 0"/>',
    cloud: '<path d="M7 18a4 4 0 0 1-.5-8A5.5 5.5 0 0 1 17 8.5 4.5 4.5 0 0 1 17.5 18z"/>',
    lock: '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
    chip: '<rect x="7" y="7" width="10" height="10" rx="1.5"/><path d="M10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
    moon: '<path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"/>',
    code: '<path d="m8 8-4 4 4 4M16 8l4 4-4 4M13.5 5l-3 14"/>',
    phone: '<rect x="7" y="3" width="10" height="18" rx="2"/><path d="M11 18h2"/>',
    store: '<path d="M4 9 5.5 4h13L20 9M4 9v11h16V9M4 9h16M9 20v-6h6v6"/>',
    ticket: '<path d="M4 7h16v3a2 2 0 0 0 0 4v3H4v-3a2 2 0 0 0 0-4z"/><path d="M14 7v10"/>',
    check: '<path d="m5 12 4 4 10-10"/>',
    x: '<path d="M6 6l12 12M18 6 6 18"/>'
  };

  var root = document.getElementById('stage');
  var wrap = document.getElementById('wrap');

  function fit() {
    var w = wrap.clientWidth, h = wrap.clientHeight, s = Math.min(w / W, h / H);
    root.style.transform = 'translate(' + (w - W * s) / 2 + 'px,' + (h - H * s) / 2 + 'px) scale(' + s + ')';
  }
  window.addEventListener('resize', fit);

  function el(tag, cls, parent, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    if (parent) parent.appendChild(e);
    return e;
  }
  function svgEl(tag, attrs, parent) {
    var e = document.createElementNS(NS, tag);
    for (var k in attrs) e.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(e);
    return e;
  }
  function br(s) { return esc(s).replace(/\n/g, '<br>'); }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]; }); }
  function icon(name) { return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">' + (ICONS[name] || '') + '</svg>'; }
  function fmt(v, d) {
    if (typeof v !== 'number') return String(v);
    var s = v.toFixed(d == null ? 0 : d);
    var parts = s.split('.'); parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    return parts.join('.');
  }

  var reg = {};
  var dotsLayer, wiresSvg;

  // 부모를 따라 올라가며 무대 기준의 위치를 구한다
  function abs(d) {
    var x = d.x || 0, y = d.y || 0, p = d.parent;
    while (p) { var pd = reg[p] && reg[p].def; if (!pd) throw new Error('없는 parent: ' + p); x += pd.x || 0; y += pd.y || 0; p = pd.parent; }
    return { x: x, y: y, w: d.w || 0, h: d.h || 0 };
  }

  function anchor(a) {
    if (Array.isArray(a)) return [a[0], a[1]];
    var m = /^([^.]+)\.(c|l|r|t|b|tl|tr|bl|br)([+-]\d+)?([+-]\d+)?$/.exec(a);
    if (!m || !reg[m[1]]) throw new Error('알 수 없는 기준점: ' + a);
    var b = abs(reg[m[1]].def), x = b.x, y = b.y, w = b.w, h = b.h, p = m[2];
    var pt = { c: [x + w / 2, y + h / 2], l: [x, y + h / 2], r: [x + w, y + h / 2], t: [x + w / 2, y], b: [x + w / 2, y + h],
      tl: [x, y], tr: [x + w, y], bl: [x, y + h], br: [x + w, y + h] }[p];
    var dx = 0, dy = 0;
    if (m[3] && m[4]) { dx = +m[3]; dy = +m[4]; }
    else if (m[3]) { if (p === 'l' || p === 'r') dy = +m[3]; else dx = +m[3]; }
    return [pt[0] + dx, pt[1] + dy];
  }

  function route(points, elbow) {
    elbow = elbow || 'hvh';
    var out = [points[0]];
    for (var i = 1; i < points.length; i++) {
      var a = out[out.length - 1], b = points[i];
      if (a[0] !== b[0] && a[1] !== b[1] && elbow !== 'none') {
        if (elbow === 'hv') out.push([b[0], a[1]]);
        else if (elbow === 'vh') out.push([a[0], b[1]]);
        else if (elbow === 'vhv') { var my = Math.round((a[1] + b[1]) / 2); out.push([a[0], my], [b[0], my]); }
        else { var mx = Math.round((a[0] + b[0]) / 2); out.push([mx, a[1]], [mx, b[1]]); }
      }
      out.push(b);
    }
    return out;
  }

  function render() {
    reg = {};
    root.innerHTML = '';
    el('div', 'bg', root);
    if (spec.title) {
      var hd = el('div', 'headline', root, esc(spec.title).replace(/\n/g, '<br>'));
      hd.style.left = '100px'; hd.style.top = (spec.title.indexOf('\n') >= 0 ? 56 : 78) + 'px';
    }
    if (spec.logo) { var lg = el('div', 'logo', root, esc(spec.logo)); lg.style.left = '1380px'; lg.style.top = '92px'; lg.style.width = '140px'; }

    var under = el('div', 'layer', root);
    wiresSvg = svgEl('svg', { 'class': 'wires', viewBox: '0 0 ' + W + ' ' + H }, root);
    svgEl('defs', {}, wiresSvg).innerHTML = '<marker id="arr" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M1 1.5 6.5 4 1 6.5" fill="none" stroke="currentColor" stroke-width="1.3"/></marker>';
    dotsLayer = svgEl('g', {});
    var top = el('div', 'layer top', root);

    (spec.elements || []).forEach(function (d) {
      if (d.id) reg[d.id] = { def: d };
      var made = build(d, d.type === 'panel' ? under : top);
      var b = abs(d);
      var e = made.el;
      e.classList.add('el');
      e.style.left = b.x + 'px'; e.style.top = b.y + 'px';
      if (d.w != null) e.style.width = d.w + 'px';
      if (d.h != null) e.style.height = d.h + 'px';
      // 부모가 숨겨졌거나 흐리면 자식도 같은 상태로 시작한다
      var hid = d.hidden || inherited(d, 'hidden'), dm = d.dim || inherited(d, 'dim');
      if (hid) { e.classList.add('hidden'); e.style.opacity = 0; }
      else if (dm) { e.classList.add('dimmed'); e.style.opacity = DIM; }
      if (d.id) { reg[d.id].el = e; reg[d.id].parts = made.parts || {}; reg[d.id].kind = d.type; }
      e.setAttribute('data-id', d.id || '');
      e.setAttribute('data-parent', d.parent || '');
    });

    (spec.wires || []).forEach(function (w) {
      var pts = route(w.points.map(anchor), w.elbow);
      var pl = svgEl('polyline', { points: pts.map(function (p) { return p.join(','); }).join(' '),
        'class': 'wire' + (w.dash ? ' dash' : '') + (w.tone ? ' t-' + w.tone : '') + (w.hidden ? ' hidden' : ''), opacity: w.hidden ? 0 : 1 }, wiresSvg);
      if (w.arrow) pl.setAttribute('marker-end', 'url(#arr)');
      var rec = { def: w, el: pl, pts: pts, kind: 'wire' };
      if (w.label) {
        var lp = w.label.at ? anchor(w.label.at) : mid(pts);
        var t = svgEl('text', { x: lp[0] + (w.label.dx || 0), y: lp[1] + (w.label.dy == null ? -8 : w.label.dy), 'text-anchor': w.label.align || 'middle',
          'class': w.hidden ? 'hidden' : '', opacity: w.hidden ? 0 : 1 }, wiresSvg);
        t.textContent = w.label.text; rec.labelEl = t;
      }
      if (w.id) reg[w.id] = rec;
    });
    wiresSvg.appendChild(dotsLayer);
  }

  function mid(pts) {
    var segs = [], total = 0, i;
    for (i = 1; i < pts.length; i++) { var d = dist(pts[i - 1], pts[i]); segs.push(d); total += d; }
    var half = total / 2;
    for (i = 0; i < segs.length; i++) {
      if (half <= segs[i]) { var f = segs[i] ? half / segs[i] : 0; return [pts[i][0] + (pts[i + 1][0] - pts[i][0]) * f, pts[i][1] + (pts[i + 1][1] - pts[i][1]) * f]; }
      half -= segs[i];
    }
    return pts[0];
  }
  function dist(a, b) { return Math.hypot(b[0] - a[0], b[1] - a[1]); }

  function tone(d, base) { return base + (d.tone ? ' t-' + d.tone : ''); }

  function build(d, parent) {
    var e, parts = {}, i;
    switch (d.type) {
      case 'panel':
        e = el('div', tone(d, 'panel' + (d.dashed ? ' dashed' : '') + (d.bare ? ' bare' : '')), parent);
        if (d.eyebrow) el('div', 'p-eyebrow', e, esc(d.eyebrow));
        if (d.title) parts.title = el('div', 'p-title', e, esc(d.title));
        if (d.sub) parts.sub = el('div', 'p-sub', e, esc(d.sub));
        break;
      case 'box':
        e = el('div', tone(d, 'box s-' + (d.size || 'm') + (d.align === 'center' ? ' center' : '')), parent);
        if (d.icon || d.num) {
          var head = el('div', 'b-head', e);
          if (d.icon) el('div', 'b-icon', head, icon(d.icon));
          if (d.num) el('div', 'b-num', head, esc(d.num));
        }
        parts.title = el('div', 'b-title', e, br(d.title));
        parts.sub = el('div', 'b-sub', e, br(d.sub));
        if (d.sub == null) parts.sub.style.display = 'none';
        parts.value = el('div', 'b-value', e, br(d.value));
        if (d.value == null) parts.value.style.display = 'none';
        if (d.progress != null) { var tr = el('div', 'b-track', e); parts.fill = el('div', 'b-fill', tr); parts.fill.style.width = d.progress * 100 + '%'; }
        break;
      case 'label':
        e = el('div', 'label c-' + (d.color || 'dim') + ' z-' + (d.size || 's') + (d.mono ? ' mono' : '') + (d.w ? ' wrap' : ''), parent, esc(d.text).replace(/\n/g, '<br>'));
        if (d.align) e.style.textAlign = d.align;
        parts.value = e;
        break;
      case 'formula':
        e = el('div', 'formula z-' + (d.size || 'l'), parent, d.html);
        break;
      case 'chips':
        e = el('div', 'chips' + (d.size === 'l' ? ' big' : ''), parent);
        parts.items = d.items.map(function (t, k) {
          if (k > 0 && d.joined !== false) el('span', 'chip-join', e);
          else if (k > 0) el('span', '', e).style.width = (d.gap == null ? 6 : d.gap) + 'px';
          var o = typeof t === 'object' ? t : { text: t };
          return el('span', 'chip' + (o.tone ? ' t-' + o.tone : ''), e, esc(o.text));
        });
        break;
      case 'cells':
        e = el('div', 'cells', parent);
        var cs = d.size || 18;
        e.style.gridTemplateColumns = 'repeat(' + (d.cols || d.n) + ',' + (d.cellW || cs) + 'px)';
        e.style.gap = (d.gap == null ? 6 : d.gap) + 'px';
        parts.items = [];
        for (i = 0; i < d.n; i++) { var c = el('span', 'cell', e); c.style.height = cs + 'px'; parts.items.push(c); }
        paintCells(parts.items, d.fill || []);
        break;
      case 'rows':
        e = el('div', 'rows', parent);
        e.style.gap = (d.gap == null ? 8 : d.gap) + 'px';
        parts.items = d.items.map(function (r) {
          var row = el('div', 'row' + (r.tone ? ' t-' + r.tone : '') + (r.on ? ' on' : ''), e);
          row.style.height = (d.rowH || 34) + 'px';
          var p = { el: row };
          p.label = el('span', 'r-label', row, esc(r.label));
          if (r.bar != null) { var tk = el('span', 'r-track', row); p.fill = el('span', 'r-fill', tk); p.fill.style.width = r.bar * 100 + '%'; }
          else el('span', '', row).style.flex = '1';
          if (r.meta != null) p.meta = el('span', 'r-meta', row, esc(r.meta));
          return p;
        });
        break;
      case 'stat':
        e = el('div', tone(d, 'stat z-' + (d.size || 'l')), parent);
        if (d.label) parts.label = el('div', 'st-label', e, esc(d.label));
        if (d.caption) el('div', 'st-sub', e, esc(d.caption));
        var num = el('div', 'st-num', e);
        if (d.prefix) el('span', 'p', num, esc(d.prefix));
        parts.value = el('span', '', num, esc(fmt(d.value, d.decimals)));
        if (d.unit) el('span', 'u', num, esc(d.unit));
        if (d.sub) parts.sub = el('div', 'st-sub', e, esc(d.sub));
        parts.num = d.value; parts.decimals = d.decimals;
        break;
      case 'meter':
        e = el('div', tone(d, 'meter'), parent);
        var mh = el('div', 'm-head', e);
        el('span', 'm-label', mh, esc(d.label || ''));
        var mv = el('span', 'm-val', mh);
        parts.value = el('span', '', mv, esc(d.text != null ? d.text : fmt(d.value, d.decimals)));
        if (d.unit) el('span', 'u', mv, esc(d.unit));
        var mt = el('div', 'm-track', e);
        parts.fill = el('div', 'm-fill', mt);
        parts.over = el('div', 'm-over', mt);
        if (d.threshold != null) {
          var tp = Math.min(d.threshold / d.max, 1) * 100;
          el('div', 'm-tick', mt).style.left = tp + '%';
          if (d.thresholdLabel) {
            // 끝에 가까운 기준선은 글자가 무대 밖으로 나가지 않게 한쪽으로 붙인다
            var tl = el('div', 'm-tl', mt, esc(d.thresholdLabel)); tl.style.left = tp + '%';
            if (tp < 12) tl.style.transform = 'translateX(0)'; else if (tp > 88) tl.style.transform = 'translateX(-100%)';
          }
        }
        parts.max = d.max; parts.threshold = d.threshold; parts.decimals = d.decimals; parts.overTone = d.overflow !== false;
        setMeter(parts, d.value || 0);
        break;
      case 'ring':
        e = el('div', tone(d, 'ring'), parent);
        var sz = Math.min(d.w || 160, d.h || 160), sw = d.stroke || Math.round(sz * 0.08), r0 = (sz - sw) / 2;
        var s = svgEl('svg', { viewBox: '0 0 ' + sz + ' ' + sz }, e);
        svgEl('circle', { 'class': 'rg-bg', cx: sz / 2, cy: sz / 2, r: r0, 'stroke-width': sw }, s);
        parts.arc = svgEl('circle', { 'class': 'rg-fg', cx: sz / 2, cy: sz / 2, r: r0, 'stroke-width': sw }, s);
        parts.circ = 2 * Math.PI * r0;
        var rt = el('div', 'rg-text', e);
        parts.value = el('div', 'rg-big', rt, esc(d.text != null ? d.text : Math.round((d.value || 0) * 1000) / 10 + '%'));
        if (d.sub) el('div', 'rg-sub', rt, esc(d.sub));
        setRing(parts, d.value || 0);
        break;
      case 'chart':
        e = el('div', 'chart', parent);
        parts.svg = svgEl('svg', { viewBox: '0 0 ' + d.w + ' ' + d.h, preserveAspectRatio: 'none' }, e);
        parts.def = d; parts.paths = [];
        drawChart(parts, d);
        break;
      case 'columns':
        e = el('div', tone(d, 'columns'), parent);
        e.style.gap = (d.gap == null ? 14 : d.gap) + 'px';
        parts.items = d.values.map(function (v, k) {
          var col = el('div', 'col' + ((d.on || []).indexOf(k) >= 0 ? ' on' : ''), e);
          var cv = el('div', 'col-v', col, esc(d.texts ? d.texts[k] : fmt(v)));
          var bar = el('div', 'col-bar', col);
          var lab = d.labels ? el('div', 'col-l', col, esc(d.labels[k])) : null;
          return { el: col, bar: bar, v: cv, l: lab };
        });
        parts.max = d.max || Math.max.apply(null, d.values); parts.def = d;
        setColumns(parts, d.values, d.h);
        break;
      case 'tabs':
        e = el('div', 'tabs', parent);
        parts.items = d.items.map(function (t, k) {
          return el('div', 'tab' + (k === (d.index || 0) ? ' on' : ''), e, '<span class="tn">' + String(k + 1).padStart(2, '0') + '</span>' + esc(t));
        });
        break;
      case 'code':
        e = el('div', 'code', parent);
        parts.items = d.lines.map(function (ln) {
          var o = typeof ln === 'object' ? ln : { text: ln };
          var txt = esc(o.text).replace(/(\/\/.*)$/, '<span class="cm">$1</span>');
          return el('div', 'ln' + (o.tone ? ' t-' + o.tone : ''), e, txt || ' ');
        });
        break;
      case 'icon':
        e = el('div', tone(d, 'icon' + (d.plain ? ' plain' : '')), parent);
        var ic = el('div', 'ic', e, icon(d.icon));
        var isz = d.size || 56; ic.style.width = isz + 'px'; ic.style.height = isz + 'px';
        if (d.label) parts.value = el('div', 'il', e, esc(d.label));
        break;
      case 'wave':
        e = el('div', tone(d, 'wave'), parent);
        e.style.gap = (d.gap == null ? 3 : d.gap) + 'px';
        parts.items = [];
        for (i = 0; i < (d.n || 40); i++) parts.items.push(el('i', '', e));
        parts.def = d; waves.push(parts);
        break;
      default:
        throw new Error('알 수 없는 요소 type: ' + d.type);
    }
    return { el: e, parts: parts };
  }

  function paintCells(items, fill) {
    items.forEach(function (c, i) { var k = fill[i]; c.className = 'cell' + (k ? ' k-' + k : ''); });
  }
  function setMeter(p, v) {
    var max = p.max || 1, thr = p.threshold;
    var base = thr != null && p.overTone ? Math.min(v, thr) : v;
    p.fill.style.width = Math.min(base / max, 1) * 100 + '%';
    if (thr != null && p.overTone && v > thr) { p.over.style.left = (thr / max) * 100 + '%'; p.over.style.width = (Math.min(v, max) - thr) / max * 100 + '%'; }
    else p.over.style.width = '0';
  }
  function setRing(p, v) { p.arc.setAttribute('stroke-dasharray', p.circ * Math.max(0, Math.min(v, 1)) + ' ' + p.circ); }
  function setColumns(p, vals, h) {
    var room = (h || 200) - 40;
    p.items.forEach(function (it, k) { it.bar.style.height = Math.max(2, (vals[k] / p.max) * room) + 'px'; });
  }

  // 선 그래프를 값에서 다시 그린다. reveal 은 0~1 이고 왼쪽부터 그 비율만큼 보인다
  function drawChart(p, d) {
    var svg = p.svg; svg.innerHTML = '';
    var w = d.w, h = d.h, padL = d.yLabel ? 44 : 8, padB = d.xLabels ? 22 : 6, padT = 14, padR = 8;
    var min = d.min != null ? d.min : 0, max = d.max != null ? d.max : 1;
    var iw = w - padL - padR, ih = h - padT - padB;
    function X(i, n) { return padL + (n <= 1 ? 0 : iw * i / (n - 1)); }
    function Y(v) { return padT + ih * (1 - (v - min) / (max - min)); }
    for (var g = 0; g <= 3; g++) svgEl('line', { 'class': 'grid', x1: padL, x2: w - padR, y1: padT + ih * g / 3, y2: padT + ih * g / 3 }, svg);
    if (d.yLabel) { var yl = svgEl('text', { x: 0, y: padT + 4 }, svg); yl.textContent = d.yLabel; }
    if (d.xLabels) d.xLabels.forEach(function (t, i) {
      var tx = svgEl('text', { x: X(i, d.xLabels.length), y: h - 4, 'text-anchor': i === 0 ? 'start' : i === d.xLabels.length - 1 ? 'end' : 'middle' }, svg); tx.textContent = t;
    });
    if (d.threshold != null) {
      var ty = Y(d.threshold), tt = d.thresholdTone || 'bad';
      svgEl('line', { 'class': 'thr t-' + tt, x1: padL, x2: w - padR, y1: ty, y2: ty }, svg);
      if (d.thresholdLabel) { var tl = svgEl('text', { 'class': 'thr-l t-' + tt, x: w - padR, y: ty - 6, 'text-anchor': 'end' }, svg); tl.textContent = d.thresholdLabel; }
    }
    var clipId = 'cl' + Math.random().toString(36).slice(2, 8);
    var cp = svgEl('clipPath', { id: clipId }, svgEl('defs', {}, svg));
    p.clip = svgEl('rect', { x: 0, y: -20, width: w * (p.reveal == null ? (d.reveal == null ? 1 : d.reveal) : p.reveal), height: h + 40 }, cp);
    p.paths = (p.series || d.series).map(function (s) {
      var n = s.values.length;
      var dstr = s.values.map(function (v, i) { return (i ? 'L' : 'M') + X(i, n).toFixed(1) + ' ' + Y(v).toFixed(1); }).join(' ');
      if (d.smooth !== false) dstr = smooth(s.values.map(function (v, i) { return [X(i, n), Y(v)]; }));
      var path = svgEl('path', { d: dstr, 'class': 'ser' + (s.tone ? ' t-' + s.tone : '') + (s.dash ? ' dash' : ''), 'clip-path': 'url(#' + clipId + ')' }, svg);
      if (s.label) { var last = s.values[n - 1]; var lt = svgEl('text', { 'class': 'lg', x: w - padR, y: Y(last) - 6, 'text-anchor': 'end', 'clip-path': 'url(#' + clipId + ')' }, svg); lt.textContent = s.label; }
      return path;
    });
    p.series = p.series || d.series.map(function (s) { return JSON.parse(JSON.stringify(s)); });
  }
  function smooth(pts) {
    if (pts.length < 3) return pts.map(function (q, i) { return (i ? 'L' : 'M') + q[0] + ' ' + q[1]; }).join(' ');
    var s = 'M' + pts[0][0].toFixed(1) + ' ' + pts[0][1].toFixed(1);
    for (var i = 0; i < pts.length - 1; i++) {
      var p0 = pts[i - 1] || pts[i], p1 = pts[i], p2 = pts[i + 1], p3 = pts[i + 2] || p2;
      var c1 = [p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6], c2 = [p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6];
      s += ' C' + c1[0].toFixed(1) + ' ' + c1[1].toFixed(1) + ' ' + c2[0].toFixed(1) + ' ' + c2[1].toFixed(1) + ' ' + p2[0].toFixed(1) + ' ' + p2[1].toFixed(1);
    }
    return s;
  }

  /* ---------- 재생 ---------- */

  var sim = 0, timers = [], movers = [], waves = [], paused = false, loopStart = 0;
  var DIM = 0.32;
  // 나타나기와 흐리기를 녹화 시계로 돌린다. 시작 값은 지금 opacity 다
  function fade(e, to, ms) {
    var from = parseFloat(e.style.opacity); if (isNaN(from)) from = 1;
    if (e._fade) e._fade.cancel = true;
    var job = { cancel: false }; e._fade = job;
    tween(ms == null ? 400 : ms, function (k) { if (!job.cancel) e.style.opacity = from + (to - from) * k; });
  }
  function after(ms, fn) { timers.push({ t: sim + ms, fn: fn }); }
  function tween(ms, apply, done) { movers.push({ tween: true, t0: sim, dur: Math.max(ms, 1), apply: apply, done: done }); }

  function dotPath(p) {
    var out = [];
    p.forEach(function (it) {
      if (it && it.wire) {
        var w = reg[it.wire]; if (!w || !w.pts) throw new Error('알 수 없는 wire: ' + it.wire);
        var pts = w.pts.slice(); if (it.rev) pts.reverse();
        pts.forEach(function (q) { push(out, q, 'none'); });
      } else push(out, anchor(it), 'hvh');
    });
    return out;
  }
  function push(out, q, elbow) {
    if (!out.length) { out.push(q); return; }
    var a = out[out.length - 1];
    if (a[0] === q[0] && a[1] === q[1]) return;
    var r = route([a, q], elbow); for (var i = 1; i < r.length; i++) out.push(r[i]);
  }
  function ids(v) { return Array.isArray(v) ? v : [v]; }
  function need(id) { var r = reg[id]; if (!r) throw new Error('없는 id: ' + id); return r; }
  function inherited(d, key) { var p = d.parent; while (p) { var pd = reg[p] && reg[p].def; if (!pd) return false; if (pd[key]) return true; p = pd.parent; } return false; }
  // 요소와 그 안에 parent 로 놓인 요소를 모두 모은다
  function family(id) {
    var out = [id];
    Object.keys(reg).forEach(function (k) {
      var p = reg[k].def && reg[k].def.parent;
      while (p) { if (p === id) { out.push(k); break; } p = reg[p] && reg[p].def && reg[p].def.parent; }
    });
    return out;
  }
  function visEls(r) { var a = [r.el]; if (r.labelEl) a.push(r.labelEl); return a; }
  function famEls(id) { var a = []; family(id).forEach(function (k) { if (reg[k].el) a = a.concat(visEls(reg[k])); }); return a; }

  function exec(s, done) {
    var fin = s.async ? function () {} : done;
    if (s.async) done();
    var keys = Object.keys(s).filter(function (k) { return k !== 'async'; });
    if (s.wait != null && keys.length === 1) { after(s.wait, fin); return; }
    if (s.par) { var left = s.par.length; if (!left) return fin(); s.par.forEach(function (x) { exec(x, function () { if (--left === 0) fin(); }); }); return; }
    if (s.seq) { runSeq(s.seq, fin); return; }
    if (s.repeat) { var n = s.times || 2, k = 0; (function again() { if (k++ >= n) return fin(); runSeq(s.repeat, again); })(); return; }
    if (s.dot) {
      var pts = dotPath(s.dot), c = svgEl('circle', { r: s.r || 4.5, 'class': 'dot k-' + (s.color || 'fg') }, dotsLayer);
      var segs = [], total = 0;
      for (var i = 1; i < pts.length; i++) { var dd = dist(pts[i - 1], pts[i]); segs.push(dd); total += dd; }
      var m = { el: c, pts: pts, segs: segs, total: total, d: 0, keep: s.keep, done: fin, speed: s.speed || SPEED };
      place(m); movers.push(m); return;
    }
    if (s.stream) {
      // 같은 경로로 점 여러 개를 간격을 두고 흘려 보낸다
      var cnt = s.count || 4, gap = s.gap || 220, got = 0;
      for (var q = 0; q < cnt; q++) (function (q) { after(q * gap, function () { exec({ dot: s.stream, color: s.color, r: s.r, speed: s.speed }, function () { if (++got === cnt) fin(); }); }); })(q);
      return;
    }
    if (s.show || s.hide) {
      ids(s.show || s.hide).forEach(function (id) { need(id); famEls(id).forEach(function (e) {
        e.classList.toggle('hidden', !s.show); fade(e, s.show ? (e.classList.contains('dimmed') ? DIM : 1) : 0, s.fade);
      }); });
      after(s.ms || 0, fin); return;
    }
    if (s.dim || s.undim) {
      ids(s.dim || s.undim).forEach(function (id) { need(id); famEls(id).forEach(function (e) {
        e.classList.toggle('dimmed', !!s.dim); if (!e.classList.contains('hidden')) fade(e, s.dim ? DIM : 1, s.fade);
      }); });
      return fin();
    }
    if (s.focus) {
      var keep = ids(s.focus), scope = s.among;
      Object.keys(reg).forEach(function (id) {
        var r = reg[id]; if (!r.el || r.kind === 'wire') return;
        if (scope && ids(scope).indexOf(id) < 0) return;
        if (!scope && r.def.parent !== reg[keep[0]].def.parent) return;
        var off = keep.indexOf(id) < 0;
        famEls(id).forEach(function (e) { e.classList.toggle('dimmed', off); if (!e.classList.contains('hidden')) fade(e, off ? DIM : 1, s.fade); });
      });
      return fin();
    }
    if (s.unfocus) { ids(s.unfocus).forEach(function (id) { need(id); famEls(id).forEach(function (e) { e.classList.remove('dimmed'); if (!e.classList.contains('hidden')) fade(e, 1, s.fade); }); }); return fin(); }
    if (s.hl) { var t = need(s.hl).el; t.classList.add('hit'); after(s.ms || 600, function () { t.classList.remove('hit'); }); after(s.hold ? s.ms || 600 : 0, fin); return; }
    if (s.active) { need(s.active).el.classList.toggle('active', s.on !== false); return fin(); }
    if (s.retone) {
      var tr = need(s.retone), e = tr.el;
      e.className = e.className.replace(/ t-\w+/g, '') + (s.to ? ' t-' + s.to : '');
      return fin();
    }
    if (s.progress) {
      var f = need(s.progress).parts.fill, from = parseFloat(f.style.width) / 100 || 0, to = s.to;
      tween(s.ms || 800, function (k) { f.style.width = (from + (to - from) * k) * 100 + '%'; }, fin); return;
    }
    if (s.write) {
      var rr = need(s.write), part = rr.parts[s.field || 'value'];
      if (!part) throw new Error(s.write + ' 에는 ' + (s.field || 'value') + ' 칸이 없다');
      part.innerHTML = br(s.value); part.style.display = ''; return fin();
    }
    if (s.count) {
      // 숫자를 지금 값에서 to 까지 올리거나 내린다
      var cr = need(s.count), cp = cr.parts, c0 = s.from != null ? s.from : (typeof cp.num === 'number' ? cp.num : 0), c1 = s.to;
      var dec = s.decimals != null ? s.decimals : cp.decimals;
      tween(s.ms || 900, function (k) { var v = c0 + (c1 - c0) * k; cp.num = v; cp.value.textContent = fmt(v, dec); }, function () { cp.num = c1; cp.value.textContent = fmt(c1, dec); fin(); }); return;
    }
    if (s.meter) {
      var mr = need(s.meter).parts, m0 = mr.cur != null ? mr.cur : (need(s.meter).def.value || 0), m1 = s.to, mdec = s.decimals != null ? s.decimals : mr.decimals;
      tween(s.ms || 900, function (k) { var v = m0 + (m1 - m0) * k; mr.cur = v; setMeter(mr, v); if (s.text == null) mr.value.textContent = fmt(v, mdec); },
        function () { if (s.text != null) mr.value.textContent = s.text; fin(); }); return;
    }
    if (s.ring) {
      var rp = need(s.ring).parts, r0 = rp.cur != null ? rp.cur : (need(s.ring).def.value || 0), r1 = s.to;
      tween(s.ms || 900, function (k) { var v = r0 + (r1 - r0) * k; rp.cur = v; setRing(rp, v); if (s.text == null) rp.value.textContent = (Math.round(v * 1000) / 10) + '%'; },
        function () { if (s.text != null) rp.value.textContent = s.text; fin(); }); return;
    }
    if (s.chart) {
      var ch = need(s.chart).parts, d0 = ch.def;
      if (s.draw) {
        // 왼쪽에서 오른쪽으로 선을 그려 나간다
        ch.reveal = 0; drawChart(ch, d0);
        tween(s.ms || 1600, function (k) { ch.clip.setAttribute('width', d0.w * k); ch.reveal = k; }, fin); return;
      }
      var idx = s.series || 0, from2 = ch.series[idx].values.slice(), to2 = s.values;
      tween(s.ms || 1200, function (k) {
        ch.series[idx].values = from2.map(function (v, i) { return v + ((to2[i] != null ? to2[i] : v) - v) * k; }); drawChart(ch, d0);
      }, fin); return;
    }
    if (s.columns) {
      var cl = need(s.columns).parts, v0 = cl.cur || cl.def.values.slice(), v1 = s.values || v0;
      if (s.on) cl.items.forEach(function (it, k) { it.el.classList.toggle('on', ids(s.on).indexOf(k) >= 0); });
      if (s.texts) cl.items.forEach(function (it, k) { if (s.texts[k] != null) it.v.textContent = s.texts[k]; });
      tween(s.ms || 700, function (k) { var v = v0.map(function (a, i) { return a + (v1[i] - a) * k; }); cl.cur = v; setColumns(cl, v, cl.def.h); }, fin); return;
    }
    if (s.tab) { need(s.tab).parts.items.forEach(function (t, k) { t.classList.toggle('on', k === s.index); }); return fin(); }
    if (s.line) {
      var li = need(s.line).parts.items;
      ids(s.index).forEach(function (k) { var ln = li[k]; ln.className = 'ln' + (s.to ? ' t-' + s.to : ''); if (s.value != null) ln.textContent = s.value; });
      return fin();
    }
    if (s.cells) {
      var ce = need(s.cells).parts.items, cur = ce.map(function (c) { var mm = /k-(\w+)/.exec(c.className); return mm ? mm[1] : null; });
      if (s.set) cur = s.set.slice();
      if (s.fillTo != null) { cur = cur.map(function (c, i) { return i < s.fillTo ? (s.color || 'fg') : null; }); }
      if (s.push) { var ix = cur.indexOf(null); if (ix < 0) ix = cur.length - 1; cur[ix] = s.push; }
      if (s.pop) { cur.shift(); cur.push(null); }
      if (s.paint != null) ids(s.paint).forEach(function (k) { cur[k] = s.color || null; });
      while (cur.length < ce.length) cur.push(null);
      paintCells(ce, cur); return fin();
    }
    if (s.chip) {
      var it = need(s.chip).parts.items;
      if (s.to !== undefined) { ids(s.index).forEach(function (k) { it[k].className = 'chip' + (s.to ? ' t-' + s.to : ''); if (s.value != null) it[k].textContent = s.value; }); return fin(); }
      it.forEach(function (c, k) { c.classList.toggle('on', ids(s.index).indexOf(k) >= 0); });
      return fin();
    }
    if (s.row) {
      var rw = need(s.row).parts.items;
      if (s.on != null && rw[s.index]) rw[s.index].el.classList.toggle('on', !!s.on);
      else if (s.index != null && s.focus !== false) rw.forEach(function (p, i) { p.el.classList.toggle('on', i === s.index); });
      var pr = rw[s.index];
      if (pr && s.bar != null && pr.fill) pr.fill.style.width = s.bar * 100 + '%';
      if (pr && s.meta != null && pr.meta) pr.meta.textContent = s.meta;
      if (pr && s.label != null) pr.label.textContent = s.label;
      if (pr && s.tone !== undefined) pr.el.className = pr.el.className.replace(/ t-\w+/g, '') + (s.tone ? ' t-' + s.tone : '');
      if (s.index === -1) rw.forEach(function (q) { q.el.classList.remove('on'); });
      return fin();
    }
    throw new Error('알 수 없는 단계: ' + JSON.stringify(s));
  }
  function runSeq(steps, done) {
    var i = 0;
    (function next() { if (i >= steps.length) return done && done(); exec(steps[i++], next); })();
  }

  function place(m) {
    var d = Math.min(m.d, m.total), k = 0;
    while (k < m.segs.length - 1 && d > m.segs[k]) { d -= m.segs[k]; k++; }
    var a = m.pts[k], b = m.pts[k + 1] || a, f = m.segs[k] ? Math.min(d / m.segs[k], 1) : 1;
    m.el.setAttribute('cx', a[0] + (b[0] - a[0]) * f); m.el.setAttribute('cy', a[1] + (b[1] - a[1]) * f);
  }

  // 파형은 시간에 따라 높이가 바뀐다. 같은 시각이면 같은 모양이라 GIF 가 이음매 없이 돈다
  function tickWaves() {
    var ph = (sim - loopStart) / LOOP * Math.PI * 2;
    waves.forEach(function (p) {
      var d = p.def, n = p.items.length, h = d.h || 60, amp = d.amp == null ? 1 : d.amp;
      p.items.forEach(function (it, i) {
        var env = Math.sin(Math.PI * (i + 0.5) / n);
        var v = 0.25 + 0.75 * Math.abs(Math.sin(i * 0.9 + ph * 3) * 0.6 + Math.sin(i * 0.37 - ph * 5) * 0.4);
        it.style.height = Math.max(3, h * env * v * amp) + 'px';
      });
    });
  }

  function start() {
    waves = [];
    render(); timers = []; movers = []; loopStart = sim;
    (spec.timeline || []).forEach(function (track) {
      after(track.at || 0, function () {
        if (track.every) {
          (function rep() { runSeq(track.steps); if (sim + track.every < loopStart + LOOP - (track.until || 0)) after(track.every, rep); })();
        } else runSeq(track.steps);
      });
    });
  }

  var last = null;
  function frame(now) {
    if (last == null) last = now;
    var dt = Math.min(now - last, 100); last = now;
    if (!paused) {
      sim += dt;
      if (sim - loopStart >= LOOP) { sim = loopStart + LOOP; start(); }
      var guard = 0;
      timers.sort(function (a, b) { return a.t - b.t; });
      while (timers.length && timers[0].t <= sim && guard++ < 800) { timers.shift().fn(); timers.sort(function (a, b) { return a.t - b.t; }); }
      for (var i = movers.length - 1; i >= 0; i--) {
        var m = movers[i];
        if (m.tween) { var k = Math.min((sim - m.t0) / m.dur, 1); m.apply(1 - Math.pow(1 - k, 2)); if (k >= 1) { movers.splice(i, 1); m.done && m.done(); } continue; }
        m.d += dt * m.speed; place(m);
        if (m.d >= m.total) { movers.splice(i, 1); if (!m.keep) m.el.remove(); m.done(); }
      }
      tickWaves();
    }
    requestAnimationFrame(frame);
  }

  function toggle() { paused = !paused; }
  wrap.addEventListener('click', toggle);
  document.addEventListener('keydown', function (e) {
    if (e.code === 'Space') { e.preventDefault(); toggle(); }
    if (e.key === 'f' || e.key === 'F') {
      if (document.fullscreenElement) document.exitFullscreen();
      else if (document.documentElement.requestFullscreen) document.documentElement.requestFullscreen().catch(function () {});
    }
  });
  document.addEventListener('fullscreenchange', function () { setTimeout(fit, 50); });

  window.SLIDE = { loop: LOOP, ready: false, restart: function () { sim = 0; last = null; paused = false; start(); } };
  fit();
  try { start(); window.SLIDE.ready = true; } catch (err) {
    window.SLIDE.error = String(err && err.message || err);
    root.innerHTML = '<div class="err">' + esc(window.SLIDE.error) + '</div>';
  }
  requestAnimationFrame(frame);
})();
