import React from 'react';
import Button from './ui/Button';

interface ErrorBoundaryProps {
  children: React.ReactNode;
}

interface ErrorBoundaryState {
  /** The error React caught, or `null` while the tree below is rendering normally. */
  error: Error | null;
}

/**
 * Catches a render-time error so it becomes a message instead of a blank page.
 *
 * Without one, React unmounts the entire tree when any component throws — the user is left looking at
 * white, with nothing to read, nothing to click and nothing to report. This is the only construct that
 * can prevent that, and it has to be a class: `getDerivedStateFromError` has no hooks equivalent.
 *
 * <p><b>It shows, it does not record.</b> There is no `console.error` and no reporting endpoint,
 * deliberately. This repository has no `console` call in any committed file, and `architecture.md`
 * states "no analytics" as a position rather than an omission — so a browser error here has no sink to
 * go to, and inventing one is a decision about sending user data off the device, not a logging tweak.
 * What the user gets instead is a page that says what happened and a reload that works. Errors that
 * came from the API are a different story and do carry a trace id — see `ErrorState`.
 *
 * <p>Mounted inside `ThemeProvider` so the fallback is drawn in the theme the user chose, and around
 * the router so a throw in any page is contained rather than taking the shell with it.
 */
class ErrorBoundary extends React.Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  /**
   * A full reload rather than clearing the error state.
   *
   * Re-rendering the same tree would usually throw again immediately, since whatever was inconsistent
   * about the props or the cache is still inconsistent. Replacing the document is the one recovery
   * that reliably works from here.
   */
  private reload = () => {
    window.location.reload();
  };

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;

    return (
      <div className="min-h-screen flex flex-col items-center justify-center gap-4 bg-canvas px-6 text-center" role="alert">
        <div className="text-5xl">⚠️</div>
        <h1 className="text-xl font-semibold text-fg">This page stopped working.</h1>
        <p className="max-w-md text-sm text-fg-subtle">
          Nothing you have saved is affected. Reloading usually fixes it.
        </p>
        {/* The message, not the stack: a stack trace tells a user nothing and can quote internals. */}
        {error.message && (
          <p className="max-w-md text-xs text-fg-faint font-mono select-all">{error.message}</p>
        )}
        <Button type="button" onClick={this.reload} className="mt-2">
          Reload the page
        </Button>
      </div>
    );
  }
}

export default ErrorBoundary;
