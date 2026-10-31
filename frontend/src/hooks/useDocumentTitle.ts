import { useEffect } from 'react';

/** The product's name, which ends every page's title. `index.html`'s own title is this alone. */
const APP_NAME = 'UpHealther';

/**
 * Titles the document after the page that calls it, as "{page} – UpHealther" (WCAG 2.4.2).
 *
 * Every route page calls it at its top, before any early return, so the title is right while the page
 * is still loading and when it failed to load, not only once its data arrived. The format lives here
 * alone. Nothing restores the previous title on unmount: the next page sets its own, and restoring
 * first would flash the old one in the tab.
 *
 * `index.html` keeps its fixed title, because editing it would change the boot script's CSP hash.
 *
 * @param pageTitle what the page is, said as its heading says it
 */
export function useDocumentTitle(pageTitle: string): void {
  useEffect(() => {
    document.title = `${pageTitle} – ${APP_NAME}`;
  }, [pageTitle]);
}
