import { describe, it, expect, beforeEach, vi } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import LoginPage from './LoginPage';
import { AuthContext, type AuthContextType } from '../contexts/authContextValue';
import { ThemeProvider } from '../contexts/ThemeProvider';

const login = vi.fn();

/**
 * What `login` actually rejects with: an axios error carrying a response.
 *
 * Previously these tests rejected with `new Error('401')`, which has no status on it. That was fine
 * while every failure produced the same sentence, and stopped being fine the moment the page started
 * telling a rejected credential apart from a server fault — the stub had no way to say which it was.
 */
function apiFailure(status: number, body: unknown = {}): AxiosError {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
  const response = {
    data: body,
    status,
    statusText: '',
    headers: new AxiosHeaders(),
    config,
  } as AxiosResponse;
  return new AxiosError('Request failed', String(status), config, {}, response);
}

function contextWith(overrides: Partial<AuthContextType> = {}): AuthContextType {
  return {
    user: null,
    isLoading: false,
    isAuthenticated: false,
    login,
    register: async () => {},
    logout: async () => {},
    ...overrides,
  };
}

function renderLogin(ctx: AuthContextType = contextWith()) {
  return render(
    <ThemeProvider>
      <AuthContext.Provider value={ctx}>
        <MemoryRouter initialEntries={['/login']}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
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

/** Fills both fields and submits, the way a user would. */
async function signIn(email = 'someone@example.com', password = 's3cret!') {
  const { fireEvent } = await import('@testing-library/react');
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: email } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } });
  fireEvent.submit(screen.getByRole('button', { name: 'Sign In' }));
}

/**
 * FR-37 — the theme control is reachable before signing in — and the sign-in form's failure paths.
 *
 * FR-37 is easy to break invisibly: this page renders outside `Layout`, so the navbar's toggle cannot
 * reach it and the control has to be placed here separately. Deleting it leaves a working application
 * that a user in a dark room cannot dim until after they have signed in.
 *
 * The error message is asserted to be identical whichever half of the credentials was wrong. Saying
 * which would tell an attacker that an email is registered, and it is the kind of "helpful" change
 * that gets made in good faith.
 */
describe('LoginPage', () => {
  beforeEach(() => {
    login.mockReset();
    login.mockResolvedValue(undefined);
  });

  it('GivenAVisitorWhoIsNotSignedIn_WhenTheLoginPageRenders_ThenTheThemeControlIsReachable', () => {
    renderLogin();

    expect(screen.getByRole('group', { name: /theme/i })).toBeDefined();
  });

  it('GivenValidCredentials_WhenTheFormIsSubmitted_ThenTheUserIsSignedInAndTakenToTheDashboard', async () => {
    renderLogin();

    await signIn();

    await waitFor(() => expect(login).toHaveBeenCalledWith('someone@example.com', 's3cret!'));
    await waitFor(() => expect(screen.getByText('the dashboard')).toBeDefined());
  });

  it('GivenCredentialsTheServerRejects_WhenTheFormIsSubmitted_ThenAnErrorIsShownAndNothingNavigates', async () => {
    login.mockRejectedValue(apiFailure(401, { message: 'Invalid credentials' }));
    renderLogin();

    await signIn();

    await waitFor(() => expect(screen.getByText('Invalid email or password.')).toBeDefined());
    expect(screen.queryByText('the dashboard')).toBeNull();
  });

  it('GivenTheServerFails_WhenTheFormIsSubmitted_ThenTheUserIsNotToldTheirPasswordIsWrong', async () => {
    // A database outage during login is a 500 with a trace id on the body, not a rejected credential.
    // Answering it with "invalid email or password" sends the user to reset a password that was never
    // the problem, and throws away the one string that would have found the fault in the log.
    login.mockRejectedValue(
      apiFailure(500, {
        message: 'Internal server error',
        traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
      }),
    );
    renderLogin();

    await signIn();

    await waitFor(() => expect(screen.getByText(/Internal server error/)).toBeDefined());
    expect(screen.getByText(/4bf92f3577b34da6a3ce929d0e0e4736/)).toBeDefined();
    expect(screen.queryByText('Invalid email or password.')).toBeNull();
  });

  it('GivenAPasswordOfSpaces_WhenTheApiRefusesItAsBlank_ThenTheFormSaysWhichFieldRatherThanValidationFailed', async () => {
    // Spaces pass the form's own check, and the API's @NotBlank answers 400 with the detail in
    // fieldErrors and only "Validation failed" in the message.
    login.mockRejectedValue(
      apiFailure(400, {
        message: 'Validation failed',
        fieldErrors: { password: 'must not be blank' },
        traceId: 'trace-login',
      }),
    );
    renderLogin();

    await signIn('someone@example.com', '   ');

    expect(await screen.findByText('Password: must not be blank.')).toBeDefined();
    expect(screen.queryByText(/Validation failed/)).toBeNull();
  });

  it('GivenTooManyAttempts_WhenTheFormIsSubmitted_ThenTheUserIsToldToWaitNotThatTheyAreWrong', async () => {
    // The rate limit answers 429 (ADR-017). Showing "invalid email or password" here would send
    // somebody who typed the right password off to reset it, and they would keep trying — which is
    // the behaviour the limit exists to stop.
    login.mockRejectedValue(
      apiFailure(429, {
        message: 'Too many attempts. Please try again in a moment.',
      }),
    );
    renderLogin();

    await signIn();

    await waitFor(() => expect(screen.getByText(/Too many attempts/)).toBeDefined());
    expect(screen.queryByText('Invalid email or password.')).toBeNull();
  });

  it('GivenAnUnknownEmailAndAWrongPassword_WhenEachIsTried_ThenTheMessageIsTheSame', async () => {
    // Two different failures, one message. A different message per case is an account-enumeration
    // oracle that costs nothing to hand out. The two refusals are worded differently on purpose: the
    // page must not relay a 401's own wording, so a server that ever told the cases apart could not leak
    // it through this form.
    login
      .mockRejectedValueOnce(apiFailure(401, { message: 'No account for that email' }))
      .mockRejectedValueOnce(apiFailure(401, { message: 'Wrong password' }));
    const { unmount } = renderLogin();
    await signIn('nobody@example.com', 'whatever');
    await waitFor(() => expect(screen.getByText('Invalid email or password.')).toBeDefined());
    unmount();

    renderLogin();
    await signIn('someone@example.com', 'wrong');

    await waitFor(() => expect(screen.getByText('Invalid email or password.')).toBeDefined());
  });

  it('GivenAnEmptyForm_WhenItIsSubmitted_ThenItIsRefusedWithoutCallingTheApi', async () => {
    const { fireEvent } = await import('@testing-library/react');
    renderLogin();

    fireEvent.submit(screen.getByRole('button', { name: 'Sign In' }));

    await waitFor(() => expect(screen.getByText('Email is required.')).toBeDefined());
    expect(login).not.toHaveBeenCalled();
  });

  it('GivenAnAlreadySignedInUser_WhenTheyOpenTheLoginPage_ThenTheyAreSentToTheDashboard', async () => {
    // Typing the URL with a live session should not offer a second sign-in.
    renderLogin(contextWith({ isAuthenticated: true }));

    await waitFor(() => expect(screen.getByText('the dashboard')).toBeDefined());
  });
});

/**
 * The document is titled after the page, from its first render, so a tab, the history list and a screen
 * reader opening the page all say which page it is. Every route was titled "UpHealther" alone (WCAG
 * 2.4.2, finding X9.2).
 */
describe('LoginPage title', () => {
  it('GivenThePage_WhenItOpens_ThenTheDocumentIsTitledAfterIt', () => {
    document.title = '';

    renderLogin();

    expect(document.title).toBe('Sign in – UpHealther');
  });
});

/**
 * The sign-in form marks its fields required, announces its message, and sends focus to the field to fix.
 * The message was a bare paragraph a screen reader never announced, and focus stayed on the button
 * (WCAG 3.3.1, 3.3.2, 4.1.3, finding X9.5).
 */
describe('LoginPage form errors', () => {
  // `fireEvent.submit` skips the browser's own validation, so without this a form that lost `noValidate`
  // passed every test, while in a browser the native bubble took the place of the form's message and focus
  // (review finding 16 on #407).
  it('GivenTheSignInForm_WhenItRenders_ThenItChecksItselfRatherThanTheBrowser', () => {
    renderLogin();

    expect(screen.getByLabelText('Email').closest('form')?.noValidate).toBe(true);
  });

  it('GivenTheSignInForm_WhenItRenders_ThenBothFieldsAreMarkedRequired', () => {
    renderLogin();

    expect(screen.getByLabelText('Email').getAttribute('aria-required')).toBe('true');
    expect(screen.getByLabelText('Password').getAttribute('aria-required')).toBe('true');
    // Review finding 24 on #407: nothing said what the asterisk meant.
    expect(screen.getByText('Required').closest('form')).toBe(screen.getByLabelText('Email').closest('form'));
  });

  it('GivenAnEmptyEmail_WhenTheFormIsSubmitted_ThenTheMessageIsAnnouncedAndTheEmailFieldHasFocus', async () => {
    renderLogin();

    await signIn('', 's3cret!');

    expect((await screen.findByRole('alert')).textContent).toBe('Email is required.');
    expect(document.activeElement).toBe(screen.getByLabelText('Email'));
  });

  it('GivenAnEmptyPassword_WhenTheFormIsSubmitted_ThenThePasswordFieldHasFocus', async () => {
    renderLogin();

    await signIn('someone@example.com', '');

    await screen.findByRole('alert');
    expect(document.activeElement).toBe(screen.getByLabelText('Password'));
  });

  // Review finding 12 on #407: one page-wide message named neither field, and neither was marked
  // invalid, so a screen reader on Password never heard what was wrong with it (WCAG 3.3.1).
  it('GivenAnEmptyForm_WhenItIsSubmitted_ThenEachFieldIsInvalidAndSaysWhatIsMissing', async () => {
    renderLogin();

    await signIn('', '');

    expect(await screen.findByText('Email is required.')).toBeDefined();
    expect(errorOf('Email')).toBe('Email is required.');
    expect(errorOf('Password')).toBe('Password is required.');
    expect(document.activeElement).toBe(screen.getByLabelText('Email'));
  });

  it('GivenCredentialsTheServerRejects_WhenTheFormIsSubmitted_ThenTheRefusalIsAnnounced', async () => {
    login.mockRejectedValue(apiFailure(401));
    renderLogin();

    await signIn();

    expect((await screen.findByRole('alert')).textContent).toBe('Invalid email or password.');
  });
});

/**
 * The page has a main landmark around its form, as every signed-in page has through the shell. It renders
 * outside the shell, so it had none, and a screen reader's landmark list was empty (WCAG 1.3.1, 2.4.1,
 * finding X9.18).
 */
describe('LoginPage landmark', () => {
  it('GivenThePage_WhenItRenders_ThenItsHeadingAndFormAreInsideTheMainLandmark', () => {
    renderLogin();

    const main = within(screen.getByRole('main'));
    expect(main.getByRole('heading', { level: 1, name: 'UpHealther' })).toBeDefined();
    expect(main.getByLabelText('Email')).toBeDefined();
  });
});
