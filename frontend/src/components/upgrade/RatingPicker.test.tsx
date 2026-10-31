import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import RatingPicker from './RatingPicker';

/**
 * The scores are native radios inside the check-in's form, and Enter on a radio submits its form in
 * Chromium and Firefox: a keyboard user pressing Enter on "3", as they could when the scores were
 * buttons, posted every active upgrade's check-in at once (review finding 5 on #407). jsdom performs no
 * implicit submission, so the test reads what stops it, the key's cancelled default.
 */
describe('RatingPicker', () => {
  it('GivenARatingInAForm_WhenEnterIsPressedOnAScore_ThenTheScoreIsChosenAndTheFormIsNotSubmitted', () => {
    const onChange = vi.fn();
    render(
      <form>
        <RatingPicker legend="How did it go?" value={undefined} onChange={onChange} />
      </form>,
    );

    const notCancelled = fireEvent.keyDown(screen.getByRole('radio', { name: 'Rate 3 of 5' }), { key: 'Enter' });

    expect(notCancelled).toBe(false);
    expect(onChange).toHaveBeenCalledWith(3);
  });

  it('GivenARating_WhenAnotherKeyIsPressedOnAScore_ThenItIsLeftToTheBrowser', () => {
    render(<RatingPicker legend="How did it go?" value={undefined} onChange={vi.fn()} />);

    const notCancelled = fireEvent.keyDown(screen.getByRole('radio', { name: 'Rate 3 of 5' }), { key: 'ArrowRight' });

    expect(notCancelled).toBe(true);
  });
});
