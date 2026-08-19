import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';
import { useTheme } from './useTheme';

const Consumer: React.FC = () => {
  useTheme();
  return null;
};

describe('useTheme', () => {
  it('GivenNoProvider_WhenUseThemeIsCalled_ThenItThrowsAGuidingError', () => {
    // React logs the error boundary trace to the console before rethrowing; silenced so a deliberate
    // failure does not look like a broken test run.
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    try {
      expect(() => render(<Consumer />)).toThrowError('useTheme must be used within ThemeProvider');
    } finally {
      consoleError.mockRestore();
    }
  });
});
