#!/usr/bin/env node
/**
 * Builds both container images, starts the whole stack from them, waits until every service reports
 * healthy, and then drives it with the browser suite in `e2e/`. Run it as `npm run test:images` from the
 * repository root. It needs a running Docker daemon, a Compose of 2.24.4 or later (`compose.images-check.yml`
 * uses `!reset` and `!override`), and `npm run setup:e2e`.
 *
 * It first removes whatever an interrupted earlier run of its own left behind, and publishes
 * 127.0.0.1:3000 while it runs. Whatever the build, the start or the suite did, it then takes the stack
 * down again, volume and built images included. An interrupt (Ctrl+C) ends the script before that and
 * leaves the containers behind; the next run removes them first. A failing suite leaves its trace and
 * screenshots in `e2e/test-results/`.
 *
 * Exits 0 only when the suite passes and the take-down succeeds. Exits non-zero when an image does not
 * build, when a service is not healthy within `WAIT_TIMEOUT_SECONDS`, when the suite fails, or when the
 * take-down fails. It prints the stack's logs when the start or the suite fails. A failed precondition
 * (Playwright or its Chromium missing, another stack's containers holding the fixed names, something
 * already answering on a published address) is an uncaught `Error`, so Node prints its stack trace and
 * exits 1.
 *
 * Two bare `docker build`s would only show that each Dockerfile runs. Starting the stack shows what a
 * deployment needs:
 * - the backend's readiness probe passes after Flyway has migrated an empty database;
 * - nginx has loaded `nginx.conf`.
 * See `docs/ADRs/ADR-045-ci-builds-and-starts-both-images.md`. Driving it shows that the frontend and
 * the API agree, which neither module's own suite can (#167,
 * `docs/ADRs/ADR-052-a-playwright-smoke-suite-drives-the-stack-the-image-check-starts.md`).
 *
 * It runs as a compose project of its own, so taking it down never touches a developer's stack or its
 * `postgres_data` volume. `compose.images-check.yml` publishes one host port, the frontend's on the
 * loopback address, for the browser; the check refuses to start while something already answers there.
 * `docker-compose.yml` fixes the container names and the network's subnet, though. Both are global
 * across projects, so the check cannot run beside the developer's own stack. It refuses to start while
 * containers hold those names, which is how a developer's stack shows even when stopped. It does not
 * look for the subnet: a network that already holds it with no such containers fails the check later,
 * when compose creates its own, with Docker's "Pool overlaps" error.
 */
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { connect } from 'node:net';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const E2E = fileURLToPath(new URL('../e2e/', import.meta.url));

/**
 * The compose arguments every call shares. The project has a name of its own, so `down` removes only this
 * check's resources and none of a developer's stack (ADR-045). The file paths are relative to `ROOT`, the
 * working directory every `docker` call here uses.
 */
const COMPOSE = [
  'compose',
  '--project-name',
  'uphealther-images-check',
  '--file',
  'docker-compose.yml',
  '--file',
  'scripts/compose.images-check.yml',
];

/**
 * How long `up` may wait for every service to report healthy. The backend's start period alone is 60 s,
 * and the frontend starts only once the backend is ready.
 */
const WAIT_TIMEOUT_SECONDS = 240;

/**
 * Runs `docker` with its output shown, and returns its exit status. A child killed by a signal counts as
 * status 1. Throws when `docker` cannot be started.
 */
function docker(args) {
  const result = spawnSync('docker', args, { cwd: ROOT, stdio: 'inherit' });
  if (result.error) {
    throw result.error;
  }
  return result.status ?? 1;
}

/** Removes this project's containers, volume, network and built images, whatever state they are in. */
function takeDown() {
  return docker([...COMPOSE, 'down', '--volumes', '--rmi', 'local']);
}

/**
 * Runs `docker` and returns its standard output, which is not echoed. Throws an `Error` carrying the
 * standard error when `docker` exits non-zero, and the spawn error when it cannot be started.
 */
function dockerOutput(args) {
  const result = spawnSync('docker', args, { cwd: ROOT, encoding: 'utf8' });
  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    throw new Error(`docker ${args.join(' ')} failed: ${result.stderr.trim()}`);
  }
  return result.stdout;
}

/**
 * The browser suite's command-line entry point, once the Chromium it drives has launched and closed.
 * Both are checked before any image is built. Otherwise a clone that never ran `npm run setup:e2e`, or
 * ran a bare `npm ci` in `e2e/` after a Playwright bump that moved the browser, learns so only once
 * the stack is up. The package being installed is not enough: the browser lives outside it, in
 * Playwright's cache, and the suite runs the headless shell, whose path no public API gives, so it is
 * launched rather than looked for.
 *
 * @returns the path of Playwright's command-line entry point
 * @throws {Error} naming `npm run setup:e2e`, with the cause attached, when the package cannot be resolved
 *   from `e2e/` or Chromium does not launch
 */
async function preparedPlaywrightCli() {
  const fromE2e = createRequire(new URL('../e2e/package.json', import.meta.url));
  let cli;
  let chromium;
  try {
    cli = fromE2e.resolve('@playwright/test/cli');
    ({ chromium } = fromE2e('@playwright/test'));
  } catch (cause) {
    throw new Error('the browser suite in e2e/ is not installed. Run `npm run setup:e2e` first.', { cause });
  }
  try {
    const browser = await chromium.launch();
    await browser.close();
  } catch (cause) {
    throw new Error(
      "Playwright's Chromium did not launch. If it is missing, run `npm run setup:e2e`; the cause follows.",
      { cause },
    );
  }
  return cli;
}

/** The configuration compose merges from both files, as the services it would run. */
function composeServices() {
  return JSON.parse(dockerOutput([...COMPOSE, 'config', '--format', 'json'])).services;
}

/**
 * The `container_name`s the merged configuration sets, read from it rather than copied, so a rename in
 * `docker-compose.yml` cannot leave the refusal below checking stale names.
 */
function fixedContainerNames(services) {
  return Object.values(services)
    .map((service) => service.container_name)
    .filter((name) => name !== undefined);
}

/**
 * Fails when containers of another project hold the fixed names. That is almost always the developer's
 * own stack, stopped but not removed. Run after `takeDown`, so a check interrupted earlier is not
 * mistaken for it.
 */
function refuseBesideAnotherStack(services) {
  const fixed = fixedContainerNames(services);
  const existing = dockerOutput(['ps', '--all', '--format', '{{.Names}}']).split(/\r?\n/);
  const clashing = existing.filter((name) => fixed.includes(name));
  if (clashing.length > 0) {
    throw new Error(
      `containers named ${clashing.join(', ')} already exist, and the check needs those names and their network. ` +
        'Run `docker compose down` first; your data volume is kept.',
    );
  }
}

/**
 * The host addresses the merged configuration publishes, read from it rather than copied. A port bound
 * to every address is probed on the loopback one, where anything holding it would also answer.
 */
function publishedAddresses(services) {
  return Object.values(services)
    .flatMap((service) => service.ports ?? [])
    .map((port) => ({ host: port.host_ip || '127.0.0.1', port: Number(port.published) }));
}

/**
 * Whether something accepts a connection at the address. A refusal means the port is free. Any other
 * connection error is thrown, not read as "free".
 */
function isAnswering({ host, port }) {
  return new Promise((resolve, reject) => {
    const socket = connect({ host, port });
    socket.once('connect', () => {
      socket.destroy();
      resolve(true);
    });
    socket.once('error', (error) => {
      if (error.code === 'ECONNREFUSED') {
        resolve(false);
      } else {
        reject(error);
      }
    });
  });
}

/**
 * Fails when a port the check publishes is already taken, before minutes of building end in Docker's
 * bind error. Run after `takeDown`, so a check interrupted earlier is not mistaken for it.
 */
async function refuseWhenAPublishedPortIsTaken(services) {
  for (const address of publishedAddresses(services)) {
    if (await isAnswering(address)) {
      throw new Error(
        `something already answers on ${address.host}:${address.port}, which the check publishes for the browser suite. ` +
          'It is usually `npm run dev`; stop it first.',
      );
    }
  }
}

/**
 * Builds the images and starts the stack, waiting until every service is healthy or `WAIT_TIMEOUT_SECONDS`
 * passes. Returns the first non-zero Docker status, or 0. Prints the stack's logs when the start fails, not
 * when the build does, since a failed build has started nothing.
 */
function buildAndStart() {
  const built = docker([...COMPOSE, 'build']);
  if (built !== 0) {
    return built;
  }
  const started = docker([...COMPOSE, 'up', '--detach', '--wait', '--wait-timeout', String(WAIT_TIMEOUT_SECONDS)]);
  if (started !== 0) {
    docker([...COMPOSE, 'logs', '--no-color']);
  }
  return started;
}

/**
 * Runs the browser suite in `e2e/` against the started stack and returns its exit status, printing the
 * stack's logs when it fails. Node runs Playwright's entry point itself, so no shell parses anything, on
 * Windows or anywhere else, as in `python.mjs`. `mvnw.mjs` cannot, because it has to go through `cmd.exe`
 * to run a `.cmd` file.
 */
function driveWithABrowser(cli) {
  const result = spawnSync(process.execPath, [cli, 'test'], { cwd: E2E, stdio: 'inherit' });
  if (result.error) {
    throw result.error;
  }
  const driven = result.status ?? 1;
  if (driven !== 0) {
    docker([...COMPOSE, 'logs', '--no-color']);
  }
  return driven;
}

function buildStartAndDrive(cli) {
  const started = buildAndStart();
  return started === 0 ? driveWithABrowser(cli) : started;
}

const cli = await preparedPlaywrightCli();

// The check relies on `!reset` and `!override` in the override, and CI's runner image changes under
// it, so every run records which Compose it ran on.
const versioned = docker(['compose', 'version']);
if (versioned !== 0) {
  process.exit(versioned);
}

// Clears what an interrupted earlier run of this check left, so those containers are not mistaken for
// another stack's by the refusals below (ADR-045).
const cleared = takeDown();
if (cleared !== 0) {
  process.exit(cleared);
}
const services = composeServices();
refuseBesideAnotherStack(services);
await refuseWhenAPublishedPortIsTaken(services);

let status = 1;
try {
  status = buildStartAndDrive(cli);
} finally {
  // The first failure is the one worth reporting. A failed take-down only decides the result when
  // nothing failed before it.
  const takenDown = takeDown();
  if (status === 0) {
    status = takenDown;
  }
}
process.exit(status);
