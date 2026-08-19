import React from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import { ThemeProvider } from './ThemeProvider';
import { useTheme } from '../hooks/useTheme';
import { installMatchMedia } from '../test/matchMediaStub';
// Vite's `?raw` rather than `node:fs`: it resolves the file the same way the build does, and it keeps
// the app's `tsconfig.json` free of Node types — see ADR-004.
import indexHtml from '../../index.html?raw';

/**
 * The contract between the pre-paint boot script in `index.html` and `ThemeProvider`.
 *
 * The theme resolution rule is implemented twice — once in inline ES5 that runs during head parse, and
 * once in the provider that takes over on mount — because nothing else can put the class on `<html>`
 * before the first paint. The duplication is deliberate; the two silently drifting apart is not.
 *
 * If they disagree, the page paints one theme and then flips to the other. That is precisely the defect
 * the boot script exists to prevent, it is invisible to every other test in this suite, and it is
 * invisible to a reviewer reading either file on its own. So the agreement is asserted here rather than
 * asserted in a comment.
 *
 * The script is read from `index.html` and executed verbatim rather than reimplemented, because a copy
 * of it in this file would be one more thing that can drift.
 */
const BOOT_SCRIPT = (() => {
  // The classic inline script, as opposed to the module script that loads the bundle.
  const inline = [...indexHtml.matchAll(/<script(?![^>]*\b(?:src|type)=)[^>]*>([\s\S]*?)<\/script>/g)];

  if (inline.length !== 1) {
    throw new Error(
      `Expected exactly one classic inline script in index.html, found ${inline.length}. ` +
        'If the boot script moved or was split, this contract test needs to follow it.',
    );
  }

  return inline[0][1];
})();

/**
 * Runs the shipped boot script against the current window.
 *
 * `new Function` rather than a jsdom `<script>` insertion: it keeps execution synchronous and in this
 * realm, so the stubbed `matchMedia` and `localStorage` are the ones the script sees.
 *
 * The usual objection to `new Function` does not apply. Nothing is interpolated into the body — it is
 * the literal contents of a version-controlled file in this repository, read whole, and executing it
 * unaltered is the entire point: a reimplementation here could drift from the shipped script exactly
 * the way the shipped script can drift from the provider, which is the drift this file exists to catch.
 */
const runBootScript = () => {
  new Function(BOOT_SCRIPT)();
};

const isDark = () => document.documentElement.classList.contains('dark');

const clearTheClass = () => {
  document.documentElement.className = '';
};

/** A button that switches the theme, so the provider persists through its real code path. */
const SetThemeButton: React.FC<{ to: 'light' | 'dark' | 'system' }> = ({ to }) => {
  const { setTheme } = useTheme();
  return (
    <button type="button" onClick={() => setTheme(to)}>
      set {to}
    </button>
  );
};

/**
 * Every combination of stored choice and operating-system preference that reaches the resolution rule,
 * including the two malformed cases — because "anything unrecognised means system" is part of the rule
 * and is written out separately in each implementation.
 */
const scenarios: { stored: string | null; systemDark: boolean; expectedDark: boolean }[] = [
  { stored: null, systemDark: false, expectedDark: false },
  { stored: null, systemDark: true, expectedDark: true },
  { stored: 'light', systemDark: false, expectedDark: false },
  { stored: 'light', systemDark: true, expectedDark: false },
  { stored: 'dark', systemDark: false, expectedDark: true },
  { stored: 'dark', systemDark: true, expectedDark: true },
  { stored: 'system', systemDark: false, expectedDark: false },
  { stored: 'system', systemDark: true, expectedDark: true },
  { stored: 'auto', systemDark: true, expectedDark: true },
  { stored: '', systemDark: false, expectedDark: false },
];

const seed = (stored: string | null, systemDark: boolean) => {
  localStorage.clear();
  if (stored !== null) localStorage.setItem('theme', stored);
  installMatchMedia(systemDark);
  clearTheClass();
};

describe('the boot script and ThemeProvider agree', () => {
  it('GivenAnyStoredChoiceAndSystemPreference_WhenTheBootScriptRuns_ThenItResolvesTheThemeTheProviderWould', () => {
    for (const { stored, systemDark, expectedDark } of scenarios) {
      const describeCase = `stored=${JSON.stringify(stored)} systemDark=${systemDark}`;

      seed(stored, systemDark);
      runBootScript();
      const bootResult = isDark();

      seed(stored, systemDark);
      render(<ThemeProvider>{null}</ThemeProvider>);
      const providerResult = isDark();
      cleanup();

      expect(`${describeCase} -> boot:${bootResult}`).toBe(`${describeCase} -> boot:${expectedDark}`);
      expect(`${describeCase} -> provider:${providerResult}`).toBe(
        `${describeCase} -> provider:${expectedDark}`,
      );
    }
  });

  /**
   * Binds the two to the same storage key without naming it twice.
   *
   * The provider writes through its own setter and the script reads on the next load, so renaming
   * `STORAGE_KEY` in one place fails here instead of quietly reintroducing the flash.
   */
  it('GivenTheProviderPersistedAChoice_WhenTheBootScriptRunsOnTheNextLoad_ThenItReadsThatChoice', () => {
    installMatchMedia(false);
    render(
      <ThemeProvider>
        <SetThemeButton to="dark" />
      </ThemeProvider>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'set dark' }));
    expect(isDark()).toBe(true);

    cleanup();
    clearTheClass();
    runBootScript();

    expect(isDark()).toBe(true);
  });

  it('GivenTheProviderPersistedLightAgainstADarkSystem_WhenTheBootScriptRuns_ThenTheChoiceStillWins', () => {
    installMatchMedia(true);
    render(
      <ThemeProvider>
        <SetThemeButton to="light" />
      </ThemeProvider>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'set light' }));

    cleanup();
    clearTheClass();
    runBootScript();

    expect(isDark()).toBe(false);
  });

  /**
   * The script has to survive storage being unreadable, because it runs before React and there is no
   * error boundary that early — an exception here leaves the page with no application at all.
   */
  it('GivenLocalStorageThrowsOnRead_WhenTheBootScriptRuns_ThenItBootsLightWithoutThrowing', () => {
    installMatchMedia(false);
    clearTheClass();
    const getItem = Storage.prototype.getItem;
    Storage.prototype.getItem = () => {
      throw new Error('SecurityError: storage is blocked');
    };

    try {
      expect(() => runBootScript()).not.toThrow();
      expect(isDark()).toBe(false);
    } finally {
      Storage.prototype.getItem = getItem;
    }
  });
});
