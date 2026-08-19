import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import PageContainer from './PageContainer';

/**
 * These pin which cap each variant selects, and nothing more.
 *
 * jsdom has no layout engine and `vitest.config.ts` sets `css: false`, so no test in this repo can
 * observe a width, a wrap or an overflow — see ADR-004. Asserting the class name is the one mechanical
 * link between the requirement and the code; the layout itself is checked by hand.
 */
const capOf = (container: HTMLElement) => container.firstElementChild?.className ?? '';

describe('PageContainer', () => {
  it('GivenNoWidth_WhenThePageContainerRenders_ThenItCapsAtTheDefaultWidth', () => {
    const { container } = render(<PageContainer>page</PageContainer>);

    expect(capOf(container)).toContain('max-w-7xl');
  });

  it('GivenTheNarrowWidth_WhenThePageContainerRenders_ThenItCapsAtTheReadingWidth', () => {
    const { container } = render(<PageContainer width="narrow">page</PageContainer>);

    expect(capOf(container)).toContain('max-w-3xl');
  });

  it('GivenExtraClasses_WhenThePageContainerRenders_ThenTheyAreKeptAlongsideTheCap', () => {
    const { container } = render(<PageContainer className="space-y-6">page</PageContainer>);

    expect(capOf(container)).toContain('space-y-6');
    expect(capOf(container)).toContain('max-w-7xl');
  });

  it('GivenAnyWidth_WhenThePageContainerRenders_ThenItShowsItsChildren', () => {
    render(<PageContainer>the page</PageContainer>);

    expect(screen.getByText('the page')).toBeDefined();
  });
});
