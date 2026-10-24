import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import UpgradeFormFields from './UpgradeFormFields';
import type { CreateUpgradeRequest } from '../../types';

/** The fields of an upgrade being edited, with only the two the API requires filled in. */
const fieldsWith = (values: Partial<CreateUpgradeRequest>): CreateUpgradeRequest => ({
  title: 'Cold showers',
  type: 'HABIT',
  ...values,
});

const difficultySelect = () => screen.getByLabelText<HTMLSelectElement>('Difficulty');

/**
 * The API accepts an upgrade with no difficulty, but every choice the select offers is a difficulty.
 * React gives a controlled select whose value matches no option its first option, so such an upgrade
 * was edited under "Easy", a difficulty it does not have.
 */
describe('UpgradeFormFields', () => {
  it('GivenAnUpgradeWithNoDifficulty_WhenItsFieldsRender_ThenTheDifficultyReadsAsNotSet', () => {
    render(<UpgradeFormFields value={fieldsWith({ difficulty: null })} onChange={vi.fn()} areas={[]} />);

    expect(difficultySelect().selectedOptions[0]?.textContent).toBe('Not set');
  });

  it('GivenAnUpgradeWithADifficulty_WhenItsFieldsRender_ThenItIsChosenAndNotSetIsNotOffered', () => {
    render(<UpgradeFormFields value={fieldsWith({ difficulty: 'HARD' })} onChange={vi.fn()} areas={[]} />);

    expect(difficultySelect().value).toBe('HARD');
    expect(within(difficultySelect()).queryByRole('option', { name: 'Not set' })).toBeNull();
  });
});
