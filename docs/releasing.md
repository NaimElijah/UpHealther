# Releasing

Use this when you are cutting a release of UpHealther: a version PR into `dev`, the promotion from
`dev` into `main`, and the tag that names it. It is the maintainer's procedure, and nothing in it is
needed to run or read the code.

One version names both modules, and a release is an annotated `v<version>` tag on the promotion merge
that ships it, with its section in [`CHANGELOG.md`](../CHANGELOG.md)
([ADR-050](ADRs/ADR-050-one-version-names-the-whole-repository.md),
[ADR-051](ADRs/ADR-051-a-release-is-a-tag-on-its-promotion-and-git-cliff-writes-its-changelog.md)).

## Before you start

- Docker running, because step 3 writes the changelog through git-cliff's image.
- Push access to `origin`, for the branch, the pull requests and the tag.
- Node and JDK 21, for `npm version` and for the Maven wrapper in step 2.
  [Development](../README.md#development) lists what a native setup needs.
- The tags fetched, which step 1 does.

Each line below runs the same from PowerShell, Git Bash or a Linux shell. Run them one at a time,
because PowerShell 5.1 has no `&&`.

## Steps

1. Fetch the tags, then branch from `dev`. Without the tags, the changelog takes the whole history as
   one release.

   ```bash
   git fetch origin --tags
   git switch --no-track -c chore/release-X.Y.Z origin/dev
   ```

2. Set the version in both modules, the frontend's from `frontend/` and the pom's from the root:

   ```bash
   cd frontend
   npm version X.Y.Z --no-git-tag-version
   cd ..
   node scripts/mvnw.mjs versions:set "-DnewVersion=X.Y.Z" -DgenerateBackupPoms=false
   ```

3. Write the release's section of the changelog. It needs Docker, and it prepends, so run it once:

   ```bash
   npm run changelog -- --tag vX.Y.Z
   ```

4. Commit all of it as `chore(release): X.Y.Z`, and merge this *version PR* into `dev`. Nothing else
   merges into `dev` until the promotion has, because whatever does would ship with no line in the
   changelog.
5. Promote: the release PR from `dev` into `main`, labelled `release`, merged as a merge commit.
6. Tag the promotion merge, and push the tag:

   ```bash
   git fetch origin
   git tag -a vX.Y.Z origin/main -m "UpHealther X.Y.Z"
   git push origin vX.Y.Z
   ```

## What checks it

`RepositoryVersionContractTest` fails the version PR in three cases:

- a version field was not set;
- the changelog's newest section is not the new version;
- a release or a commit appears in the changelog twice.

## How you know it worked

Once the tag is pushed, `git describe --exact-match origin/main` names it. Pushing a tag starts no
workflow yet.
