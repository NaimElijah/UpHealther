import { describe, it, expect } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { useRef, useState } from 'react';
import { useFocusWhenReady } from './useFocusWhenReady';

/**
 * A page with a target for focus, a button that asks for it, and a modal dialog open over both. The ask
 * also re-renders, as the success that asks does in a page, because the hook answers after a render.
 */
function PageWithDialog() {
  const focusWhenReady = useFocusWhenReady();
  const target = useRef<HTMLButtonElement>(null);
  const [, setAsked] = useState(false);
  const [dialogOpen, setDialogOpen] = useState(true);
  return (
    <>
      <button ref={target} type="button">
        Target
      </button>
      <button
        type="button"
        onClick={() => {
          focusWhenReady(() => target.current);
          setAsked(true);
        }}
      >
        Ask
      </button>
      {dialogOpen && (
        <div role="dialog" aria-modal="true" aria-label="Dialog">
          <button type="button" onClick={() => setDialogOpen(false)}>
            Close
          </button>
        </div>
      )}
    </>
  );
}

/**
 * A modal dialog owns focus, and hands it back to its opener as it closes, so a move asked for while one
 * is open waits for it to close; made sooner, the dialog's own hand-back would undo it, or it would pull
 * focus out of the dialog. Untested until review finding 9 on #407.
 */
describe('useFocusWhenReady', () => {
  it('GivenAModalDialogIsOpen_WhenFocusIsAskedFor_ThenItMovesOnlyOnceTheDialogCloses', () => {
    render(<PageWithDialog />);
    const target = screen.getByRole('button', { name: 'Target' });

    fireEvent.click(screen.getByRole('button', { name: 'Ask' }));
    expect(document.activeElement).not.toBe(target);

    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(document.activeElement).toBe(target);
  });
});
