#!/usr/bin/env node
/**
 * Fails if any Tailwind palette colour is used directly in the files Tailwind compiles.
 *
 * Tailwind fails silently: `bg-surfce` emits no CSS and raises no error, so the element renders with no
 * background at all — which on a dark canvas reads as a deliberate transparent panel rather than a
 * mistake. There is no compiler for class names. This is the substitute.
 *
 * It catches the opposite mistake too: a literal `bg-white` reintroduced by a copy-paste would render
 * correctly in light and be invisible against dark text. Colours belong in `src/index.css`, once per
 * theme, and are reached through the semantic tokens declared in `tailwind.config.js`.
 *
 * Scope follows the `content` glob in `tailwind.config.js`, which is `index.html` as well as `src/`.
 * A palette class in the HTML shell compiles exactly like one in a component, so checking only `src/`
 * would leave a hole in the one guard that exists to have none.
 *
 * Known boundary: this matches named utilities, so arbitrary values (`bg-[#fff]`, `text-[rgb(0,0,0)]`)
 * pass through. Nothing in the app uses that form, and a regex over arbitrary CSS would cost more in
 * false positives than it buys.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const FRONTEND = join(fileURLToPath(new URL('.', import.meta.url)), '..');
const SRC = join(FRONTEND, 'src');
const HTML_SHELL = join(FRONTEND, 'index.html');

const PALETTE = [
  'slate', 'gray', 'zinc', 'neutral', 'stone', 'red', 'orange', 'amber', 'yellow', 'lime', 'green',
  'emerald', 'teal', 'cyan', 'sky', 'blue', 'indigo', 'violet', 'purple', 'fuchsia', 'pink', 'rose',
].join('|');

/**
 * Utility prefixes that take a colour, including the side-suffixed border forms.
 *
 * `border-t-blue-600` is in here deliberately: `LoadingSpinner` used exactly that shape, and a pattern
 * anchored on `border-blue-600` walks straight past it.
 */
const PREFIX = '(?:bg|text|border|ring|ring-offset|divide|placeholder|from|via|to|decoration|outline|accent|caret|shadow|fill|stroke)(?:-[trblxyse])?';

const NUMBERED = new RegExp(`\\b${PREFIX}-(?:${PALETTE})-\\d{2,3}\\b`, 'g');
const NAMED = new RegExp(`\\b${PREFIX}-(?:white|black)\\b`, 'g');

/** Every `.ts`/`.tsx` file under `src/`, depth-first. */
const sourceFiles = (dir) =>
  readdirSync(dir).flatMap((entry) => {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) return sourceFiles(path);
    return /\.tsx?$/.test(path) ? [path] : [];
  });

/** Everything Tailwind compiles class names out of: the HTML shell plus the component tree. */
const compiledFiles = () => [HTML_SHELL, ...sourceFiles(SRC)];

const findings = compiledFiles().flatMap((path) =>
  readFileSync(path, 'utf8')
    .split('\n')
    .flatMap((line, index) => {
      const hits = [...line.matchAll(NUMBERED), ...line.matchAll(NAMED)].map((m) => m[0]);
      return hits.length
        ? [`${relative(FRONTEND, path)}:${index + 1}  ${[...new Set(hits)].join(', ')}`]
        : [];
    }),
);

if (findings.length > 0) {
  console.error('Palette colours used directly. Use a semantic token from tailwind.config.js instead:\n');
  findings.forEach((f) => console.error(`  ${f}`));
  console.error(`\n${findings.length} line(s).`);
  process.exit(1);
}

console.log(`No palette colours — all ${compiledFiles().length} compiled files use tokens.`);
