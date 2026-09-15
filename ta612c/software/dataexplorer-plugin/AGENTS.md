# TA612C project instructions

## Communication and scope

- Communicate with the user in Finnish. Keep source code, tests and repository
  documentation in English.
- This is a standalone DataExplorer 4.0.7 device plugin. Avoid upstream core or
  unrelated plugin changes unless the user explicitly expands the scope.
- Use JDK 21 with the Java 19 compile target and Ant.

## Protocol safety

- Live may send only `AA 55 01 03 03` within the accepted implementation.
- REC may send only the read command `AA 55 02 03 04` within the accepted
  implementation.
- Do not add erase, recording-start, settings-write, automatic retry or other
  meter commands based on guesswork.
- Preserve exclusive live/REC transport ownership and bounded cleanup.

## Data integrity and claims

- A failed, malformed or cancelled import must not publish partial data.
- REC completion is unverified: 1.5 seconds without bytes is a receive endpoint,
  not proof that the entire meter memory was downloaded.
- REC interval and timestamps are inferred. Preserve explicit user input, epoch
  zero as an unknown-time placeholder, and the existing provenance language.
- Open probes remain empty and hidden. Never substitute zero, raw 28000 or a
  previous measurement.
- Preserve old live OSD and sparse REC/live OSD compatibility.

## Working tree hygiene

- Run `scripts/test.sh protocol` for protocol-only changes and
  `scripts/test.sh all` before accepting integration changes.
- Preserve `test/fixtures/`; they are required regression inputs.
- Keep downloaded DataExplorer sources, Xvfb, generated JARs, logs and physical
  meter recordings under ignored `local/` paths or outside the repository.
- Do not commit secrets, credentials, personal profiles or machine-specific
  absolute paths.
