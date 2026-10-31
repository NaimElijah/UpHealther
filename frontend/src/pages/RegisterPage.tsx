import React, { useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import Button from '../components/ui/Button';
import Input from '../components/ui/Input';
import PageHeading from '../components/ui/PageHeading';
import ThemeToggle from '../components/theme/ThemeToggle';
import { toApiError, toFormMessage } from '../api/apiError';
import { useDocumentTitle } from '../hooks/useDocumentTitle';

/**
 * Must match `AuthController.PASSWORD_MIN`. Nothing keeps the two in step automatically. The generated
 * wire types carry shapes, not constraints, and a contract test for one integer is more machinery than
 * it earns.
 * The server is the enforcer; this only decides when the message appears without a round trip.
 */
const PASSWORD_MIN_LENGTH = 8;

/**
 * Account creation page, and the other route reachable without a session.
 *
 * The password rules checked here — matching confirmation, minimum length — are for immediate feedback
 * only; the API is the authority on whether a registration is accepted, and since #54 it genuinely
 * enforces the minimum rather than trusting this page to. A duplicate email is refused by the API with
 * a message that confirms the address is taken without repeating it; a registration form with no
 * email-verification step cannot avoid confirming that much, so it is the server's job to slow down
 * anyone asking at volume.
 *
 * Signs the new account straight in, since registration returns a token. Refuses to submit until the
 * page-load restore has settled (`isLoading`), for the reason `LoginPage` gives.
 */
const RegisterPage: React.FC = () => {
  useDocumentTitle('Create account');
  const { register, isAuthenticated, isLoading } = useAuth();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const nameField = useRef<HTMLInputElement>(null);
  const emailField = useRef<HTMLInputElement>(null);
  const passwordField = useRef<HTMLInputElement>(null);
  const confirmField = useRef<HTMLInputElement>(null);

  /** Says what is wrong and sends focus to the field that has to change. */
  const refuse = (message: string, field: React.RefObject<HTMLInputElement | null>) => {
    setError(message);
    field.current?.focus();
  };

  React.useEffect(() => {
    if (isAuthenticated) navigate('/dashboard', { replace: true });
  }, [isAuthenticated, navigate]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (isLoading) return;
    setError('');
    if (!name || !email || !password) {
      refuse('Please fill in all fields.', !name ? nameField : !email ? emailField : passwordField);
      return;
    }
    if (password !== confirm) {
      refuse('Passwords do not match.', confirmField);
      return;
    }
    if (password.length < PASSWORD_MIN_LENGTH) {
      refuse(`Password must be at least ${PASSWORD_MIN_LENGTH} characters.`, passwordField);
      return;
    }
    setLoading(true);
    try {
      await register(name, email, password);
      navigate('/dashboard', { replace: true });
    } catch (thrown) {
      // Was a hard-coded "Email may already be in use." for every failure, including a 500 and a
      // network outage - which sent people looking for an account they had never created. The API
      // says what went wrong; the trace id is appended so a report about the ones it cannot explain
      // is worth acting on.
      // toFormMessage prefers the field detail: a 400 puts only "Validation failed" in `message` and
      // the reason in `fieldErrors`, so rendering `message` left somebody who pasted a passphrase past
      // BCrypt's 72-byte limit with nothing to act on.
      setError(toFormMessage(toApiError(thrown)));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-brand-soft to-accent-soft flex items-center justify-center p-4 relative">
      {/* These pages render outside Layout, so the navbar toggle cannot reach them. */}
      <div className="absolute top-4 right-4">
        <ThemeToggle />
      </div>
      {/* The main landmark: this page renders outside Layout, whose <main> the signed-in pages share. */}
      <main className="bg-surface rounded-2xl shadow-xl p-8 w-full max-w-md">
        <div className="text-center mb-8">
          <div className="text-5xl mb-3">💪</div>
          <PageHeading className="text-2xl font-bold text-fg">Create Account</PageHeading>
          <p className="text-fg-subtle mt-1">Start your health journey today</p>
        </div>
        {/* noValidate: the form checks its own fields, so the message is the page's, tied to the field and
              announced, and focus goes to the field to fix, rather than a browser's bubble. */}
        <form onSubmit={handleSubmit} noValidate className="space-y-4">
          <Input
            ref={nameField}
            label="Full Name"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="John Doe"
            autoComplete="name"
            required
          />
          <Input
            ref={emailField}
            label="Email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@example.com"
            autoComplete="email"
            required
          />
          <Input
            ref={passwordField}
            label="Password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="••••••••"
            autoComplete="new-password"
            required
          />
          <Input
            ref={confirmField}
            label="Confirm Password"
            type="password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            placeholder="••••••••"
            autoComplete="new-password"
            required
          />
          {error && (
            <p role="alert" className="text-sm text-danger-fg text-center">
              {error}
            </p>
          )}
          <Button type="submit" loading={loading || isLoading} className="w-full">
            Create Account
          </Button>
        </form>
        <p className="text-center text-sm text-fg-subtle mt-6">
          Already have an account?{' '}
          <Link to="/login" className="text-brand-fg hover:underline font-medium">
            Sign In
          </Link>
        </p>
      </main>
    </div>
  );
};

export default RegisterPage;
