import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '../../contexts/ThemeProvider';
import ThemeToggle from './ThemeToggle';
import { installMatchMedia } from '../../test/matchMediaStub';

const renderToggle = () =>
  render(
    <ThemeProvider>
      <ThemeToggle />
    </ThemeProvider>,
  );

const isDark = () => document.documentElement.classList.contains('dark');

describe('ThemeToggle', () => {
  it('GivenAnyTheme_WhenTheToggleRenders_ThenTheGroupIsNamedTheme', () => {
    renderToggle();

    expect(screen.getByRole('group', { name: 'Theme' })).toBeDefined();
  });

  it('GivenAnyTheme_WhenTheToggleRenders_ThenAllThreeChoicesArePresent', () => {
    renderToggle();

    expect(screen.getAllByRole('radio')).toHaveLength(3);
  });

  it('GivenTheSystemTheme_WhenTheToggleRenders_ThenTheSystemRadioIsChecked', () => {
    renderToggle();

    const system = screen.getByRole('radio', { name: /System/ }) as HTMLInputElement;
    expect(system.checked).toBe(true);
  });

  it('GivenAStoredDarkTheme_WhenTheToggleRenders_ThenTheDarkRadioIsChecked', () => {
    localStorage.setItem('theme', 'dark');

    renderToggle();

    expect((screen.getByRole('radio', { name: 'Dark' }) as HTMLInputElement).checked).toBe(true);
    expect((screen.getByRole('radio', { name: /System/ }) as HTMLInputElement).checked).toBe(false);
  });

  it('GivenTheLightTheme_WhenTheDarkOptionIsSelected_ThenTheDocumentGainsTheDarkClass', () => {
    localStorage.setItem('theme', 'light');
    renderToggle();
    expect(isDark()).toBe(false);

    fireEvent.click(screen.getByRole('radio', { name: 'Dark' }));

    expect(isDark()).toBe(true);
    expect(localStorage.getItem('theme')).toBe('dark');
  });

  // "System" alone tells a screen-reader user the rule but not the outcome — which is the one thing
  // they cannot otherwise perceive, since the outcome is conveyed purely by colour.
  it('GivenTheSystemThemeResolvingToDark_WhenTheToggleRenders_ThenTheSystemOptionNamesTheResolvedTheme', () => {
    installMatchMedia(true);

    renderToggle();

    expect(screen.getByRole('radio', { name: 'System (currently dark)' })).toBeDefined();
  });

  it('GivenTheSystemThemeResolvingToLight_WhenTheToggleRenders_ThenTheSystemOptionNamesTheResolvedTheme', () => {
    installMatchMedia(false);

    renderToggle();

    expect(screen.getByRole('radio', { name: 'System (currently light)' })).toBeDefined();
  });

  it('GivenAnExplicitTheme_WhenTheSystemOptionIsSelected_ThenTheDocumentFollowsTheOperatingSystem', () => {
    localStorage.setItem('theme', 'light');
    installMatchMedia(true);
    renderToggle();
    expect(isDark()).toBe(false);

    fireEvent.click(screen.getByRole('radio', { name: /System/ }));

    expect(isDark()).toBe(true);
  });
});
