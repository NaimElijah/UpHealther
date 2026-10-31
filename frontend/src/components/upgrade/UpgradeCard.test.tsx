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
 * The card's title is the one way from a list to an upgrade's page. It was a heading with a click
 * handler, which no keyboard can reach, so every edit, reminder and reflection was pointer-only (WCAG
 * 2.1.1, finding X9.1).
 */
describe('UpgradeCard title', () => {
  it('GivenAnUpgrade_WhenTheCardIsShown_ThenItsTitleIsALinkToItsPageInsideTheHeading', () => {
    render(
      <MemoryRouter>
        <UpgradeCard upgrade={upgradeAsSent({ id: 'u-42', title: 'Cold showers' })} />
      </MemoryRouter>,
    );

    const link = screen.getByRole('link', { name: 'Cold showers' });
    expect(link.getAttribute('href')).toBe('/upgrades/u-42');
    expect(screen.getByRole('heading', { name: 'Cold showers' }).contains(link)).toBe(true);
  });
});

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
