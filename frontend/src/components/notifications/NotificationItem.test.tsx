import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import NotificationItem from './NotificationItem';
import { notificationAsSent } from '../../test/wireFixtures';

/**
 * An unread row says so in words. The marker was an empty span carrying `aria-label="unread"`, a name
 * ARIA 1.2 does not allow on a generic element, so whether it was read at all was up to the browser,
 * and otherwise the state was a tint and a dot (WCAG 4.1.2, 1.4.1, finding X9.22).
 */
describe('NotificationItem', () => {
  it('GivenAnUnreadNotification_WhenItsRowRenders_ThenItsNameSaysItIsUnreadBeforeItsTitle', () => {
    render(
      <NotificationItem notification={notificationAsSent({ title: 'Time to log', read: false })} onSelect={() => {}} />,
    );

    expect(screen.getByRole('button', { name: /^Unread:.*Time to log/ })).toBeDefined();
    expect(document.querySelector('[aria-label="unread"]')).toBeNull();
  });

  it('GivenAReadNotification_WhenItsRowRenders_ThenItsNameDoesNotSayUnread', () => {
    render(
      <NotificationItem notification={notificationAsSent({ title: 'Time to log', read: true })} onSelect={() => {}} />,
    );

    expect(screen.getByRole('button', { name: /Time to log/ }).textContent).not.toContain('Unread');
  });
});
