# Safety and data-preservation rules

The public tooling should default to preserving device data and raw research evidence.

## Safe core used by the current logger

```text
AA 55 00 03 02   device info
AA 55 01 03 03   live data
AA 55 02 03 04   REC download
```

The current logger does not erase meter memory.

## Potentially destructive command

EnvironmentalTester 1.22 labels this common serial-layer TX command:

```text
AA 55 04 03 06
```

as:

```text
DATA_ERASE
```

Static analysis did not find the TA612C interface normally invoking it. In one
explicitly authorized physical trial on 2026-09-15, the bare command returned no
bytes and an immediate REC readback was byte-for-byte unchanged at nine sample
groups. That bounded no-effect observation does not establish that the command
is harmless, unsupported on other firmware or incapable of acting in another
device state.

**Do not send this command as a harmless discovery probe.**

Do not repeat the trial or search for a working erase sequence without a new,
explicit experiment using intentionally disposable REC data. See the
[trial record](../research/data-erase-trial-2026-09-15.md).

## Vendor binaries

EnvironmentalTester executables and packages are research inputs but are not redistributed in this repository. Store provenance, version and hashes instead.
