#!/usr/bin/env node
// Command-line encoder ring generator. Same output as index.html.
//
//   node make-ring.mjs --diameter 200 --height 16                 Letter SVG pages
//   node make-ring.mjs --circumference 630 --height 16 --paper a4
//   node make-ring.mjs --diameter 200 --height 16 --zpl --dpi 203  4x6 label ZPL + SVG previews
//   node make-ring.mjs --docs                                     regenerate docs/ images
//   node make-ring.mjs --diameter 200 --height 16 --pgm ring.pgm  whole ring as one raster strip,
//                                                                 for testing the detector

import { writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { planRing, paperSvgs, labelZpl, labelSvgs, cameraViewSvg, stripRects, summary } from './ring.js';

const here = dirname(fileURLToPath(import.meta.url));

function parseArgs(argv) {
  const args = { paper: 'letter', dpi: 203, dict: 50, height: 16, out: '.', pxPerMm: 8 };
  for (let i = 0; i < argv.length; i++) {
    const key = argv[i].replace(/^--/, '');
    const flag = ['zpl', 'docs', 'help'].includes(key);
    if (flag) { args[key] = true; continue; }
    const value = argv[++i];
    if (value === undefined) throw new Error(`--${key} needs a value`);
    args[key.replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = isNaN(value) ? value : Number(value);
  }
  return args;
}

function write(dir, name, text) {
  mkdirSync(dir, { recursive: true });
  const path = join(dir, name);
  writeFileSync(path, text);
  console.log('wrote', path);
}

// The whole ring as one continuous strip, white background, as a binary PGM.
function ringPgm(plan, pxPerMm) {
  const w = Math.round(plan.circumference * pxPerMm);
  const h = Math.round(plan.height * pxPerMm);
  const px = new Uint8Array(w * h).fill(255);
  const ids = Array.from({ length: plan.count }, (_, i) => i);
  for (const q of stripRects(plan, ids)) {
    const x0 = Math.round(q.u * pxPerMm), x1 = Math.round((q.u + q.w) * pxPerMm);
    const y0 = Math.round(q.v * pxPerMm), y1 = Math.round((q.v + q.h) * pxPerMm);
    for (let y = y0; y < y1; y++) px.fill(0, y * w + x0, y * w + x1);
  }
  return Buffer.concat([Buffer.from(`P5\n${w} ${h}\n255\n`), Buffer.from(px)]);
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    console.log(readmeUsage);
    return;
  }

  if (args.docs) {
    // Example ring for the README: a 200 mm table with a 16 mm band.
    const plan = planRing({ diameter: 200, height: 16, dictSize: 50 });
    const docs = join(here, 'docs');
    paperSvgs(plan, 'letter').forEach((svg, i) => write(docs, `example-letter-p${i + 1}.svg`, svg));
    write(docs, 'example-label-4x6.svg', labelSvgs(plan)[0]);
    write(docs, 'example-label-4x6-203dpi.zpl', labelZpl(plan, 203));
    write(docs, 'camera-view.svg', cameraViewSvg(plan, 20));
    return;
  }

  const plan = planRing({
    circumference: args.circumference,
    diameter: args.diameter,
    height: args.height,
    count: args.count,
    dictSize: args.dict,
  });
  console.log(summary(plan));

  if (args.pgm) {
    writeFileSync(args.pgm, ringPgm(plan, args.pxPerMm));
    console.log('wrote', args.pgm);
    return;
  }
  if (args.zpl) {
    write(args.out, `ring-4x6-${args.dpi}dpi.zpl`, labelZpl(plan, args.dpi));
    labelSvgs(plan).forEach((svg, i) => write(args.out, `ring-4x6-preview-${i + 1}.svg`, svg));
  } else {
    paperSvgs(plan, args.paper).forEach((svg, i) => write(args.out, `ring-${args.paper}-p${i + 1}.svg`, svg));
  }
}

const readmeUsage = `Usage: node make-ring.mjs (--diameter MM | --circumference MM) [options]

  --height MM        usable rim band height (default 16)
  --count N          marker count (default: the most that fit, dividing 360 evenly)
  --dict 50|100|250  ArUco DICT_4X4 size (default 50)
  --paper letter|a4  paper size for SVG pages (default letter)
  --zpl              write ZPL for 4x6 in labels instead of paper SVGs
  --dpi 203|300      label printer resolution (default 203)
  --out DIR          output folder (default .)
  --pgm FILE         write the whole ring as one raster strip (testing)
  --px-per-mm N      raster resolution for --pgm (default 8)
  --docs             regenerate the README images in docs/`;

try {
  main();
} catch (err) {
  console.error('error:', err.message);
  process.exit(1);
}
