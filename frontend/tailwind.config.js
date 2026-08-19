/**
 * Every colour in the app is a semantic token, not a palette shade.
 *
 * Tokens are declared as `rgb(var(--color-x) / <alpha-value>)` rather than as a hex string because two
 * call sites need Tailwind's opacity modifier on a colour that must theme: the modal scrim
 * (`bg-overlay/50`) and the unread notification row (`bg-brand-soft/50`). A hex-valued custom property
 * cannot take that modifier; space-separated channels can, and Tailwind composes the alpha itself.
 *
 * The values live in `src/index.css`, once per theme.
 *
 * `darkMode: 'class'` rather than `'selector'`: the declared dependency range is `^3.4.0` and
 * `'selector'` only landed in 3.4.1, so a fresh install resolving 3.4.0 exactly would break. It also
 * compiles to a `.dark` descendant selector rather than `:where(.dark, .dark *)`, which has zero
 * specificity — the higher specificity is what makes a one-off `dark:` override stick.
 *
 * @type {import('tailwindcss').Config}
 */

/** @param {string} name the token's name, without the `--color-` prefix */
const token = (name) => `rgb(var(--color-${name}) / <alpha-value>)`;

export default {
  content: ["./index.html", "./src/**/*.{js,ts,jsx,tsx}"],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        canvas: token('canvas'),
        surface: token('surface'),
        // Named `sunken` and never `inset`: Tailwind merges a colour named `inset` into its existing
        // `ring-inset` utility, which would silently recolour any inset ring.
        sunken: token('sunken'),
        muted: { DEFAULT: token('muted'), strong: token('muted-strong') },
        line: { DEFAULT: token('line'), subtle: token('line-subtle'), strong: token('line-strong') },
        fg: {
          DEFAULT: token('fg'),
          muted: token('fg-muted'),
          subtle: token('fg-subtle'),
          faint: token('fg-faint'),
          inverted: token('fg-inverted'),
        },
        overlay: token('overlay'),
        focus: token('focus'),

        brand: {
          DEFAULT: token('brand'),
          hover: token('brand-hover'),
          soft: token('brand-soft'),
          'soft-hover': token('brand-soft-hover'),
          line: token('brand-line'),
          fg: token('brand-fg'),
        },
        success: {
          DEFAULT: token('success'),
          soft: token('success-soft'),
          line: token('success-line'),
          fg: token('success-fg'),
        },
        warning: { soft: token('warning-soft'), line: token('warning-line'), fg: token('warning-fg') },
        danger: {
          DEFAULT: token('danger'),
          hover: token('danger-hover'),
          soft: token('danger-soft'),
          line: token('danger-line'),
          fg: token('danger-fg'),
        },
        info: { soft: token('info-soft'), line: token('info-line'), fg: token('info-fg') },
        reminder: { soft: token('reminder-soft'), line: token('reminder-line'), fg: token('reminder-fg') },
        streak: { soft: token('streak-soft'), line: token('streak-line'), fg: token('streak-fg') },
        accent: { soft: token('accent-soft'), fg: token('accent-fg') },

        // Decorative category tints, for `Badge` only. Deliberately independent of the semantic families
        // above: `tint-red` on a MEDICAL_PREVENTIVE badge is a category colour, not a danger signal, and
        // must not move when the danger hue does. Grey has no hue meaning, so it reuses `muted`.
        tint: {
          green: { soft: token('tint-green-soft'), fg: token('tint-green-fg') },
          yellow: { soft: token('tint-yellow-soft'), fg: token('tint-yellow-fg') },
          blue: { soft: token('tint-blue-soft'), fg: token('tint-blue-fg') },
          red: { soft: token('tint-red-soft'), fg: token('tint-red-fg') },
          purple: { soft: token('tint-purple-soft'), fg: token('tint-purple-fg') },
        },
      },

      // Preflight paints every element's default border colour, and out of the box that is gray-200 —
      // a light rule that would survive into the dark theme on any element carrying `border` with no
      // colour class of its own.
      borderColor: { DEFAULT: token('line') },

      // One focus colour for the whole app, so `focus:ring-2` needs no colour class at the call site.
      // Without the opacity override Tailwind applies `ringOpacity.DEFAULT` of 0.5 to the base ring.
      ringColor: { DEFAULT: 'rgb(var(--color-focus))' },
      ringOpacity: { DEFAULT: '1' },
      // Tailwind's default offset colour is `#fff`. Left alone, every control using `ring-offset-2`
      // draws a white halo against a near-black page.
      ringOffsetColor: { DEFAULT: 'rgb(var(--color-surface))' },

      // Declared here rather than as an arbitrary `animate-[fadeIn_...]` value at the call site: the
      // arbitrary form emits the `animation` property but no `@keyframes`, so it names a keyframe that
      // does not exist and the element never animates.
      keyframes: {
        'fade-in': {
          from: { opacity: '0', transform: 'translateY(-4px)' },
          to: { opacity: '1', transform: 'none' },
        },
      },
      animation: { 'fade-in': 'fade-in 0.2s ease-out' },
    },
  },
  plugins: [],
}
