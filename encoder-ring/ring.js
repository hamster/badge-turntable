// Encoder ring generator for Badge Turntable.
//
// The ring is a band of ArUco markers (OpenCV's DICT_4X4_50/100/250) wrapped
// around the turntable rim. Marker k sits at k * (360 / count) degrees, so any
// three visible markers give the table's absolute angle. See README.md.
//
// Shared by index.html (browser) and make-ring.mjs (Node). No dependencies.

// First 250 codes of OpenCV's DICT_4X4_1000 (DICT_4X4_50/100/250 are prefixes
// of it). Each is the marker's 4x4 inner bits, row-major, MSB first, 1 = white.
export const DICT_4X4 = [
  46386, 3994, 13101, 39238, 21662, 31181, 40494, 50418, 65242, 53078, 63889, 4519,
  3767, 10767, 9393, 9790, 18021, 26112, 27742, 30383, 34443, 45099, 52437, 56706,
  65095, 38001, 44260, 42324, 8483, 13423, 17429, 22450, 40655, 61643, 2222, 2345,
  6261, 1279, 3574, 7258, 5912, 10792, 12940, 14514, 9448, 12011, 11583, 19300,
  20526, 20499, 20884, 21864, 23873, 24471, 26625, 26727, 24868, 25065, 27410, 28645,
  26591, 32283, 32928, 33604, 35746, 37754, 33900, 34090, 34204, 40073, 40865, 47996,
  48132, 46683, 49096, 47019, 51743, 51554, 55640, 54229, 52376, 51104, 50487, 59741,
  63781, 64443, 60970, 63309, 13685, 35501, 30231, 2767, 1611, 11713, 18904, 17396,
  20278, 20435, 27108, 28871, 31342, 46314, 60751, 64743, 65190, 37, 67, 2696,
  2694, 623, 28, 151, 2103, 2609, 2502, 2817, 2555, 2904, 4226, 6189,
  4216, 4211, 4724, 4785, 6905, 4870, 3086, 3313, 1075, 3231, 3826, 3837,
  1868, 4004, 1839, 1461, 3985, 2011, 7908, 5177, 7552, 5576, 8075, 5562,
  7601, 8320, 10473, 8866, 10323, 10992, 8951, 10560, 8518, 10681, 11164, 11186,
  14538, 14382, 12295, 14567, 14921, 14949, 12893, 15240, 14621, 15315, 9799, 10112,
  12202, 11540, 9694, 9555, 12151, 13384, 15528, 15425, 13325, 13563, 13978, 15840,
  13674, 15625, 15853, 16324, 16236, 14286, 15708, 15734, 14256, 16151, 16383, 18661,
  17000, 18989, 16736, 18769, 16861, 19423, 22607, 23112, 22550, 20573, 23290, 23221,
  20771, 23434, 22809, 20789, 19561, 18113, 19979, 17503, 20057, 19843, 19837, 18392,
  18291, 23685, 24132, 22059, 23739, 21955, 24430, 24555, 23826, 21854, 25200, 25109,
  25026, 27424, 25413, 27484, 27483, 30732, 31439, 30847, 31104, 29157, 29044, 31158,
  29139, 31539, 25706, 26280, 28327, 28305, 25890, 28107, 26509, 27953,
];

export const DICTIONARIES = { '4X4_50': 50, '4X4_100': 100, '4X4_250': 250 };

// Marker counts that divide 360 evenly, so every marker sits on a whole-degree angle.
const NICE_COUNTS = [180, 120, 90, 72, 60, 45, 40, 36, 30, 24, 20, 18, 15, 12, 10, 9, 8, 6, 5, 4];

// Page sizes in mm, landscape so strips run along the long edge.
export const PAPER = {
  letter: { w: 279.4, h: 215.9, name: 'US Letter' },
  a4: { w: 297, h: 210, name: 'A4' },
};

// 4x6 in shipping label: w runs across the print head, h along the feed.
export const LABEL = { w: 101.6, h: 152.4 };

const PAPER_MARGIN = 12;
const PAPER_HEADER = 16;
const PAPER_FOOTER = 16;
const PAPER_CAPTION = 4.5;
const PAPER_ROW_GAP = 5;
const LABEL_MARGIN = 4;
const LABEL_CAPTION = 3.5;
const CROP = 2.5;

// 6x6 grid for one marker, true = black. The outer ring is the black border;
// the inner 4x4 holds the code bits.
export function markerGrid(id) {
  const code = DICT_4X4[id];
  const grid = [];
  for (let r = 0; r < 6; r++) {
    const row = [];
    for (let c = 0; c < 6; c++) {
      if (r === 0 || r === 5 || c === 0 || c === 5) {
        row.push(true);
      } else {
        const bit = 15 - ((r - 1) * 4 + (c - 1));
        row.push(((code >> bit) & 1) === 0);
      }
    }
    grid.push(row);
  }
  return grid;
}

// Works out marker size, count and spacing for a rim.
//   circumference or diameter (mm), height: usable rim band height (mm),
//   count: optional fixed marker count, dictSize: 50 | 100 | 250.
export function planRing(opts) {
  const circumference = opts.circumference > 0 ? opts.circumference : Math.PI * opts.diameter;
  const height = opts.height;
  const dictSize = opts.dictSize || 50;
  if (!(circumference > 0)) throw new Error('Enter the rim circumference or diameter.');
  if (!(height > 0)) throw new Error('Enter the band height.');

  // A marker is 6 cells; the band adds a one-cell quiet zone above and below.
  const cell = height / 8;
  const marker = cell * 6;
  // Neighbouring markers need at least two cells of white between them.
  const minPitch = marker + cell * 2;

  let count = opts.count > 0 ? Math.round(opts.count) : 0;
  if (!count) {
    count = NICE_COUNTS.find((n) => n <= dictSize && circumference / n >= minPitch) || 0;
    if (!count) throw new Error('The band is too tall for this rim: fewer than 4 markers fit. Use a smaller band height.');
  }
  if (count > dictSize) throw new Error(`${count} markers needs a bigger dictionary than DICT_4X4_${dictSize}.`);
  if (count < 4) throw new Error('Use at least 4 markers.');
  if (circumference / count < minPitch) {
    const max = Math.floor(circumference / minPitch);
    throw new Error(`${count} markers won't fit at this band height (max ${max}).`);
  }

  return {
    circumference,
    diameter: circumference / Math.PI,
    height,
    cell,
    marker,
    pitch: circumference / count,
    count,
    step: 360 / count,
    dictSize,
  };
}

export function markerAngle(plan, id) {
  return id * plan.step;
}

// Splits the ring into strips no longer than maxLen. Each marker sits centred in
// its own pitch-long slot, so strips butted end to end keep the pitch, and the
// ring's 0° is the centre of marker 0 (half a step after strip 1's left end).
export function splitStrips(plan, maxLen) {
  const perMax = Math.floor(maxLen / plan.pitch);
  if (perMax < 1) throw new Error('One marker is longer than the strip length available.');
  const stripCount = Math.ceil(plan.count / perMax);
  const per = Math.ceil(plan.count / stripCount);
  const strips = [];
  for (let s = 0; s < stripCount; s++) {
    const ids = [];
    for (let id = s * per; id < Math.min(plan.count, (s + 1) * per); id++) ids.push(id);
    strips.push({ index: s, total: stripCount, ids, length: ids.length * plan.pitch });
  }
  return strips;
}

// Black rectangles for one strip in strip coordinates (mm): u runs along the
// strip left to right, v runs down from the strip's top edge. Runs of black
// cells in a row are merged into one rectangle.
export function stripRects(plan, ids) {
  const rects = [];
  ids.forEach((id, i) => {
    const u0 = i * plan.pitch + (plan.pitch - plan.marker) / 2;
    const v0 = plan.cell;
    const grid = markerGrid(id);
    for (let r = 0; r < 6; r++) {
      let c = 0;
      while (c < 6) {
        if (!grid[r][c]) { c++; continue; }
        let end = c;
        while (end < 6 && grid[r][end]) end++;
        rects.push({ u: u0 + c * plan.cell, v: v0 + r * plan.cell, w: (end - c) * plan.cell, h: plan.cell });
        c = end;
      }
    }
  });
  return rects;
}

function fmtAngle(a) {
  return Number.isInteger(a) ? String(a) : a.toFixed(1);
}

export function stripCaption(plan, strip, ascii) {
  const first = strip.ids[0];
  const last = strip.ids[strip.ids.length - 1];
  const deg = ascii ? ' deg' : '°';
  let text = `Strip ${strip.index + 1}/${strip.total}  markers ${first}-${last}  ` +
    `${fmtAngle(markerAngle(plan, first))}-${fmtAngle(markerAngle(plan, last))}${deg}`;
  if (strip.index === 0) text += '  (0' + deg + ' start)';
  return text;
}

export function summary(plan) {
  return `${plan.count} markers x ${fmtAngle(plan.step)}°  |  rim ${plan.diameter.toFixed(1)} mm dia, ` +
    `${plan.circumference.toFixed(1)} mm around  |  band ${plan.height} mm, marker ${plan.marker.toFixed(1)} mm  |  ` +
    `ArUco DICT_4X4_${plan.dictSize}`;
}

const r2 = (n) => Math.round(n * 1000) / 1000;
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;');

function cropMarks(x, y, w, h) {
  // Short ticks just outside each corner, so nothing is printed on the strip edge itself.
  const g = 1;
  const lines = [
    [x - g - CROP, y, x - g, y], [x, y - g - CROP, x, y - g],
    [x + w + g, y, x + w + g + CROP, y], [x + w, y - g - CROP, x + w, y - g],
    [x - g - CROP, y + h, x - g, y + h], [x, y + h + g, x, y + h + g + CROP],
    [x + w + g, y + h, x + w + g + CROP, y + h], [x + w, y + h + g, x + w, y + h + g + CROP],
  ];
  return lines;
}

// ---- Paper (SVG) -----------------------------------------------------------

export function paperLayout(plan, paperKey) {
  const paper = PAPER[paperKey];
  if (!paper) throw new Error(`Unknown paper size "${paperKey}".`);
  const strips = splitStrips(plan, paper.w - 2 * PAPER_MARGIN);
  const rowH = PAPER_CAPTION + plan.height + PAPER_ROW_GAP;
  const rowsPerPage = Math.floor((paper.h - 2 * PAPER_MARGIN - PAPER_HEADER - PAPER_FOOTER + PAPER_ROW_GAP) / rowH);
  if (rowsPerPage < 1) throw new Error('The band is too tall to fit on the page.');
  const pages = [];
  for (let i = 0; i < strips.length; i += rowsPerPage) pages.push(strips.slice(i, i + rowsPerPage));
  return { paper, strips, pages, rowH };
}

// One SVG per page, sized in real millimetres so it prints at 100%.
export function paperSvgs(plan, paperKey) {
  const { paper, pages, rowH } = paperLayout(plan, paperKey);
  return pages.map((rows, pageIndex) => {
    const out = [];
    out.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${paper.w}mm" height="${paper.h}mm" viewBox="0 0 ${paper.w} ${paper.h}" font-family="Helvetica, Arial, sans-serif">`);
    out.push(`<rect width="${paper.w}" height="${paper.h}" fill="#fff"/>`);
    const m = PAPER_MARGIN;
    out.push(`<text x="${m}" y="${m + 4}" font-size="4.2" font-weight="bold" fill="#000">Badge Turntable encoder ring` +
      (pages.length > 1 ? ` (page ${pageIndex + 1} of ${pages.length})` : '') + `</text>`);
    out.push(`<text x="${m}" y="${m + 9}" font-size="2.8" fill="#333">${esc(summary(plan))}</text>`);
    out.push(`<text x="${m}" y="${m + 13}" font-size="2.8" fill="#333">Print at 100% (Actual size). Cut on the corner marks. ` +
      `Wrap strips in order with markers upright; strip 1's first marker is 0°.</text>`);

    rows.forEach((strip, i) => {
      const top = m + PAPER_HEADER + i * rowH;
      const y = top + PAPER_CAPTION;
      out.push(`<text x="${m + 3}" y="${r2(top + 3)}" font-size="2.8" fill="#333">${esc(stripCaption(plan, strip))}</text>`);
      out.push(`<rect x="${m}" y="${r2(y)}" width="${r2(strip.length)}" height="${plan.height}" fill="none" stroke="#ccc" stroke-width="0.15" stroke-dasharray="1 1"/>`);
      const path = stripRects(plan, strip.ids)
        .map((q) => `M${r2(m + q.u)} ${r2(y + q.v)}h${r2(q.w)}v${r2(q.h)}h${r2(-q.w)}z`).join('');
      out.push(`<path d="${path}" fill="#000"/>`);
      const ticks = cropMarks(m, y, strip.length, plan.height)
        .map(([x1, y1, x2, y2]) => `M${r2(x1)} ${r2(y1)}L${r2(x2)} ${r2(y2)}`).join('');
      out.push(`<path d="${ticks}" stroke="#000" stroke-width="0.2"/>`);
    });

    // 100 mm scale bar to check the printer didn't scale the page.
    const by = paper.h - m - 6;
    out.push(`<path d="M${m} ${by}h100" stroke="#000" stroke-width="0.4"/>`);
    const ticks = [];
    for (let t = 0; t <= 100; t += 10) ticks.push(`M${m + t} ${by}v${t % 50 === 0 ? -3 : -1.8}`);
    out.push(`<path d="${ticks.join('')}" stroke="#000" stroke-width="0.3"/>`);
    out.push(`<text x="${m + 104}" y="${by}" font-size="2.8" fill="#333">This bar must measure exactly 100 mm. If it doesn't, reprint at 100% / Actual size.</text>`);
    out.push('</svg>');
    return out.join('\n');
  });
}

// ---- 4x6 label (ZPL + SVG preview) ----------------------------------------

// Strips run along the 6 in feed direction, side by side across the 4 in width.
// Each strip is rotated so its "up" points to +x (the caption sits on its right).
export function labelLayout(plan) {
  const strips = splitStrips(plan, LABEL.h - 2 * LABEL_MARGIN);
  const colW = plan.height + LABEL_CAPTION;
  const cols = Math.floor((LABEL.w - 2 * LABEL_MARGIN) / colW);
  if (cols < 1) throw new Error('The band is too tall to fit across a 4 in label.');
  const labels = [];
  for (let i = 0; i < strips.length; i += cols) labels.push(strips.slice(i, i + cols));
  return { strips, labels, colW };
}

// Maps a strip-coordinate rectangle onto the label (mm).
function labelRect(plan, colX, q) {
  return { x: colX + plan.height - (q.v + q.h), y: LABEL_MARGIN + q.u, w: q.h, h: q.w };
}

export function labelZpl(plan, dpi) {
  const d = dpi / 25.4;
  const dots = (mm) => Math.round(mm * d);
  const { labels, colW } = labelLayout(plan);
  const out = [];
  labels.forEach((cols, labelIndex) => {
    out.push(`^XA`);
    out.push(`^FX Badge Turntable encoder ring, label ${labelIndex + 1} of ${labels.length}, ${dpi} dpi^FS`);
    out.push(`^CI28^PW${dots(LABEL.w)}^LL${dots(LABEL.h)}^LH0,0`);
    cols.forEach((strip, j) => {
      const colX = LABEL_MARGIN + j * colW;
      for (const q of stripRects(plan, strip.ids)) {
        const r = labelRect(plan, colX, q);
        // Round edges, not sizes, so neighbouring cells tile without gaps.
        const x0 = dots(r.x), y0 = dots(r.y);
        const w = dots(r.x + r.w) - x0, h = dots(r.y + r.h) - y0;
        out.push(`^FO${x0},${y0}^GB${w},${h},${Math.min(w, h)}^FS`);
      }
      const t = Math.max(2, dots(0.25));
      for (const [x1, y1, x2, y2] of cropMarks(colX, LABEL_MARGIN, plan.height, strip.length)) {
        const x = dots(Math.min(x1, x2)), y = dots(Math.min(y1, y2));
        out.push(`^FO${x},${y}^GB${Math.max(t, dots(Math.abs(x2 - x1)))},${Math.max(t, dots(Math.abs(y2 - y1)))},${t}^FS`);
      }
      const th = dots(2.2);
      out.push(`^FO${dots(colX + plan.height + 0.6)},${dots(LABEL_MARGIN)}^A0R,${th},${th}^FD${stripCaption(plan, strip, true)}^FS`);
    });
    out.push(`^XZ`);
  });
  return out.join('\n') + '\n';
}

// SVG preview of each label, matching the ZPL layout.
export function labelSvgs(plan) {
  const { labels, colW } = labelLayout(plan);
  return labels.map((cols) => {
    const out = [];
    out.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${LABEL.w}mm" height="${LABEL.h}mm" viewBox="0 0 ${LABEL.w} ${LABEL.h}" font-family="Helvetica, Arial, sans-serif">`);
    out.push(`<rect x="0.2" y="0.2" width="${LABEL.w - 0.4}" height="${LABEL.h - 0.4}" rx="3" fill="#fff" stroke="#bbb" stroke-width="0.4"/>`);
    cols.forEach((strip, j) => {
      const colX = LABEL_MARGIN + j * colW;
      const path = stripRects(plan, strip.ids).map((q) => {
        const r = labelRect(plan, colX, q);
        return `M${r2(r.x)} ${r2(r.y)}h${r2(r.w)}v${r2(r.h)}h${r2(-r.w)}z`;
      }).join('');
      out.push(`<path d="${path}" fill="#000"/>`);
      const ticks = cropMarks(colX, LABEL_MARGIN, plan.height, strip.length)
        .map(([x1, y1, x2, y2]) => `M${r2(x1)} ${r2(y1)}L${r2(x2)} ${r2(y2)}`).join('');
      out.push(`<path d="${ticks}" stroke="#000" stroke-width="0.25"/>`);
      const tx = r2(colX + plan.height + 0.6);
      out.push(`<text x="${tx}" y="${LABEL_MARGIN}" font-size="2.2" fill="#000" transform="rotate(90 ${tx} ${LABEL_MARGIN})">${esc(stripCaption(plan, strip))}</text>`);
    });
    out.push('</svg>');
    return out.join('\n');
  });
}

// ---- Illustration ------------------------------------------------------------

// What the camera sees: the ring wrapped round the rim, at table angle theta
// (the ring angle facing the camera). Used for the docs; not to scale.
export function cameraViewSvg(plan, theta) {
  const W = 320, H = 140, xc = 160, R = 130, b = 26, yc = 70;
  const s = R / (plan.circumference / (2 * Math.PI)); // drawing units per mm
  const band = plan.height * s;
  const toXY = (u, v) => {
    // The strip starts half a step before marker 0, whose centre is 0°.
    const phi = ((u / plan.circumference) * 360 - plan.step / 2 - theta) * Math.PI / 180;
    return [xc + R * Math.sin(phi), yc + b * Math.cos(phi) + v * s, Math.cos(phi)];
  };
  const out = [];
  out.push(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" font-family="Helvetica, Arial, sans-serif">`);
  out.push(`<rect width="${W}" height="${H}" fill="#fff"/>`);
  // Rim band (front half) and platter top.
  const front = [];
  for (let a = -90; a <= 90; a += 3) {
    const p = a * Math.PI / 180;
    front.push([xc + R * Math.sin(p), yc + b * Math.cos(p)]);
  }
  const bandPath = 'M' + front.map(([x, y]) => `${r2(x)} ${r2(y)}`).join('L') +
    'L' + front.slice().reverse().map(([x, y]) => `${r2(x)} ${r2(y + band)}`).join('L') + 'Z';
  out.push(`<path d="${bandPath}" fill="#fafafa" stroke="#888" stroke-width="0.6"/>`);
  out.push(`<ellipse cx="${xc}" cy="${yc}" rx="${R}" ry="${b}" fill="#e6e6e6" stroke="#888" stroke-width="0.6"/>`);

  // Markers: each black run is a quad mapped onto the cylinder; skip the back half.
  const all = Array.from({ length: plan.count }, (_, i) => i);
  const path = [];
  for (const q of stripRects(plan, all)) {
    const corners = [toXY(q.u, q.v), toXY(q.u + q.w, q.v), toXY(q.u + q.w, q.v + q.h), toXY(q.u, q.v + q.h)];
    if (corners.some((c) => c[2] < 0.12)) continue;
    path.push('M' + corners.map(([x, y]) => `${r2(x)} ${r2(y)}`).join('L') + 'Z');
  }
  out.push(`<path d="${path.join('')}" fill="#111"/>`);

  // A badge standing on the platter, and the camera axis.
  out.push(`<rect x="${xc - 22}" y="${yc - 58}" width="44" height="56" rx="5" fill="#2f6f4f" stroke="#1d4a33" stroke-width="0.8"/>`);
  out.push(`<rect x="${xc - 6}" y="${yc - 55}" width="12" height="3" rx="1.5" fill="#fff"/>`);
  out.push(`<rect x="${xc - 14}" y="${yc - 42}" width="28" height="18" rx="2" fill="#173a2a"/>`);
  out.push(`<path d="M${xc} ${yc - 62}V${yc + b + band + 14}" stroke="#d33" stroke-width="0.6" stroke-dasharray="3 2"/>`);
  out.push(`<text x="${xc + 3}" y="${yc + b + band + 13}" font-size="6" fill="#d33">camera axis: table angle = ${fmtAngle(theta)}°</text>`);
  out.push('</svg>');
  return out.join('\n');
}
