import { fireEvent } from '@testing-library/react';

/**
 * Leaves a number or date field as a browser leaves it when its text cannot be read as a value of its
 * type, such as `3e` in a number field or a date with one part erased: its value is `''`, as an empty
 * field's is, and only `validity.badInput` tells the two apart.
 *
 * jsdom never sets `badInput`, so it is stubbed on this one element, after the change event that empties
 * the value, the event a browser sends as the text stops parsing.
 *
 * @param field the `<input type="number">` or `<input type="date">` to leave unreadable
 */
export function enterUnreadableText(field: HTMLElement): void {
  fireEvent.change(field, { target: { value: '' } });
  Object.defineProperty(field, 'validity', { configurable: true, value: { badInput: true, valid: false } });
}
