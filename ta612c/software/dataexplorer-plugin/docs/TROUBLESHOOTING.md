# Development troubleshooting and integration lessons

This document records reproducible development and repository-integration
problems that are easy to rediscover. Protocol facts and product limitations
belong in the main `README.md`, `VALIDATION.md`, `AGENTS.md` and the repository's
`ta612c/research/` material; this file links to those sources rather than
duplicating their full history.

## Build and test environment

### Ant stops with "Use JDK 19 or newer"

- **Symptom:** the build fails in the `init` target before compilation.
- **Cause:** the shell's default `java` and `javac` can still be JDK 17 even
  when JDK 21 is installed.
- **Resolution:** set `JAVA_HOME` to JDK 21 and place its `bin` directory first
  in `PATH`. The project compiles with the Java 19 release target.
- **Verification:** `java -version`, `javac -version`, then
  `scripts/test.sh all`.

### SWT reports `gtk_init_check() failed`

- **Symptom:** protocol tests pass, but the integration test fails while SWT
  creates its first `Display`.
- **Cause:** the full suite needs a usable display. In a sandbox, Xvfb may also
  need permission to create its local display socket.
- **Resolution:** run `scripts/test.sh all` through the detected Xvfb wrapper
  and allow the isolated display socket when the execution environment asks.
- **Verification:** the suite reaches both integration assertion summaries,
  not merely the protocol target.

### Setting `XVFB_RUN_BIN` is not sufficient for a private Xvfb bundle

- **Symptom:** `XVFB_RUN_BIN=/path/to/xvfb-run` is set, but SWT still reports
  that no display is available.
- **Cause:** an explicit `XVFB_RUN_BIN` selects the wrapper while preserving
  the existing `PATH`. The wrapper must still find its companion `Xvfb`
  executable.
- **Resolution:** also prepend the bundle's binary directory to `PATH`, or
  place the bundle at `local/tools/xvfb/usr/bin`, where `scripts/test.sh`
  detects it and adjusts `PATH` automatically.
- **Verification:** `scripts/test.sh all` passes 57,639 assertions for the
  accepted baseline.

### DataExplorer schema or core JAR is not found after monorepo import

- **Symptom:** the schema check fails, or Ant asks for a matching core JAR.
- **Cause:** the plugin's project root moved below
  `ta612c/software/dataexplorer-plugin`; a dependency tree beside the old
  standalone checkout is not automatically beside the imported module.
- **Resolution:** put DataExplorer 4.0.7 under the module's ignored
  `local/deps/dataexplorer-4.0.7`, or set `DATAEXPLORER_ROOT`. If necessary,
  let the helper build a module-local core JAR.
- **Verification:** the schema resolves, the nine plugin sources compile, and
  the full suite succeeds from the imported directory.

## Repository integration

### GitHub `main` contained the correct files but not their detailed history

- **Symptom:** commit `044b465` had the same tree as `seed-ta612c`, but all
  research and logger files appeared in one squashed commit.
- **Cause:** the public seed was created as a single GitHub commit while the
  original 20-step history remained on `seed-ta612c`.
- **Resolution:** merge `seed-ta612c` as a second parent before importing the
  plugin. Merge commit `2a4a111` connects the detailed history without changing
  the file tree.
- **Verification:** both `044b465` and `af85049` are ancestors of the
  integration branch, and the logger, protocol and research tree object IDs
  still match the public seed.

### `git subtree add` reports that the prefix already exists

- **Symptom:** importing to `ta612c/software/dataexplorer-plugin` fails with
  `fatal: prefix ... already exists`.
- **Cause:** the seed intentionally reserved that path with a README. After
  moving the tracked file, an empty working-tree directory can also keep the
  prefix present.
- **Resolution:** first rename and commit the placeholder outside any path
  beginning with the subtree prefix. Remove only the now-empty directory with
  `rmdir`, then run `git subtree add` without `--squash`. The placeholder is
  retained as `IMPORT_PROVENANCE.md`.
- **Verification:** subtree commit `5684d3b` has standalone tip `7323738` as
  its second parent, and the imported subtree initially equals the standalone
  tree `8d5372a6`.

### A subtree import does not preserve the annotated tag reference by itself

- **Symptom:** the accepted commit is reachable, but
  `partial-probes-accepted-2026-09-15` is absent from the destination refs.
- **Cause:** importing a branch does not imply fetching or publishing its tags.
- **Resolution:** fetch the annotated tag explicitly and publish it alongside
  the integration branch. Do not recreate it as a different lightweight tag.
- **Verification:** tag object `a65b7a43` peels to accepted commit `6981734`.

### Physical measurement fixtures require a deliberate publication decision

- **Symptom:** a public push contains two OSD and four CSV regression fixtures
  derived from user measurements.
- **Cause:** these small files are intentional test inputs rather than ignored
  local captures.
- **Resolution:** inspect them for credentials, personal paths and unintended
  content, preserve their checksums and provenance, and obtain explicit user
  approval before the first public push. That approval was obtained for the
  2026-09-15 integration branch.
- **Verification:** the fixture inventory matches `test/fixtures/README.md` and
  the public branch contains no ignored `local/` evidence or build output.

### The repository and plugin have different licensing scopes

- **Symptom:** the repository root states that no repository-wide license has
  been selected, while the imported plugin contains `COPYING`.
- **Cause:** the plugin is GPL-3.0-or-later, but the surrounding research and
  tooling were not assigned a single repository-wide license.
- **Resolution:** keep the plugin license and attribution inside its directory
  and describe that limited scope in the root README. Do not imply that the
  nested GPL file automatically licenses every repository path.

## Known follow-ups

- At the 2026-09-15 review, the remote branch named
  `fix/logger-final-newline` pointed to the same commit `044b465` as `main`;
  the logger blob still lacked a final newline. This is harmless at runtime but
  the branch name must not be treated as proof of a fix.
- Live START/request cadence remains an evidence question. See
  `PROTOCOL_RESEARCH_REVIEW.md`; do not increase command frequency based only on
  source wording.
- A physical below-zero capture is still wanted to complement the signed-value
  synthetic tests.
- RTS and DTR worked in the accepted hardware trials, but their necessity was
  not isolated.

## Acceptance checks after integration changes

From this module directory, use JDK 21 and run:

```sh
scripts/test.sh protocol
scripts/test.sh all
git diff --check
git status --short --ignored
```

For repository-history work, also confirm that the standalone tip, accepted
baseline and detailed seed remain ancestors, and compare the logger, protocol
and research subtree object IDs against the intended source commit.
