import { describe, it, expect } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import LoadingSpinner from './LoadingSpinner';
import Button from './Button';

/**
 * A spinner says that something is loading. It was two unlabelled boxes, so every page load and every
 * pending request was silent to a screen reader (WCAG 1.1.1, 4.1.3, finding X9.16).
 */
describe('LoadingSpinner', () => {
  it('GivenASpinner_WhenItRenders_ThenItIsAStatusThatSaysLoading', () => {
    render(<LoadingSpinner size="lg" />);

    expect(screen.getByRole('status').textContent).toBe('Loading…');
  });

  // The button already says it is busy (aria-busy); a second status inside it would announce it twice.
  it('GivenALoadingButton_WhenItRenders_ThenItsSpinnerIsHiddenAndAnnouncesNothing', () => {
    render(<Button loading>Save</Button>);

    const button = screen.getByRole('button', { name: 'Save' });
    expect(within(button).queryByRole('status')).toBeNull();
    expect(button.querySelector('[aria-hidden="true"]')).not.toBeNull();
  });
});
