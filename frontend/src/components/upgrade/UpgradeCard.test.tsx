import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import UpgradeCard from './UpgradeCard';
import type { Difficulty } from '../../types';
import { upgradeAsSent } from '../../test/wireFixtures';

function renderCard(difficulty: Difficulty | null) {
  return render(
    <MemoryRouter>
      <UpgradeCard upgrade={upgradeAsSent({ title: 'Cold showers', status: 'IDEA', type: 'HABIT', difficulty })} />
    </MemoryRouter>,
  );
}

/**
 * The card's difficulty pill names the difficulty as the form does. It showed the raw value, so an
 * upgrade read "EASY" in capitals beside "Idea" and "Habit", and one with no difficulty an empty pill.
 */
describe('UpgradeCard', () => {
  it.each([
    ['EASY', 'Easy'],
    ['MEDIUM', 'Medium'],
    ['HARD', 'Hard'],
  ] as const)('GivenADifficulty%s_WhenTheCardIsShown_ThenItIsNamed%s', (difficulty, label) => {
    renderCard(difficulty);

    expect(screen.getByText(label)).toBeDefined();
    expect(screen.queryByText(difficulty)).toBeNull();
  });

  // A bare "Not set" beside "Idea" and "Habit" does not say what is not set, read aloud least of all.
  it('GivenNoDifficulty_WhenTheCardIsShown_ThenThePillSaysTheDifficultyIsNotSet', () => {
    renderCard(null);

    expect(screen.getByText('Difficulty not set')).toBeDefined();
  });
});
