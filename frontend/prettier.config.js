/**
 * The frontend's layout, applied by `npm run format` and checked by `npm run format:check` in CI.
 *
 * Two options differ from Prettier's defaults. Both were chosen because they moved the fewest lines of
 * the code as it stood: the sources already used single quotes, and 120 columns kept most existing lines
 * whole. Everything else is Prettier's default, including semicolons, which is also why
 * eslint-config-prettier is not needed. `.editorconfig` supplies the indentation and line endings, and
 * `.prettierignore` says what is left alone and why. See
 * `docs/ADRs/ADR-038-the-frontend-is-formatted-by-prettier.md`.
 *
 * @type {import('prettier').Config}
 */
export default {
  singleQuote: true,
  printWidth: 120,
};
