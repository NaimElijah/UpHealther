import React, { useEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';
import { moveFocusToPageHeading } from '../components/ui/pageHeadingFocus';

/**
 * Moves focus to the new page's heading whenever this tab's location changes, but not on the first
 * render (ADR-083, WCAG 2.4.3). Renders nothing.
 *
 * Mounted after the routes, inside the router, so its effect runs after the new page's own effects in
 * the same commit. That ordering is what makes it work with the phone menu: the menu's `Modal` closes in
 * that commit and hands focus back to its button in its cleanup, and every cleanup runs before any
 * effect, so this move comes last and wins.
 *
 * It compares each location's `key` with the one before rather than with the first, because Back to the
 * first entry restores the first entry's key, and that is a route change too.
 */
const RouteFocus: React.FC = () => {
  const { key } = useLocation();
  const previousKey = useRef(key);

  useEffect(() => {
    if (key === previousKey.current) return;
    previousKey.current = key;
    moveFocusToPageHeading();
  }, [key]);

  return null;
};

export default RouteFocus;
