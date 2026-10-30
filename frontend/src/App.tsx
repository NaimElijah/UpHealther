import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from './contexts/AuthContext';
import ErrorBoundary from './components/ErrorBoundary';
import { ThemeProvider } from './contexts/ThemeProvider';
import AppRouter from './router';
import { createQueryClient } from './lib/queryClient';

/** Server-state cache shared by the whole application; its defaults are explained at the factory. */
const queryClient = createQueryClient();

/**
 * Application root: installs the theme, the query cache and the auth provider around the router.
 *
 * The order matters. The router mounts the notification provider, which reads both the session and
 * the query cache, so both sit outside the router. Auth itself does not use the query client. The
 * theme sits outside them all because it depends on neither a session nor server state, and
 * because the login and register pages render outside `Layout` but still inside `App` — which is what
 * puts the theme toggle within reach before anyone has signed in.
 *
 * `ErrorBoundary` sits *inside* the theme and *outside* everything else: inside so its fallback is
 * drawn in the theme the user chose, outside so a throw anywhere below it is contained. Before it,
 * any render-time error unmounted the whole tree to a blank page with nothing to read and nothing to
 * report.
 */
const App: React.FC = () => (
  <ThemeProvider>
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <AppRouter />
        </AuthProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  </ThemeProvider>
);

export default App;
