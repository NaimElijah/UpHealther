import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { useState } from 'react';
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

/** Hosts the fields the way a form does, holding what they report and handing it back. */
function HostedFields({ initial }: { initial: CreateUpgradeRequest }) {
  const [value, setValue] = useState(initial);
  return <UpgradeFormFields value={value} onChange={setValue} areas={[]} />;
}

/**
 * Firefox clears a date the user left unfinished as focus leaves it, and its `validity.badInput` with it,
 * once the field's `value` attribute has changed since the user began editing. React rewrites that
 * attribute on every change of a controlled field, so a form pressed with the mouse could no longer tell
 * an unfinished target end from an empty one, and saved it as none (review finding 1 on #407).
 */
describe('UpgradeFormFields target end', () => {
  it('GivenATargetEnd_WhenItIsEdited_ThenItsValueAttributeStaysAsTheFieldOpened', () => {
    render(<HostedFields initial={fieldsWith({ targetEndDate: '2026-12-31' })} />);
    const targetEnd = screen.getByLabelText<HTMLInputElement>('Target End');

    fireEvent.change(targetEnd, { target: { value: '' } });

    expect(targetEnd.getAttribute('value')).toBe('2026-12-31');
  });

  it('GivenATargetEnd_WhenANewDateIsTyped_ThenTheFormReceivesIt', () => {
    const onChange = vi.fn();
    render(<UpgradeFormFields value={fieldsWith({ targetEndDate: '2026-12-31' })} onChange={onChange} areas={[]} />);

    fireEvent.change(screen.getByLabelText('Target End'), { target: { value: '2027-01-15' } });

    expect(onChange.mock.calls[0][0]).toMatchObject({ targetEndDate: '2027-01-15' });
  });
});
