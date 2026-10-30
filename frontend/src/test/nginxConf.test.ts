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

  /**
   * Every header a caller could continue a trace with. Boot's `management.tracing.propagation.consume`
   * defaults to W3C, B3 and B3 multi-header, so each format's headers are listed.
   */
  const TRACE_HEADERS = [
    'traceparent',
    'tracestate',
    'baggage',
    'b3',
    'X-B3-TraceId',
    'X-B3-SpanId',
    'X-B3-ParentSpanId',
    'X-B3-Sampled',
    'X-B3-Flags',
  ];

  it.each(PROXIED)('GivenTheProxiedLocation%s_WhenItsTraceHeadersAreRead_ThenTheCallersAreCleared', (path) => {
    // S5.C5: the backend continues an inbound trace, so a caller who sent one chose the trace id the
    // logs and the audit trail join on. An empty value makes nginx send none.
    const body = locationBody(path);

    expect(Object.fromEntries(TRACE_HEADERS.map((name) => [name, proxiedHeader(body, name)]))).toEqual(
      Object.fromEntries(TRACE_HEADERS.map((name) => [name, '""'])),
    );
  });

  describe('the socket endpoint', () => {
    /** One tab's socket, reconnecting every five seconds while it is down (`api/notificationSocket.ts`). */
    const HANDSHAKES_A_MINUTE_FOR_ONE_TAB = 60_000 / 5_000;

    /** The zone a top-level `limit_req_zone` or `limit_conn_zone` declares, with its key and its parameters. */
    function zone(directive: 'limit_req_zone' | 'limit_conn_zone', name: string) {
      const match = nginxConf.match(new RegExp(`^\\s*${directive}\\s+(\\S+)\\s+zone=${name}:\\S+([^;]*);`, 'm'));
      return match ? { key: match[1], parameters: match[2] } : undefined;
    }

    it('GivenTheSocketLocation_WhenItsLimitsAreRead_ThenHandshakesAndOpenSocketsAreCappedPerAddress', () => {
      // S5.C4: an anonymous client could open sockets, and have each refused frame logged at ERROR, as
      // fast as it liked.
      const body = locationBody('/ws');
      const requestZone = body.match(/limit_req\s+zone=(\S+)/)?.[1];
      const connectionZone = body.match(/limit_conn\s+(\S+)\s+\d+\s*;/)?.[1];

      expect(requestZone && zone('limit_req_zone', requestZone)?.key).toBe('$binary_remote_addr');
      expect(connectionZone && zone('limit_conn_zone', connectionZone)?.key).toBe('$binary_remote_addr');
    });

    it('GivenTheSocketLocation_WhenItsHandshakeRateIsRead_ThenATabReconnectingEveryFiveSecondsIsUnderIt', () => {
      const requestZone = locationBody('/ws').match(/limit_req\s+zone=(\S+)/)?.[1] ?? '';
      const rate = zone('limit_req_zone', requestZone)?.parameters.match(/rate=(\d+)r\/(s|m)/);
      if (!rate) throw new Error('The socket location applies no limit_req zone with a rate.');
      const perMinute = Number(rate[1]) * (rate[2] === 's' ? 60 : 1);

      expect(perMinute).toBeGreaterThanOrEqual(HANDSHAKES_A_MINUTE_FOR_ONE_TAB);
    });
  });

  describe("NFR-45's headers", () => {
    /** The five headers NFR-45 names. */
    const HARDENING = [
      'Content-Security-Policy',
      'X-Content-Type-Options',
      'X-Frame-Options',
      'Referrer-Policy',
      'Permissions-Policy',
    ];

    it('GivenTheServerBlock_WhenItsOwnHeadersAreRead_ThenItDeclaresEveryHardeningHeader', () => {
      // S5.C12: declared on `location /` alone, they were missing from nginx's own error pages under /api
      // and /ws, a 502 while the backend restarts or a 413 for an oversized body.
      const outsideLocations = nginxConf.replace(/location\s+\S+\s*\{[^}]*\}/g, '');
      const declared = [...outsideLocations.matchAll(/add_header\s+(\S+)\s/g)].map(([, name]) => name);

      expect(declared).toEqual(expect.arrayContaining(HARDENING));
    });

    it.each(['/', ...PROXIED])('GivenTheLocation%s_WhenItsHeadersAreRead_ThenItDeclaresNoneOfItsOwn', (path) => {
      // nginx inherits the server's add_header only into a location that declares none: one add_header
      // in a location would silently drop all five from it.
      expect(locationBody(path)).not.toMatch(/add_header/);
    });
  });
});
