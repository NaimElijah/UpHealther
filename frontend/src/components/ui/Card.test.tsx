import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import Card from './Card';
import EmptyState from './EmptyState';
import ErrorState from './ErrorState';

/**
 * A card's header is a heading, at the level its place in the page needs, and so are an empty state's
 * and a failed load's titles. Section titles were plain text, so the dashboard's outline was its `h1`
 * and then eight `h3` cards (WCAG 1.3.1, 2.4.6, finding X9.11).
 */
describe('Card', () => {
  it('GivenAHeader_WhenTheCardRenders_ThenTheHeaderIsASecondLevelHeading', () => {
    render(<Card header="Today's Health Moves">body</Card>);

    expect(screen.getByRole('heading', { level: 2, name: "Today's Health Moves" })).toBeDefined();
  });

  it('GivenALevel_WhenTheCardRenders_ThenTheHeaderIsAHeadingOfThatLevel', () => {
    render(
      <Card header="Reminders" level={3}>
        body
      </Card>,
    );

    expect(screen.getByRole('heading', { level: 3, name: 'Reminders' })).toBeDefined();
  });

  it('GivenAHeaderAction_WhenTheCardRenders_ThenTheActionIsBesideTheHeadingNotInsideIt', () => {
    render(
      <Card header="Progress History" headerAction={<button type="button">+ Log Progress</button>}>
        body
      </Card>,
    );

    const heading = screen.getByRole('heading', { level: 2 });
    expect(heading.textContent).toBe('Progress History');
    expect(heading.contains(screen.getByRole('button', { name: '+ Log Progress' }))).toBe(false);
  });

  it('GivenNoHeader_WhenTheCardRenders_ThenItHasNoHeading', () => {
    render(<Card>body</Card>);

    expect(screen.queryByRole('heading')).toBeNull();
  });
});

describe.each([
  ['EmptyState', (level?: 2 | 3) => <EmptyState title="Nothing here" level={level} />],
  ['ErrorState', (level?: 2 | 3) => <ErrorState title="Nothing here" level={level} />],
])('%s', (_, state) => {
  it('GivenNoLevel_WhenItRenders_ThenItsTitleIsASecondLevelHeading', () => {
    render(state());

    expect(screen.getByRole('heading', { level: 2, name: 'Nothing here' })).toBeDefined();
  });

  it('GivenLevelThree_WhenItRendersInsideASection_ThenItsTitleIsAThirdLevelHeading', () => {
    render(state(3));

    expect(screen.getByRole('heading', { level: 3, name: 'Nothing here' })).toBeDefined();
  });
});
