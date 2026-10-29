/**
 * The smoke suite: one new visitor drives the frontend, nginx, the API, the notification socket and the
 * database together, through a browser, along the happy path.
 *
 * Each step is a place where the two sides have disagreed while each side's own suite passed: Plan
 * posted no start date and every plan was refused (#88), and the dashboard showed the weekly rate a
 * hundred times too large (#20). Failure paths stay at the cheapest level that can observe them
 * (ADR-009). See docs/ADRs/ADR-052-a-playwright-smoke-suite-drives-the-stack-the-image-check-starts.md.
 *
 * A second test reads the third-party notices that must travel with the bundle (#337): only the built
 * image shows whether nginx serves the file Vite wrote.
 */
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { expect, test, type Locator, type Page } from '@playwright/test';

/** Eight characters or more, the API's minimum (BR-17). The account lives only in this run's database. */
const PASSWORD = 'smoke-test-password';

/** Shares no word with a button the steps press, so a name match never lands on the card's title. */
const UPGRADE_TITLE = 'Drink a glass of water';

/**
 * A page's link in the sidebar. Links are followed rather than pages loaded, because every load renews
 * the session from the rotating refresh cookie, and the suite should not race its own renewals.
 */
function sidebarLink(page: Page, label: string): Locator {
  return page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: label });
}

/**
 * The figure of one of the dashboard's stat tiles. A tile is a figure followed by its caption, with no
 * role between them, so the caption is found and the figure read before it, as DashboardPage.test.tsx
 * does. Restricted to a `div` because the same words appear in the sidebar and in status badges.
 */
function statTile(page: Page, caption: string): Locator {
  return page
    .getByRole('main')
    .getByText(caption, { exact: true })
    .and(page.locator('div'))
    .locator('xpath=preceding-sibling::div[1]');
}

/**
 * Watches the sockets the page opens from now on, and answers whether one has sent a STOMP SUBSCRIBE:
 * the session is listening for notifications. The client speaks STOMP over a plain WebSocket, so each
 * sent frame is the STOMP frame itself. A flag polled by `expect.poll`, rather than a promise awaited,
 * so a socket that never subscribes fails within the assertion timeout and says so.
 */
function watchForSubscription(page: Page): () => boolean {
  let subscribed = false;
  page.on('websocket', (socket) => {
    socket.on('framesent', ({ payload }) => {
      if (String(payload).startsWith('SUBSCRIBE')) {
        subscribed = true;
      }
    });
  });
  return () => subscribed;
}

/**
 * The frontend's direct runtime dependencies, read from its manifest rather than listed here, so one added
 * later must appear in the notices too. Names only: the notices name the installed version, which the
 * manifest's range does not.
 */
function runtimeDependencies(): string[] {
  const manifest = JSON.parse(readFileSync(new URL('../../frontend/package.json', import.meta.url), 'utf8')) as {
    dependencies: Record<string, string>;
  };
  return Object.keys(manifest.dependencies);
}

/**
 * The notices' sections, by the package name in each `## <name> - <version> (<licence>)` heading, with the
 * text up to the next heading. The body is kept because Vite writes a heading alone for a package it
 * finds no licence file in, and a heading is not a notice.
 */
function noticeSections(notices: string): Map<string, string> {
  return new Map(
    notices
      .split(/^## /m)
      .slice(1)
      .map((section): [string, string] => {
        const [heading, ...body] = section.split('\n');
        return [heading.split(' - ')[0], body.join('\n')];
      }),
  );
}

test('GivenANewVisitor_WhenTheyActivateAnUpgradeAndLogToday_ThenTheDashboardCountsIt', async ({ page }) => {
  // nginx serves the CSP, so a violation shows in no other suite. Every console error is not collected:
  // the session check on a signed-out page is answered 401 by design, and the browser logs it.
  const problems: string[] = [];
  page.on('pageerror', (error) => problems.push(`uncaught: ${error.message}`));
  page.on('console', (message) => {
    if (message.text().includes('Content Security Policy')) {
      problems.push(`CSP: ${message.text()}`);
    }
  });
  const email = `e2e-${randomUUID()}@example.test`;
  const main = page.getByRole('main');

  await test.step('register, sign out and sign in again', async () => {
    await page.goto('/register');
    await page.getByLabel('Full Name').fill('Smoke Tester');
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
    await page.getByLabel('Confirm Password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Create Account' }).click();
    await expect(page).toHaveURL(/\/dashboard$/);

    await page.getByRole('button', { name: 'Logout' }).click();
    await expect(page).toHaveURL(/\/login$/);

    // Armed after signing out, so the socket the registered session opened cannot satisfy it.
    const hasSubscribed = watchForSubscription(page);
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Sign In' }).click();
    await expect(page).toHaveURL(/\/dashboard$/);
    // A notification raised before the subscription would be stored but never pushed, and the toast
    // asserted below would not appear.
    await expect
      .poll(hasSubscribed, { message: 'the notification socket subscribes once signed in, through nginx /ws' })
      .toBe(true);
  });

  await test.step('create an idea, plan it and activate it', async () => {
    await sidebarLink(page, 'Idea Backlog').click();
    await page.getByRole('button', { name: '+ New Idea' }).click();
    const newIdea = page.getByRole('dialog', { name: 'New Upgrade Idea' });
    await newIdea.getByLabel('Title').fill(UPGRADE_TITLE);
    await newIdea.getByRole('button', { name: 'Add Idea' }).click();
    await expect(newIdea).toBeHidden();

    // The card's Plan and the dialog's Plan share a name, and toasts render outside <main>.
    await expect(main.getByRole('heading', { level: 3, name: UPGRADE_TITLE })).toBeVisible();
    await main.getByRole('button', { name: 'Plan', exact: true }).click();
    const plan = page.getByRole('dialog', { name: 'Plan Upgrade' });
    await plan.getByRole('button', { name: 'Plan', exact: true }).click();
    // The dialog closes only once the backlog no longer lists the idea, so the plan was accepted.
    await expect(plan).toBeHidden();

    await sidebarLink(page, 'Planned').click();
    await main.getByRole('button', { name: 'Activate', exact: true }).click();
    // Pushed over the socket: nginx's /ws upgrade, the socket's origin check and STOMP authentication.
    await expect(page.getByRole('status').filter({ hasText: 'Upgrade activated' })).toBeVisible();
    await expect(main.getByRole('heading', { level: 3, name: UPGRADE_TITLE })).toBeHidden();
  });

  await test.step("open the upgrade and log today's progress", async () => {
    await sidebarLink(page, 'Active').click();
    await main.getByRole('heading', { level: 3, name: UPGRADE_TITLE }).click();
    await expect(page.getByRole('heading', { level: 1, name: UPGRADE_TITLE })).toBeVisible();
    // A toast can still cover this button. The click waits until it is the button that would receive
    // it, and a toast closes itself after six seconds.
    await page.getByRole('button', { name: '+ Log Progress' }).click();
    const log = page.getByRole('dialog', { name: 'Log Progress' });
    await log.getByLabel('Completed').check();
    await log.getByRole('button', { name: 'Save' }).click();
    await expect(log).toBeHidden();
    await expect(main.getByText('Done', { exact: true })).toBeVisible();
  });

  await test.step('the dashboard counts the active upgrade and the completed day', async () => {
    // Loaded, not followed: logging progress leaves the dashboard's cached figures in place for thirty
    // seconds, and this reads the server's.
    await page.goto('/dashboard');
    await expect(statTile(page, 'Active')).toHaveText('1');
    await expect(statTile(page, 'Weekly Rate')).toHaveText('100%');
  });

  expect(problems).toEqual([]);
});

test('GivenTheBuiltImage_WhenTheThirdPartyNoticesAreRequested_ThenEveryRuntimeDependencyAndTailwindHasANotice', async ({
  page,
}) => {
  // Loaded by the browser, not by the request fixture, which resolves `localhost` through Node and
  // misses the IPv4 mapping in playwright.config.ts.
  const response = await page.goto('/THIRD-PARTY-NOTICES.txt');
  expect(response?.ok(), `the notices answer ${response?.status()}`).toBe(true);
  // nginx answers a missing file with the SPA's index.html and a 200, so the status alone cannot fail.
  // The charset matters too: licence texts are copied verbatim, and a `©` sent without one is mojibake.
  expect(response?.headers()['content-type'], 'the notices are served as UTF-8 plain text').toMatch(
    /^text\/plain;\s*charset=utf-8$/i,
  );

  const sections = noticeSections((await response?.text()) ?? '');
  // The stylesheet carries Tailwind's preflight, although Tailwind is a devDependency.
  const unnoticed = [...runtimeDependencies(), 'tailwindcss'].flatMap((name) => {
    const body = sections.get(name);
    if (body === undefined) {
      return [`${name}: no section`];
    }
    return body.trim() === '' ? [`${name}: a heading with no licence text`] : [];
  });
  expect(unnoticed, 'every runtime dependency, and Tailwind, has a notice').toEqual([]);
});
