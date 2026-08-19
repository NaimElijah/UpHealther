import React from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { ThemeProvider } from './ThemeProvider';
import { useTheme } from '../hooks/useTheme';
import { installMatchMedia, uninstallMatchMedia } from '../test/matchMediaStub';

/** Renders the context's current values, so a test can assert on what a consumer would actually see. */
const ThemeReadout: React.FC = () => {
  const { theme, resolvedTheme } = useTheme();
  return (
    <>
      <span data-testid="theme">{theme}</span>
      <span data-testid="resolved">{resolvedTheme}</span>
    </>
  );
};

/** A button that switches the theme, so the setter is exercised the way the toggle uses it. */
const SetThemeButton: React.FC<{ to: 'light' | 'dark' | 'system' }> = ({ to }) => {
  const { setTheme } = useTheme();
  return (
    <button type="button" onClick={() => setTheme(to)}>
      set {to}
    </button>
  );
};

const renderProvider = (children: React.ReactNode = <ThemeReadout />) =>
  render(<ThemeProvider>{children}</ThemeProvider>);

const isDark = () => document.documentElement.classList.contains('dark');

describe('ThemeProvider', () => {
  describe('resolving the initial theme', () => {
    it('GivenNoStoredTheme_WhenTheProviderMounts_ThenTheThemeIsSystem', () => {
      renderProvider();

      expect(screen.getByTestId('theme').textContent).toBe('system');
    });

    it('GivenAnUnrecognisedStoredValue_WhenTheProviderMounts_ThenTheThemeIsSystem', () => {
      localStorage.setItem('theme', 'auto');

      renderProvider();

      expect(screen.getByTestId('theme').textContent).toBe('system');
    });

    it('GivenAStoredDarkTheme_WhenTheProviderMounts_ThenTheDarkClassIsOnTheDocumentElement', () => {
      localStorage.setItem('theme', 'dark');

      renderProvider();

      expect(isDark()).toBe(true);
    });

    it('GivenAStoredLightTheme_WhenTheSystemPrefersDark_ThenTheDarkClassIsAbsent', () => {
      localStorage.setItem('theme', 'light');
      installMatchMedia(true);

      renderProvider();

      expect(isDark()).toBe(false);
    });

    it('GivenTheSystemTheme_WhenTheSystemPrefersDark_ThenTheResolvedThemeIsDark', () => {
      installMatchMedia(true);

      renderProvider();

      expect(screen.getByTestId('resolved').textContent).toBe('dark');
      expect(isDark()).toBe(true);
    });
  });

  describe('following the system preference', () => {
    it('GivenTheSystemTheme_WhenTheSystemPreferenceChangesToDark_ThenTheDarkClassIsAdded', () => {
      const media = installMatchMedia(false);
      renderProvider();
      expect(isDark()).toBe(false);

      act(() => media.setMatches(true));

      expect(isDark()).toBe(true);
    });

    it('GivenTheSystemTheme_WhenTheSystemPreferenceChangesBackToLight_ThenTheDarkClassIsRemoved', () => {
      const media = installMatchMedia(true);
      renderProvider();
      expect(isDark()).toBe(true);

      act(() => media.setMatches(false));

      expect(isDark()).toBe(false);
    });

    it('GivenAnExplicitLightTheme_WhenTheSystemPreferenceChangesToDark_ThenTheDarkClassIsStillAbsent', () => {
      localStorage.setItem('theme', 'light');
      const media = installMatchMedia(false);
      renderProvider();

      act(() => media.setMatches(true));

      expect(isDark()).toBe(false);
    });
  });

  describe('changing and persisting the theme', () => {
    it('GivenAnyTheme_WhenSetThemeIsCalled_ThenTheChoiceIsWrittenToLocalStorage', () => {
      renderProvider(<SetThemeButton to="dark" />);

      act(() => screen.getByRole('button').click());

      expect(localStorage.getItem('theme')).toBe('dark');
      expect(isDark()).toBe(true);
    });

    it('GivenAnExplicitTheme_WhenSetThemeIsCalledWithSystem_ThenTheDocumentFollowsTheSystemPreferenceAgain', () => {
      localStorage.setItem('theme', 'light');
      installMatchMedia(true);
      renderProvider(<SetThemeButton to="system" />);
      expect(isDark()).toBe(false);

      act(() => screen.getByRole('button').click());

      expect(isDark()).toBe(true);
    });
  });

  describe('surviving a hostile environment', () => {
    it('GivenLocalStorageThrowsOnRead_WhenTheProviderMounts_ThenItFallsBackToSystemWithoutThrowing', () => {
      const getItem = Storage.prototype.getItem;
      Storage.prototype.getItem = () => {
        throw new Error('SecurityError: storage is disabled');
      };

      try {
        renderProvider();
        expect(screen.getByTestId('theme').textContent).toBe('system');
      } finally {
        Storage.prototype.getItem = getItem;
      }
    });

    it('GivenLocalStorageThrowsOnWrite_WhenSetThemeIsCalled_ThenTheThemeStillChanges', () => {
      const setItem = Storage.prototype.setItem;
      Storage.prototype.setItem = () => {
        throw new Error('QuotaExceededError');
      };

      try {
        renderProvider(<SetThemeButton to="dark" />);
        act(() => screen.getByRole('button').click());
        expect(isDark()).toBe(true);
      } finally {
        Storage.prototype.setItem = setItem;
      }
    });

    it('GivenNoMatchMediaSupport_WhenTheProviderMounts_ThenItResolvesToLight', () => {
      uninstallMatchMedia();

      renderProvider();

      expect(screen.getByTestId('resolved').textContent).toBe('light');
      expect(isDark()).toBe(false);
    });
  });

  describe('subscription lifecycle', () => {
    it('GivenTheProviderIsUnmounted_WhenTheSystemPreferenceChanges_ThenNoListenerRemains', () => {
      const media = installMatchMedia(false);
      const { unmount } = renderProvider();

      unmount();

      expect(media.listenerCount()).toBe(0);
    });

    it('GivenStrictMode_WhenTheProviderMounts_ThenExactlyOneMediaQueryListenerIsRegistered', () => {
      const media = installMatchMedia(false);

      render(
        <React.StrictMode>
          <ThemeProvider>
            <ThemeReadout />
          </ThemeProvider>
        </React.StrictMode>,
      );

      expect(media.listenerCount()).toBe(1);
    });

    it('GivenStrictMode_WhenTheProviderMounts_ThenTheDarkClassIsAppliedExactlyOnce', () => {
      localStorage.setItem('theme', 'dark');

      render(
        <React.StrictMode>
          <ThemeProvider>
            <ThemeReadout />
          </ThemeProvider>
        </React.StrictMode>,
      );

      expect(document.documentElement.className.match(/dark/g)).toHaveLength(1);
    });
  });
});
