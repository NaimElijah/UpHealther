/**
 * The segments that no encoding keeps in place: the URL parser resolves `.` and `..` whether or not they
 * are percent-encoded, and an empty segment names the collection instead of an item of it.
 */
const UNENCODABLE_SEGMENTS = new Set(['', '.', '..']);

/**
 * Builds an API path from a template literal, putting each interpolated value in as exactly one path
 * segment: `` apiPath`/api/upgrades/${id}/plan` ``.
 *
 * Every id that reaches a path comes from a route or a response, and a route's `:id` arrives decoded,
 * so `/upgrades/..%2Fhealth-areas%3F` hands the page `../health-areas?`. Interpolated as it is, that
 * climbs out of the upgrade's path and turns the rest into a query string (S5.D4, #339). Each value is
 * therefore percent-encoded with `encodeURIComponent`, which leaves no `/`, `?` or `#` to read as
 * structure.
 *
 * @param template  the literal parts of the path, as the template tag receives them
 * @param segments  the interpolated values, each one whole segment
 * @returns the path, with every interpolated value encoded
 * @throws RangeError when a value is empty, `.` or `..`, which would move the path however encoded
 */
export function apiPath(template: TemplateStringsArray, ...segments: string[]): string {
  return template.reduce((path, literal, index) => {
    if (index === 0) return literal;
    const segment = segments[index - 1];
    if (UNENCODABLE_SEGMENTS.has(segment)) {
      throw new RangeError('An API path segment cannot be empty, "." or "..".');
    }
    return path + encodeURIComponent(segment) + literal;
  }, '');
}
