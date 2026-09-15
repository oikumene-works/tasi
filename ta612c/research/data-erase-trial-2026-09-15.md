# TA612C DATA_ERASE candidate trial — 2026-09-15

## Purpose and authorization

EnvironmentalTester 1.22 labels the common serial-layer command
`AA 55 04 03 06` as `DATA_ERASE`, but static analysis did not show the TA612C
UI invoking it. A single controlled physical trial was performed after the user
explicitly confirmed that REC was stopped and the nine stored sample groups
could be permanently removed.

The experiment used the previously identified TA612C with firmware 3.50 through
its CH340 serial adapter at 9600 baud, 8N1. The command is not present in the
public logger or DataExplorer plugin.

## Procedure

1. Confirm that no process owns the serial port.
2. Send the read-only REC command `AA 55 02 03 04` once and preserve the exact
   response before the erase trial.
3. Validate every REC frame boundary, command byte, payload length and checksum.
4. Send `AA 55 04 03 06` exactly once, with no automatic retry.
5. Wait two seconds for a response, close the port, then send one new read-only
   REC request and preserve its exact response.
6. Compare the complete pre- and post-command REC byte streams.

Raw transcripts and binaries remain in ignored local experiment storage. They
are not published with this note; the hashes below identify them.

## Observations

### Before the candidate command

- two valid REC frames: 61 and 21 bytes;
- seven and two sample groups, nine total;
- 82 response bytes;
- complete response SHA-256:
  `aaef715625c41298ddbbb5f3e27e914cc8d266531a6a55a04f3a324f7be62422`.

### Candidate command

- transmitted bytes: `AA 55 04 03 06`;
- send count: exactly one;
- automatic retry: none;
- response during the two-second observation window: zero bytes;
- empty response-file SHA-256:
  `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.

### Immediate readback

- two valid REC frames: 61 and 21 bytes;
- seven and two sample groups, nine total;
- 82 response bytes;
- complete response SHA-256:
  `aaef715625c41298ddbbb5f3e27e914cc8d266531a6a55a04f3a324f7be62422`;
- the pre- and post-command response files compare byte-for-byte identical.

Both REC reads ended after 1.5 seconds without bytes. As elsewhere in this
project, that is a receive endpoint and not proof of memory completeness.

## Interpretation

The candidate command had no observable REC-erasure effect on this TA612C in
this state: the immediate readback returned exactly the same stored bytes. This
is positive evidence that the bare common-layer command is not a functioning
TA612C REC erase operation under the tested conditions.

It does **not** prove that the command is harmless, unsupported on every
firmware, or incapable of affecting another device state. It might belong to a
different model family or require an unknown precondition. Absence of a command
response is also not an acknowledgement.

Keep treating `0x04` as potentially destructive. Do not add it to normal tools,
do not retry it automatically, and do not search for a working erase sequence
by guessing adjacent commands or payloads.
