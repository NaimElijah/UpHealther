import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { useEffect, useState } from 'react';
import { QueryClientProvider, useQuery } from '@tanstack/react-query';
import { endSession } from './api/session';
import { createQueryClient } from './lib/queryClient';

const readUpgrades = vi.hoisted(() => vi.fn(() => Promise.resolve(["The previous user's upgrade"])));

/** Schedules a render of the stand-in application, as `AuthContext`'s sign-out does just before it ends the session. */
const scheduleRender = vi.hoisted(() => ({ current: () => {} }));

/** Stands in for the application: one cache, built as the app builds it, and one mounted query. */
vi.mock('./App', () => {
  const queryClient = createQueryClient();
  function Upgrades() {
    const [, setRenders] = useState(0);
    useEffect(() => {
      scheduleRender.current = () => setRenders((renders) => renders + 1);
    }, []);
    const { data } = useQuery({ queryKey: ['upgrades'], queryFn: readUpgrades });
    return <p>{data?.[0] ?? 'loading'}</p>;
  }
  return {
    default: () => (
      <QueryClientProvider client={queryClient}>
        <Upgrades />
      </QueryClientProvider>
    ),
  };
});

/**
 * The entry point, run as the browser runs it, and what a session's end does to what it mounted.
 *
 * Emptying the query cache leaves the rendered page in place, so a page the back/forward cache keeps
 * would still show the previous user's data until its reload commits; and a query still mounted would
 * fetch again with no token, a 401 and a refused, audited refresh later (ADR-066). So the root is
 * unmounted too. `window.location` is redefined because jsdom refuses the navigation `endSession`
 * performs.
 */
describe('the entry point', () => {
  const realLocation = window.location;

  beforeEach(() => {
    const root = document.createElement('div');
    root.id = 'root';
    document.body.replaceChildren(root);
    Object.defineProperty(window, 'location', {
      configurable: true,
      writable: true,
      value: { ...realLocation, href: '' },
    });
  });

  afterEach(() => {
    Object.defineProperty(window, 'location', { configurable: true, writable: true, value: realLocation });
  });

  it('GivenTheMountedApplication_WhenTheSessionEnds_ThenItIsUnmountedAndNothingFetchesAgain', async () => {
    await import('./main');
    await vi.waitFor(() => expect(document.getElementById('root')?.textContent).toBe("The previous user's upgrade"));

    // As a sign-out does: a state change, then the session's end, before React has rendered again.
    scheduleRender.current();
    endSession();
    await new Promise<void>((resolve) => setTimeout(resolve, 0));

    expect(document.getElementById('root')?.innerHTML).toBe('');
    expect(readUpgrades).toHaveBeenCalledTimes(1);
  });
});
