import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import Input from './Input';
import Select from './Select';

const OPTIONS = [
  { value: 'a', label: 'First' },
  { value: 'b', label: 'Second' },
];

/**
 * A field's error is tied to the field and announced, and a required field says so. The error was a bare
 * paragraph below the field, which a screen reader reached only by reading on, and nothing marked a field
 * as required before a submit failed (WCAG 3.3.1, 3.3.2, 4.1.2, 4.1.3, finding X9.5).
 */
describe.each([
  ['Input', (props: { error?: string; required?: boolean }) => <Input label="Name" {...props} />],
  ['Select', (props: { error?: string; required?: boolean }) => <Select label="Name" options={OPTIONS} {...props} />],
])('%s', (_, field) => {
  it('GivenAnError_WhenTheFieldRenders_ThenItIsInvalidAndDescribedByTheAnnouncedMessage', () => {
    render(field({ error: 'Name is required.' }));

    const control = screen.getByLabelText('Name');
    expect(control.getAttribute('aria-invalid')).toBe('true');
    const message = document.getElementById(control.getAttribute('aria-describedby') ?? '');
    expect(message?.textContent).toBe('Name is required.');
    expect(message?.getAttribute('role')).toBe('alert');
  });

  it('GivenNoError_WhenTheFieldRenders_ThenItIsNeitherInvalidNorDescribed', () => {
    render(field({}));

    const control = screen.getByLabelText('Name');
    expect(control.hasAttribute('aria-invalid')).toBe(false);
    expect(control.hasAttribute('aria-describedby')).toBe(false);
  });

  it('GivenARequiredField_WhenItRenders_ThenItIsMarkedRequiredForEveryone', () => {
    render(field({ required: true }));

    const control = screen.getByLabelText('Name');
    expect(control.hasAttribute('required')).toBe(true);
    expect(control.getAttribute('aria-required')).toBe('true');
    expect(screen.getByText('*').getAttribute('aria-hidden')).toBe('true');
  });

  it('GivenTwoFieldsWithTheSameLabel_WhenBothRender_ThenEachHasItsOwnIdAndMessage', () => {
    render(
      <>
        {field({ error: 'First problem.' })}
        {field({ error: 'Second problem.' })}
      </>,
    );

    const [first, second] = screen.getAllByLabelText('Name');
    expect(first.id).not.toBe(second.id);
    expect(first.getAttribute('aria-describedby')).not.toBe(second.getAttribute('aria-describedby'));
  });
});

describe('Input description', () => {
  it('GivenTheCallerDescribesTheFieldToo_WhenItHasAnError_ThenBothDescriptionsAreKept', () => {
    render(
      <>
        <p id="hint">Use your work address.</p>
        <Input label="Email" aria-describedby="hint" error="That address is taken." />
      </>,
    );

    const ids = (screen.getByLabelText('Email').getAttribute('aria-describedby') ?? '').split(' ');
    expect(ids).toContain('hint');
    expect(ids).toHaveLength(2);
  });
});
