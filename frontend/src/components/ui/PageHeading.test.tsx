import { describe, it, expect } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import PageHeading from './PageHeading';

/** A view that swaps its form for a heading that takes focus, as the check-in does once it is sent. */
function Completes() {
  const [done, setDone] = useState(false);
  return (
    <>
      {done ? (
        <PageHeading takeFocus>Done</PageHeading>
      ) : (
        <button type="button" onClick={() => setDone(true)}>
          Send
        </button>
      )}
      <a href="#elsewhere">Elsewhere</a>
    </>
  );
}

/**
 * `takeFocus` hands focus on from a control the view has just removed (WCAG 2.4.3). It takes focus only
 * when nothing holds it, because focus the user moved while the view changed is theirs (review finding 2
 * on #407).
 */
describe('PageHeading takeFocus', () => {
  it('GivenTheSentFormIsGone_WhenTheHeadingArrives_ThenItTakesFocus', () => {
    render(<Completes />);
    const send = screen.getByRole('button', { name: 'Send' });
    send.focus();

    fireEvent.click(send);

    expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1, name: 'Done' }));
  });

  it('GivenFocusMovedElsewhere_WhenTheHeadingArrives_ThenFocusStaysWhereTheUserPutIt', () => {
    render(<Completes />);
    const elsewhere = screen.getByRole('link', { name: 'Elsewhere' });
    const send = screen.getByRole('button', { name: 'Send' });
    elsewhere.focus();

    fireEvent.click(send);

    expect(document.activeElement).toBe(elsewhere);
  });
});
