import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import Button from './Button';

/**
 * A loading button refuses a second press without being `disabled`. A disabled button gives up focus,
 * so pressing Save dropped focus to `<body>` for as long as the save took, and a keyboard user started
 * again from the top of the page (WCAG 2.4.3, finding X9.4).
 */
describe('Button', () => {
  it('GivenALoadingButton_WhenItRenders_ThenItStaysFocusableAndSaysItIsBusyAndUnavailable', () => {
    render(<Button loading>Save</Button>);

    const button = screen.getByRole('button', { name: 'Save' });
    expect(button.hasAttribute('disabled')).toBe(false);
    expect(button.getAttribute('aria-disabled')).toBe('true');
    expect(button.getAttribute('aria-busy')).toBe('true');
  });

  it('GivenAButtonThatHasFocus_WhenItStartsLoading_ThenItKeepsFocus', () => {
    const { rerender } = render(<Button>Save</Button>);
    const button = screen.getByRole('button', { name: 'Save' });
    button.focus();

    rerender(<Button loading>Save</Button>);

    expect(document.activeElement).toBe(button);
    expect(button.hasAttribute('disabled')).toBe(false);
  });

  it('GivenALoadingButton_WhenItIsPressed_ThenItsHandlerIsNotCalled', () => {
    const onClick = vi.fn();
    render(
      <Button loading onClick={onClick}>
        Save
      </Button>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(onClick).not.toHaveBeenCalled();
  });

  it('GivenALoadingSubmitButton_WhenItIsPressed_ThenTheFormIsNotSubmittedAgain', () => {
    const onSubmit = vi.fn((e: React.FormEvent) => e.preventDefault());
    render(
      <form onSubmit={onSubmit}>
        <Button type="submit" loading>
          Save
        </Button>
      </form>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(onSubmit).not.toHaveBeenCalled();
  });

  // A caller that also disables the button while its own write is in flight must not take the focus away.
  it('GivenALoadingButtonTheCallerAlsoDisables_WhenItRenders_ThenItIsStillFocusable', () => {
    render(
      <Button loading disabled>
        Save
      </Button>,
    );

    expect(screen.getByRole('button', { name: 'Save' }).hasAttribute('disabled')).toBe(false);
  });

  it('GivenADisabledButtonThatIsNotLoading_WhenItRenders_ThenItIsDisabled', () => {
    render(<Button disabled>Save</Button>);

    const button = screen.getByRole('button', { name: 'Save' });
    expect(button.hasAttribute('disabled')).toBe(true);
    expect(button.hasAttribute('aria-busy')).toBe(false);
  });

  it('GivenAButtonThatIsNotLoading_WhenItIsPressed_ThenItsHandlerIsCalled', () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Save</Button>);

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(onClick).toHaveBeenCalledTimes(1);
  });

  // Review finding 13 on #407: the button wrote its own aria-disabled after the caller's, so a caller's was
  // silently dropped. The two are merged: either one makes the button unavailable.
  it('GivenACallersOwnAriaDisabled_WhenTheButtonIsNotLoading_ThenItIsKept', () => {
    render(<Button aria-disabled="true">Save</Button>);

    expect(screen.getByRole('button', { name: 'Save' }).getAttribute('aria-disabled')).toBe('true');
  });

  it('GivenACallerSaysItIsAvailable_WhenTheButtonIsLoading_ThenItIsStillUnavailable', () => {
    render(
      <Button aria-disabled={false} loading>
        Save
      </Button>,
    );

    expect(screen.getByRole('button', { name: 'Save' }).getAttribute('aria-disabled')).toBe('true');
  });
});
