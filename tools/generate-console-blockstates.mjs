// Regenerates the console blockstate table (tone x style x state x facing) and
// the per-tone wrapper models, which only swap the shared palette atlas.
// node tools/generate-console-blockstates.mjs
import { mkdirSync, writeFileSync } from 'node:fs';

const root = new URL('../common/src/main/resources/assets/retroconsole/', import.meta.url);
const styles = ['classic', 'compact', 'hifi', 'legacy', 'fullblock'];
const states = ['off', 'standby', 'running'];
const tones = ['cream', 'graphite', 'lavender'];
const rotations = { north: 0, south: 180, east: 90, west: 270 };

// Cream is the shipped model; the other finishes wrap it and override `#atlas`.
let wrappers = 0;
for (const style of styles) {
  for (const state of states) {
    for (const tone of tones) {
      if (tone !== 'cream') {
        const dir = new URL(`models/block/console/${style}/`, root);
        mkdirSync(dir, { recursive: true });
        writeFileSync(new URL(`${state}_${tone}.json`, dir), `${JSON.stringify({
          credit: `Console chassis finish (${tone}); wraps block/console/${style}/${state}.`,
          parent: `retroconsole:block/console/${style}/${state}`,
          textures: { atlas: `retroconsole:block/retro_console_atlas_${tone}` },
        }, null, 2)}\n`);
        wrappers++;
      }
    }
  }
}

const variants = {};
for (const style of styles) {
  for (const state of states) {
    for (const tone of tones) {
      const model = tone === 'cream'
        ? `retroconsole:block/console/${style}/${state}`
        : `retroconsole:block/console/${style}/${state}_${tone}`;
      for (const [facing, y] of Object.entries(rotations)) {
        variants[`facing=${facing},style=${style},state=${state},tone=${tone}`] = {
          model,
          ...(y ? { y } : {}),
        };
      }
    }
  }
}

writeFileSync(new URL('blockstates/retro_console.json', root), `${JSON.stringify({ variants }, null, 2)}\n`);
console.log(`Wrote ${wrappers} tone models and `
  + `${Object.keys(variants).length} variants to ${root.pathname}`);
