import { afterEach, beforeEach } from 'vitest';
import { cleanup } from '@testing-library/react';
import { installMatchMedia } from './test/matchMediaStub';

/**
 * Per-test isolation.
 *
 * `localStorage` and the `dark` class on `<html>` both outlive a test — they live on the jsdom window,
 * not on the rendered tree — so a test that sets either would otherwise decide the outcome of the next
 * one. Resetting here rather than in each file is what keeps the suite order-independent.
 *
 * `matchMedia` is stubbed for every test, not only the ones that care, so no test silently depends on
 * whatever jsdom reports by default.
 */
beforeEach(() => {
  localStorage.clear();
  document.documentElement.className = '';
  installMatchMedia(false);
});

// Testing Library only auto-cleans when Vitest's globals are on. They are off here — see ADR-004.
afterEach(() => {
  cleanup();
});
