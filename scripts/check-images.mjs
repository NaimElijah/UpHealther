#!/usr/bin/env node
/**
 * Builds both container images, starts the whole stack from them, and waits until every service
 * reports healthy. The stack is always taken down again. Exits non-zero when an image does not build or
 * a service never becomes healthy, after printing the stack's logs.
 *
 * Two bare `docker build`s would only show that each Dockerfile runs. Starting the stack shows what a
 * deployment needs:
 * - the backend's readiness probe passes after Flyway has migrated an empty database;
 * - nginx has loaded `nginx.conf`.
 * See `docs/ADRs/ADR-045-ci-builds-and-starts-both-images.md`.
 *
 * It runs as a compose project of its own, so taking it down never touches a developer's stack or its
 * `postgres_data` volume. `compose.images-check.yml` publishes no host ports, so none has to be free.
 * `docker-compose.yml` fixes the container names and the network's subnet, though. Both are global
 * across projects, so the check cannot run beside the developer's own stack, and it refuses to start
 * rather than fail halfway.
 */
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));

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

/** The `container_name`s in `docker-compose.yml`. */
const FIXED_CONTAINER_NAMES = ['healthupgrades-db', 'healthupgrades-backend', 'healthupgrades-frontend'];

/** Runs `docker` with its output shown, and returns its exit status. */
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
 * Fails when containers of another project hold the fixed names. That is almost always the developer's
 * own stack, stopped but not removed. Run after `takeDown`, so a check interrupted earlier is not
 * mistaken for it.
 */
function refuseBesideAnotherStack() {
  const listed = spawnSync('docker', ['ps', '--all', '--format', '{{.Names}}'], { cwd: ROOT, encoding: 'utf8' });
  if (listed.error) {
    throw listed.error;
  }
  if (listed.status !== 0) {
    throw new Error(`docker ps failed: ${listed.stderr.trim()}`);
  }
  const clashing = listed.stdout.split(/\r?\n/).filter((name) => FIXED_CONTAINER_NAMES.includes(name));
  if (clashing.length > 0) {
    throw new Error(
      `containers named ${clashing.join(', ')} already exist, and the check needs those names and their network. ` +
        'Run `docker compose down` first; your data volume is kept.',
    );
  }
}

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

const cleared = takeDown();
if (cleared !== 0) {
  process.exit(cleared);
}
refuseBesideAnotherStack();

let status = 1;
try {
  status = buildAndStart();
} finally {
  // The first failure is the one worth reporting. A failed take-down only decides the result when
  // nothing failed before it.
  const takenDown = takeDown();
  if (status === 0) {
    status = takenDown;
  }
}
process.exit(status);
