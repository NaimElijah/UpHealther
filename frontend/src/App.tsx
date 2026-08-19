import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from './contexts/AuthContext';
import { ThemeProvider } from './contexts/ThemeProvider';
import AppRouter from './router';

/**
 * Server-state cache shared by the whole application.
 *
 * A single retry, because a failed call here is usually a 4xx that will fail identically; and a 30
 * second staleness window, which keeps navigation between pages from refetching data that was just
 * shown while still picking up changes made in another tab reasonably soon.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 30_000,
    },
  },
});

/**
 * Application root: installs the theme, the query cache and the auth provider around the router.
 *
 * The order matters. Auth reads through the query client, and everything the router renders needs
 * both. The theme sits outside them all because it depends on neither a session nor server state, and
 * because the login and register pages render outside `Layout` but still inside `App` — which is what
 * puts the theme toggle within reach before anyone has signed in.
 */
const App: React.FC = () => (
  <ThemeProvider>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <AppRouter />
      </AuthProvider>
    </QueryClientProvider>
  </ThemeProvider>
);

export default App;
