import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import client from '../api/client';
import UpgradeDetailsPage from './UpgradeDetailsPage';
import { upgradeAsSent } from '../test/wireFixtures';

/**
 * S5.D4, from the route down: the upgrade page with an id that would leave its path.
 *
 * React Router decodes `:id`, so this route hands the page `../health-areas?`. The page's own test
 * replaces the `api/` modules and cannot see a path, so this one runs the real modules and the real
 * client, with the adapter replaced so nothing leaves the process, and records every path the page
 * calls as it loads. Besides the upgrade's own paths, it reads one that takes no id: the user's health
 * areas, which the edit form offers whatever upgrade is open.
 */
describe('the upgrade page, opened with an id that climbs out of its path', () => {
  const ROUTE = '/upgrades/..%2Fhealth-areas%3F';
  const ENCODED_ID = '..%2Fhealth-areas%3F';
  const AREAS_PATH = '/api/health-areas';
  const realAdapter = client.defaults.adapter;
  let sentPaths: string[] = [];

  /** Answers as the API would for an upgrade with no history, so every section's read goes out. */
  function answerFor(url: string): unknown {
    if (url.endsWith('/streak')) return { current: 0, longest: 0 };
    if (url === AREAS_PATH || /\/(progress|reflections|reminders)$/.test(url)) return [];
    return upgradeAsSent({ title: 'An upgrade reached by a strange id' });
  }

  beforeEach(() => {
    sentPaths = [];
    client.defaults.adapter = (config) => {
      const url = (config as InternalAxiosRequestConfig).url ?? '';
      sentPaths.push(url);
      return Promise.resolve({
        data: answerFor(url),
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      } as AxiosResponse);
    };
  });

  afterEach(() => {
    client.defaults.adapter = realAdapter;
  });

  it('GivenARouteIdThatClimbsAndStartsAQuery_WhenThePageLoads_ThenEveryCallStaysUnderThatUpgrade', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[ROUTE]}>
          <Routes>
            <Route path="/upgrades/:id" element={<UpgradeDetailsPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await screen.findByRole('heading', { name: 'An upgrade reached by a strange id' });
    // The areas read starts only once the loaded upgrade mounts the actions, so it may land after the heading.
    await waitFor(() => expect(sentPaths).toContain(AREAS_PATH));

    const underThatUpgrade = new RegExp(`^/api/upgrades/${ENCODED_ID}(/[a-z-]+)?$`);
    expect(sentPaths.filter((path) => !underThatUpgrade.test(path))).toEqual([AREAS_PATH]);
  });
});
