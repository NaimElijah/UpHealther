/**
 * Browser entry point: mounts the application into `#root`.
 *
 * StrictMode is on, so in development effects run twice on mount — anything that misbehaves under a
 * double invocation is a real bug, not an artefact.
 */
import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './index.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
