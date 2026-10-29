// The design-lab exporter keeps elements whose faces were all culled by its
// hidden-face pass; vanilla rejects an element with no faces. Drops them.
//   node tools/sanitize-console-models.mjs
import { readdirSync, readFileSync, writeFileSync } from 'node:fs';

const root = new URL('../common/src/main/resources/assets/retroconsole/models/block/console/', import.meta.url);
let dropped = 0;
for (const style of readdirSync(root, { withFileTypes: true })) {
  if (!style.isDirectory()) continue;
  for (const model of readdirSync(new URL(`${style.name}/`, root))) {
    if (!model.endsWith('.json')) continue;
    const file = new URL(`${style.name}/${model}`, root);
    const json = JSON.parse(readFileSync(file, 'utf8'));
    const kept = json.elements.filter(element => Object.keys(element.faces ?? {}).length > 0);
    const removed = json.elements.length - kept.length;
    if (!removed) continue;
    json.elements = kept;
    writeFileSync(file, `${JSON.stringify(json, null, 2)}\n`);
    dropped += removed;
    console.log(`${style.name}/${model}: dropped ${removed} element(s) without faces`);
  }
}
console.log(dropped ? `Removed ${dropped} hidden element(s)` : 'All elements have faces');
