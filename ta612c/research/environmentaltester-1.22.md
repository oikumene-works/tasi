# EnvironmentalTester 1.22 — static analysis notes

## Identification

Analyzed executable:

```text
SHA-256  ac9a9d3415b9de12958b794f49e1d9e351a38979eef4b368ce4975a2fb123bad
size     2783744 bytes
```

The obtained package contains an updated `config.ini` with:

```ini
[Debug]
EnableCommunicationLog=true
```

## Command names exposed by the newer build

The common serial layer includes the strings:

```text
[TX][START]
[TX][PAUSE]
[TX][UPLOAD]
[TX][SYNC]
[TX][DATA_ERASE]
```

These correspond to the same command-selection cases 0..4 seen in 1.21. Of special importance, case 4 builds:

```text
AA 55 04 03 06
```

and the 1.22 build labels that TX path `DATA_ERASE`.

### Safety interpretation

This does **not** prove that `0x04` is a supported TA612C erase command. Static analysis did not find the TA612C interface invoking this command, and older protocol documentation associates command `0x04` function-setting use with other model context. However, the vendor software's `DATA_ERASE` label is sufficient reason to treat the command as potentially destructive and not probe it casually.

## TA612C-specific changes

The clearest TA612C-specific addition compared with 1.21 is automatic serial connection support, including strings such as:

```text
TA612_INTERFACE: Auto-connect enabled for port:
Auto-jumping to TA612 interface
Deleting existing TA612 interface before creating new one
```

No new TA612C alarm-mute or acknowledge command was found in the static analysis.

## Communication logging

The 1.22 build contains explicit TX/RX logging support. This creates a useful future dynamic-test path: change an EnvironmentalTester setting and inspect whether any new serial TX frame is emitted, without relying only on static analysis.
