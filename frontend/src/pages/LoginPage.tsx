import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import Button from '../components/ui/Button';
import Input from '../components/ui/Input';
import ThemeToggle from '../components/theme/ThemeToggle';

/**
 * Sign-in page, one of the two routes reachable without a session.
 *
 * The error message is deliberately the same whichever half of the credentials was wrong — saying
 * which would tell an attacker that an email is registered.
 *
 * Redirects to the dashboard when already signed in, which is what stops a user with a valid session
 * from landing here by typing the URL.
 */
const LoginPage: React.FC = () => {
  const { login, isAuthenticated } = useAuth();
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
    setError('');
    if (!email || !password) { setError('Please fill in all fields.'); return; }
    setLoading(true);
    try {
      await login(email, password);
      navigate('/dashboard', { replace: true });
    } catch {
      setError('Invalid email or password.');
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
          <h1 className="text-2xl font-bold text-fg">UpHealther</h1>
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
          <Button type="submit" loading={loading} className="w-full">
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
