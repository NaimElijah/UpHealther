import { describe, it, expect, beforeEach, vi } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import RegisterPage from './RegisterPage';
import { AuthContext, type AuthContextType } from '../contexts/authContextValue';
import { ThemeProvider } from '../contexts/ThemeProvider';

const register = vi.fn();

/** What `register` rejects with when the API answers: an axios error carrying a response. */
function apiFailure(status: number, body: unknown): AxiosError {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
  const response = { data: body, status, statusText: '', headers: new AxiosHeaders(), config } as AxiosResponse;
  return new AxiosError('Request failed', String(status), config, {}, response);
}

function contextWith(overrides: Partial<AuthContextType> = {}): AuthContextType {
  return {
    user: null,
    isLoading: false,
    isAuthenticated: false,
    login: async () => {},
    register,
    logout: async () => {},
    ...overrides,
  };
}

function renderRegister(ctx: AuthContextType = contextWith()) {
  return render(
    <ThemeProvider>
      <AuthContext.Provider value={ctx}>
        <MemoryRouter initialEntries={['/register']}>
          <Routes>
            <Route path="/register" element={<RegisterPage />} />
            <Route path="/dashboard" element={<div>the dashboard</div>} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>
    </ThemeProvider>,
  );
}

/** What a field's error says, read the way a screen reader reaches it: through `aria-describedby`. */
function errorOf(label: string): string | null | undefined {
  const field = screen.getByLabelText(label);
  if (field.getAttribute('aria-invalid') !== 'true') return null;
  return document.getElementById(field.getAttribute('aria-describedby') ?? '')?.textContent;
}

/** Fills every field and submits, the way a user would. */
function fillAndSubmit({
  name = 'Dana Walker',
  email = 'dana@example.com',
  password = 'eight-ch',
  confirm = password,
}: { name?: string; email?: string; password?: string; confirm?: string } = {}) {
  fireEvent.change(screen.getByLabelText('Full Name'), { target: { value: name } });
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: email } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } });
  fireEvent.change(screen.getByLabelText('Confirm Password'), { target: { value: confirm } });
  fireEvent.submit(screen.getByRole('button', { name: 'Create Account' }));
}

/**
 * FR-37 — the theme control is reachable before signing in — and the registration form's own checks and
 * failure paths.
 *
 * The password checks here are immediate feedback, not enforcement (the API refuses a short password
 * itself, BR-17), so what is pinned is that a form the API would refuse is never sent, and that the
 * API's own refusal reaches the user as the API worded it. The latter was once a hard-coded "Email may
 * already be in use." for every failure, a 500 and a network outage included.
 */
describe('RegisterPage', () => {
  beforeEach(() => {
    register.mockReset();
    register.mockResolvedValue(undefined);
  });

  it('GivenAVisitorWhoIsNotSignedIn_WhenTheRegisterPageRenders_ThenTheThemeControlIsReachable', () => {
    renderRegister();

    expect(screen.getByRole('group', { name: /theme/i })).toBeDefined();
  });

  it('GivenEveryFieldValid_WhenTheFormIsSubmitted_ThenTheAccountIsRegisteredAndTheUserTakenToTheDashboard', async () => {
    renderRegister();

    fillAndSubmit();

    await waitFor(() => expect(screen.getByText('the dashboard')).toBeDefined());
    expect(register).toHaveBeenCalledWith('Dana Walker', 'dana@example.com', 'eight-ch');
  });

  // An empty password is left out: it is also shorter than the minimum, so the length check would refuse
  // it even without this one.
  it.each([
    ['Name', { name: '' }],
    ['Email', { email: '' }],
  ])('GivenAnEmpty%s_WhenTheFormIsSubmitted_ThenItIsRefusedWithoutCallingTheApi', async (field, blank) => {
    renderRegister();

    fillAndSubmit(blank);

    await waitFor(() => expect(screen.getByText(`${field} is required.`)).toBeDefined());
    expect(register).not.toHaveBeenCalled();
  });

  it('GivenAConfirmationThatDiffers_WhenTheFormIsSubmitted_ThenItIsRefusedWithoutCallingTheApi', async () => {
    renderRegister();

    fillAndSubmit({ password: 'eight-ch', confirm: 'eight-cH' });

    await waitFor(() => expect(screen.getByText('Passwords do not match.')).toBeDefined());
    expect(register).not.toHaveBeenCalled();
    expect(screen.queryByText('the dashboard')).toBeNull();
  });

  it('GivenAPasswordOneCharacterShortOfTheMinimum_WhenTheFormIsSubmitted_ThenItIsRefusedWithoutCallingTheApi', async () => {
    renderRegister();

    fillAndSubmit({ password: 'seven-c' });

    await waitFor(() => expect(screen.getByText('Password must be at least 8 characters.')).toBeDefined());
    expect(register).not.toHaveBeenCalled();
  });

  it('GivenAPasswordOfExactlyTheMinimum_WhenTheFormIsSubmitted_ThenItIsSent', async () => {
    renderRegister();

    fillAndSubmit({ password: '12345678' });

    await waitFor(() => expect(register).toHaveBeenCalledWith('Dana Walker', 'dana@example.com', '12345678'));
    expect(screen.queryByText(/Password must be at least/)).toBeNull();
  });

  it('GivenAnEmailAlreadyRegistered_WhenTheFormIsSubmitted_ThenTheApisOwnRefusalIsShownAndNothingNavigates', async () => {
    register.mockRejectedValue(
      apiFailure(422, { message: 'That email is already registered', traceId: '0af7651916cd43dd8448eb211c80319c' }),
    );
    renderRegister();

    fillAndSubmit();

    await waitFor(() =>
      expect(
        screen.getByText('That email is already registered (reference 0af7651916cd43dd8448eb211c80319c)'),
      ).toBeDefined(),
    );
    expect(screen.queryByText('the dashboard')).toBeNull();
  });

  it('GivenTheServerFails_WhenTheFormIsSubmitted_ThenTheUserIsNotToldTheirEmailMayBeInUse', async () => {
    // A 500 is not a taken address; saying it might be sends somebody looking for an account they never
    // created, and drops the trace id that would have found the fault.
    register.mockRejectedValue(
      apiFailure(500, { message: 'Internal server error', traceId: '4bf92f3577b34da6a3ce929d0e0e4736' }),
    );
    renderRegister();

    fillAndSubmit();

    await waitFor(() =>
      expect(screen.getByText('Internal server error (reference 4bf92f3577b34da6a3ce929d0e0e4736)')).toBeDefined(),
    );
    expect(screen.queryByText(/in use/i)).toBeNull();
  });

  it('GivenAPasswordTheApiRefusesByField_WhenTheFormIsSubmitted_ThenTheFieldsReasonIsShown', async () => {
    // A 400 carries only "Validation failed" in `message`; the reason is in `fieldErrors`.
    register.mockRejectedValue(
      apiFailure(400, {
        message: 'Validation failed',
        fieldErrors: { password: 'must be at most 72 bytes; an accented letter takes two and an emoji four' },
        traceId: '6e0c63257de34c92bf9efcd03927272e',
      }),
    );
    renderRegister();

    fillAndSubmit({ password: 'a long passphrase' });

    await waitFor(() =>
      expect(
        screen.getByText('Password: must be at most 72 bytes; an accented letter takes two and an emoji four.'),
      ).toBeDefined(),
    );
  });

  it('GivenAnAlreadySignedInUser_WhenTheyOpenTheRegisterPage_ThenTheyAreSentToTheDashboard', async () => {
    renderRegister(contextWith({ isAuthenticated: true }));

    await waitFor(() => expect(screen.getByText('the dashboard')).toBeDefined());
  });
});

/**
 * The document is titled after the page, from its first render, so a tab, the history list and a screen
 * reader opening the page all say which page it is. Every route was titled "UpHealther" alone (WCAG
 * 2.4.2, finding X9.2).
 */
describe('RegisterPage title', () => {
  it('GivenThePage_WhenItOpens_ThenTheDocumentIsTitledAfterIt', () => {
    document.title = '';

    renderRegister();

    expect(document.title).toBe('Create account – UpHealther');
  });
});

/**
 * The registration form marks its fields required, announces its message, and sends focus to the field
 * to fix (WCAG 3.3.1, 3.3.2, 4.1.3, finding X9.5).
 */
describe('RegisterPage form errors', () => {
  // `fireEvent.submit` skips the browser's own validation, so without this a form that lost `noValidate`
  // passed every test, while in a browser the native bubble took the place of the form's message and focus
  // (review finding 16 on #407).
  it('GivenTheRegistrationForm_WhenItRenders_ThenItChecksItselfRatherThanTheBrowser', () => {
    renderRegister();

    expect(screen.getByLabelText('Email').closest('form')?.noValidate).toBe(true);
  });

  it('GivenTheRegistrationForm_WhenItRenders_ThenEveryFieldIsMarkedRequired', () => {
    renderRegister();

    for (const label of ['Full Name', 'Email', 'Password', 'Confirm Password']) {
      expect(screen.getByLabelText(label).getAttribute('aria-required')).toBe('true');
    }
    // Review finding 24 on #407: nothing said what the asterisk meant.
    expect(screen.getByText('Required').closest('form')).toBe(screen.getByLabelText('Email').closest('form'));
  });

  it('GivenAConfirmationThatDiffers_WhenTheFormIsSubmitted_ThenTheMessageIsAnnouncedAndTheConfirmationHasFocus', async () => {
    renderRegister();

    fillAndSubmit({ password: 'eight-ch', confirm: 'nine-chr' });

    expect((await screen.findByRole('alert')).textContent).toBe('Passwords do not match.');
    expect(document.activeElement).toBe(screen.getByLabelText('Confirm Password'));
  });

  it('GivenAShortPassword_WhenTheFormIsSubmitted_ThenThePasswordHasFocus', async () => {
    renderRegister();

    fillAndSubmit({ password: 'short' });

    await screen.findByRole('alert');
    expect(document.activeElement).toBe(screen.getByLabelText('Password'));
  });

  // Review finding 12 on #407: one page-wide message named no field, and none was marked invalid, so a
  // screen reader on the field to fix never heard what was wrong with it (WCAG 3.3.1).
  it('GivenAnEmptyForm_WhenItIsSubmitted_ThenEachMissingFieldIsInvalidAndSaysSo', async () => {
    renderRegister();

    fillAndSubmit({ name: '', email: '', password: '', confirm: '' });

    expect(await screen.findByText('Name is required.')).toBeDefined();
    expect(errorOf('Full Name')).toBe('Name is required.');
    expect(errorOf('Email')).toBe('Email is required.');
    expect(errorOf('Password')).toBe('Password is required.');
    expect(document.activeElement).toBe(screen.getByLabelText('Full Name'));
  });

  it('GivenAConfirmationThatDiffers_WhenTheFormIsSubmitted_ThenTheConfirmationIsInvalidAndSaysSo', async () => {
    renderRegister();

    fillAndSubmit({ password: 'eight-ch', confirm: 'nine-chr' });

    await screen.findByText('Passwords do not match.');
    expect(errorOf('Confirm Password')).toBe('Passwords do not match.');
    expect(errorOf('Password')).toBeNull();
  });

  it('GivenAShortPassword_WhenTheFormIsSubmitted_ThenThePasswordIsInvalidAndSaysSo', async () => {
    renderRegister();

    fillAndSubmit({ password: 'short' });

    await screen.findByText('Password must be at least 8 characters.');
    expect(errorOf('Password')).toBe('Password must be at least 8 characters.');
  });

  it('GivenAnEmptyName_WhenTheFormIsSubmitted_ThenTheNameHasFocus', async () => {
    renderRegister();

    fillAndSubmit({ name: '' });

    await screen.findByRole('alert');
    expect(document.activeElement).toBe(screen.getByLabelText('Full Name'));
  });
});

/**
 * The page has a main landmark around its form, as every signed-in page has through the shell. It renders
 * outside the shell, so it had none, and a screen reader's landmark list was empty (WCAG 1.3.1, 2.4.1,
 * finding X9.18).
 */
describe('RegisterPage landmark', () => {
  it('GivenThePage_WhenItRenders_ThenItsHeadingAndFormAreInsideTheMainLandmark', () => {
    renderRegister();

    const main = within(screen.getByRole('main'));
    expect(main.getByRole('heading', { level: 1, name: 'Create Account' })).toBeDefined();
    expect(main.getByLabelText('Email')).toBeDefined();
  });
});
