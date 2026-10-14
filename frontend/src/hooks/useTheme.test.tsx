import React from 'react';
import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { useTheme } from './useTheme';
import { silenceExpectedRenderErrors } from '../test/expectedRenderErrors';

const Consumer: React.FC = () => {
  useTheme();
  return null;
};

describe('useTheme', () => {
  it('GivenNoProvider_WhenUseThemeIsCalled_ThenItThrowsAGuidingError', () => {
    // React and jsdom both report the throw before it is rethrown; silenced so a deliberate failure
    // does not look like a broken test run.
    const unsilence = silenceExpectedRenderErrors();

    try {
      expect(() => render(<Consumer />)).toThrowError('useTheme must be used within ThemeProvider');
    } finally {
      unsilence();
    }
  });
});
