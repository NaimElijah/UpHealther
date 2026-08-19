/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{js,ts,jsx,tsx}"],
  theme: {
    extend: {
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
