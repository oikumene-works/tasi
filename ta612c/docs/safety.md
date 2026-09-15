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

Its actual TA612C applicability has not been physically confirmed, and static analysis did not find the TA612C interface normally invoking it. Nonetheless:

**Do not send this command as a harmless discovery probe.**

If an erase test is ever performed, it should be an explicit experiment with intentionally disposable REC data and a documented expected outcome.

## Vendor binaries

EnvironmentalTester executables and packages are research inputs but are not redistributed in this repository. Store provenance, version and hashes instead.
