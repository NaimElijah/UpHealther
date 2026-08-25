import { describe, it, expect, beforeEach, vi } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { render, screen, waitFor } from '@testing-library/react';
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
    token: null,
    isLoading: false,
    isAuthenticated: false,
    login,
    register: async () => {},
    logout: () => {},
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
    login.mockRejectedValue(apiFailure(500, {
      message: 'Internal server error',
      traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
    }));
    renderLogin();

    await signIn();

    await waitFor(() => expect(screen.getByText(/Internal server error/)).toBeDefined());
    expect(screen.getByText(/4bf92f3577b34da6a3ce929d0e0e4736/)).toBeDefined();
    expect(screen.queryByText('Invalid email or password.')).toBeNull();
  });

  it('GivenAnUnknownEmailAndAWrongPassword_WhenEachIsTried_ThenTheMessageIsTheSame', async () => {
    // Two different failures, one message. A different message per case is an account-enumeration
    // oracle that costs nothing to hand out.
    login.mockRejectedValue(apiFailure(401, { message: 'Invalid credentials' }));
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

    await waitFor(() => expect(screen.getByText('Please fill in all fields.')).toBeDefined());
    expect(login).not.toHaveBeenCalled();
  });

  it('GivenAnAlreadySignedInUser_WhenTheyOpenTheLoginPage_ThenTheyAreSentToTheDashboard', async () => {
    // Typing the URL with a live session should not offer a second sign-in.
    renderLogin(contextWith({ isAuthenticated: true, token: 'stored.token' }));

    await waitFor(() => expect(screen.getByText('the dashboard')).toBeDefined());
  });
});
