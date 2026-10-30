/**
 * Browser entry point: mounts the application into `#root`, and unmounts it when a session ends.
 *
 * StrictMode is on, so in development effects run twice on mount — anything that misbehaves under a
 * double invocation is a real bug, not an artefact.
 */
import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import { onSessionEnd } from './api/session';
import './index.css';

const root = ReactDOM.createRoot(document.getElementById('root')!);

root.render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);

// Emptying the query cache leaves the rendered page in place, so a page the back/forward cache keeps
// would show the previous user's data until its reload, and a query still mounted would fetch again with
// no token (ADR-066). Unmounting synchronously is safe because every caller of `endSession` runs outside
// React's render and commit: `AuthContext`'s sign-out after an await, the response interceptor, and the
// STOMP client's ERROR callback.
onSessionEnd(() => root.unmount());
