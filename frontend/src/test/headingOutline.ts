/**
 * What is wrong with the document's heading outline, for a test to assert empty (WCAG 1.3.1, 2.4.6).
 *
 * A sound outline starts with one `h1`, has no other, and never goes more than one level deeper than the
 * heading before it: an `h3` straight after the `h1` hides that it belongs to a section nobody named.
 *
 * @returns one line per problem, such as "h1 then h3 (Evening walk)", or none
 */
export function headingOutlineProblems(): string[] {
  const headings = Array.from(document.querySelectorAll('h1, h2, h3, h4, h5, h6'));
  const levels = headings.map((h) => Number(h.tagName[1]));
  const problems: string[] = [];
  if (levels[0] !== 1) problems.push(`the first heading is h${levels[0] ?? ' missing'}, not h1`);
  if (levels.filter((level) => level === 1).length > 1) problems.push('more than one h1');
  levels.forEach((level, i) => {
    if (i > 0 && level > levels[i - 1] + 1) {
      problems.push(`h${levels[i - 1]} then h${level} (${headings[i].textContent?.trim()})`);
    }
  });
  return problems;
}
