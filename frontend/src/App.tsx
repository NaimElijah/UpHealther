import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from './contexts/AuthContext';
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
 * Application root: installs the query cache and the auth provider around the router.
 *
 * The order matters — auth reads through the query client, and everything the router renders needs
 * both.
 */
const App: React.FC = () => (
  <QueryClientProvider client={queryClient}>
    <AuthProvider>
      <AppRouter />
    </AuthProvider>
  </QueryClientProvider>
);

export default App;
