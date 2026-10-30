// Runs in the suite's default jsdom environment for the reason bootScriptCsp.test.ts gives: the shared
// setup touches localStorage and <html> before every test.
import { describe, expect, it } from 'vitest';
import nginxConf from '../../nginx.conf?raw';

/** The two locations nginx proxies to the backend. */
const PROXIED = ['/api', '/ws'] as const;

/**
 * The body of `location <path> { … }`. The file nests no braces inside a location, so the first `}`
 * after the opening one closes it.
 */
function locationBody(path: string): string {
  const escaped = path.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&');
  const match = nginxConf.match(new RegExp(`location\\s+${escaped}\\s*\\{([^}]*)\\}`));
  if (!match) throw new Error(`No location ${path} block found in nginx.conf.`);
  return match[1];
}

/** The value a block gives `proxy_set_header <name>`, or undefined when it sets none. */
function proxiedHeader(body: string, name: string): string | undefined {
  return body.match(new RegExp(`proxy_set_header\\s+${name}\\s+([^;]+);`))?.[1].trim();
}

/**
 * The edge's own rules for what reaches the backend. nginx is the only thing in front of the API, and
 * Tomcat believes the `X-Forwarded-*` headers it receives from nginx's address
 * (`server.tomcat.remoteip.internal-proxies`), so a header nginx passes through unset is one the caller
 * writes and the API believes. Nothing runs this file except `npm run test:images`, so these read it.
 */
describe('the nginx configuration', () => {
  it.each(PROXIED)('GivenTheProxiedLocation%s_WhenItsForwardedHeadersAreRead_ThenNginxSetsEachItself', (path) => {
    // S5.B9: Host and Port were passed through as the caller sent them.
    const body = locationBody(path);

    expect({
      For: proxiedHeader(body, 'X-Forwarded-For'),
      Proto: proxiedHeader(body, 'X-Forwarded-Proto'),
      Host: proxiedHeader(body, 'X-Forwarded-Host'),
      Port: proxiedHeader(body, 'X-Forwarded-Port'),
    }).toEqual({ For: '$remote_addr', Proto: '$scheme', Host: '$host', Port: '$server_port' });
  });
});
