import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import Button from '../components/ui/Button';
import Input from '../components/ui/Input';
import PageHeading from '../components/ui/PageHeading';
import ThemeToggle from '../components/theme/ThemeToggle';
import { toApiError, toFormMessage } from '../api/apiError';
import { useDocumentTitle } from '../hooks/useDocumentTitle';

/**
 * Sign-in page, one of the two routes reachable without a session.
 *
 * The error message is deliberately the same whichever half of the credentials was wrong — saying
 * which would tell an attacker that an email is registered. That guard applies to a **rejected
 * credential** and nothing else: a 500 is not a wrong password, and answering one with "invalid email
 * or password" sends the user to reset a password that was never the problem, with nothing to quote.
 *
 * Redirects to the dashboard when already signed in, which is what stops a user with a valid session
 * from landing here by typing the URL.
 *
 * Refuses to submit until the page-load restore has settled (`isLoading`). A restore that succeeds
 * signs the tab in as the account whose refresh cookie the browser holds, and redirects it, before a
 * sign-in sent meanwhile could land; that sign-in would then put another account's token under the
 * restored one's socket, or, refused, report nothing on a page already gone.
 */
const LoginPage: React.FC = () => {
  useDocumentTitle('Sign in');
  const { login, isAuthenticated, isLoading } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  React.useEffect(() => {
    if (isAuthenticated) navigate('/dashboard', { replace: true });
  }, [isAuthenticated, navigate]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (isLoading) return;
    setError('');
    if (!email || !password) {
      setError('Please fill in all fields.');
      return;
    }
    setLoading(true);
    try {
      await login(email, password);
      navigate('/dashboard', { replace: true });
    } catch (thrown) {
      const failure = toApiError(thrown);
      // 401 is the only status that means "these credentials are not good", and it keeps the
      // deliberately vague message. Anything else is ours, so say what the API said: the field it
      // refused, or the message and the trace id — this is the highest-traffic failure path in the
      // application, and it was the one place a server fault was being reported to the user as their
      // own mistake.
      setError(failure.status === 401 ? 'Invalid email or password.' : toFormMessage(failure));
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
      <div className="bg-surface rounded-2xl shadow-xl p-8 w-full max-w-md">
        <div className="text-center mb-8">
          <div className="text-5xl mb-3">💪</div>
          <PageHeading className="text-2xl font-bold text-fg">UpHealther</PageHeading>
          <p className="text-fg-subtle mt-1">Sign in to your account</p>
        </div>
        <form onSubmit={handleSubmit} className="space-y-4">
          <Input
            label="Email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@example.com"
            autoComplete="email"
          />
          <Input
            label="Password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="••••••••"
            autoComplete="current-password"
          />
          {error && <p className="text-sm text-danger-fg text-center">{error}</p>}
          <Button type="submit" loading={loading || isLoading} className="w-full">
            Sign In
          </Button>
        </form>
        <p className="text-center text-sm text-fg-subtle mt-6">
          Don't have an account?{' '}
          <Link to="/register" className="text-brand-fg hover:underline font-medium">
            Register
          </Link>
        </p>
      </div>
    </div>
  );
};

export default LoginPage;
