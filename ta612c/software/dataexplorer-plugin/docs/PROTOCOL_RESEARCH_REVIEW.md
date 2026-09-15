# Cross-check against vendor-software and Artisan research

Reviewed 2026-09-15 against the repository's EnvironmentalTester 1.21/1.22,
Artisan and consolidated TA612C research notes. This review records what the new
evidence changes for the DataExplorer plugin and, equally importantly, what it
does not justify changing.

Primary repository references for this review are the
[consolidated research handoff](../../../research/TA612C_PROTOCOL_RESEARCH_HANDOFF_2026-09-15.md),
[EnvironmentalTester 1.21](../../../research/environmentaltester-1.21.md),
[EnvironmentalTester 1.22](../../../research/environmentaltester-1.22.md),
[Artisan comparison](../../../research/artisan.md) and the
[Python logger notes](../../logger/README.md).

## Result

No immediate protocol-code change is justified. The plugin already agrees with
the strongest shared evidence on framing, serial format, temperature encoding,
REC uncertainty and destructive-command avoidance. The research adds useful
constraints and identifies a live-command cadence question that needs a wire
capture rather than a speculative implementation change.

## Command cross-check

| Command or finding | Plugin status | Decision |
|---|---|---|
| `AA 55 00 03 02` device info / vendor common-layer PAUSE path | Not sent | Keep omitted. Although physically observed as read-only, it is not needed for acquisition, the model-identity contract is not established, and closing the port already stops the tested device. |
| `AA 55 01 03 03` live data / START | The only live command | Correct command. Preserve the accepted bounded behavior until cadence is resolved. |
| `AA 55 02 03 04` REC upload | The only REC command | Matches physical, vendor-software and logger evidence. Keep the transfer read-only and single-shot. |
| `AA 55 03 07 ...` clock sync | Not sent | EnvironmentalTester indicates a 32-bit Unix timestamp rather than BCD, but it writes device state and does not establish REC sample timestamps. Do not add it. |
| `AA 55 04 03 06` | Not present | EnvironmentalTester 1.22 labels it `DATA_ERASE`. A single authorized physical send returned no bytes and left immediate REC readback unchanged; that does not establish safety. Keep it out of the plugin. |

The accepted plugin boundary remains deliberately narrower than the set of
commands found in the vendor software: live may send only `0x01`, and REC may
send only `0x02`.

## Live cadence remains open

The current plugin sends one START command, consumes the resulting live frames,
and resends the same command only after 1.5 seconds without a valid frame. It
stops after three bounded restart attempts. Physical trials on firmware 3.50
produced multiple updating rows and graphs with this behavior.

Other evidence is not yet conclusive:

- EnvironmentalTester 1.22 calls the command `START`, which is compatible with
  a continuing stream.
- Vendor-protocol wording and the current Artisan usage have been interpreted
  as one request followed by one response.
- The Python logger also notes explicit request/response polling as a possible
  cleanup, but this is not an independent meter observation.

Changing the plugin to transmit for every sample would materially increase the
wire-command rate and replace a physically accepted behavior with an inference.
Before such a change, capture TX and RX traffic from current EnvironmentalTester
and Artisan sessions, record whether each response is preceded by a request,
and repeat the DataExplorer hardware trial. Do not infer cadence from UI timer
settings: EnvironmentalTester's interval changes a PC-side polling timer, not
the meter's REC interval.

## Framing and values

- All sources agree on 9600 baud, 8N1, response header `55 AA`, length-derived
  response size and an additive modulo-256 checksum.
- The plugin decodes each channel as signed little-endian 16-bit and scales from
  tenths to DataExplorer thousandths. This supports negative temperatures and
  is stronger than Artisan's historical unsigned interpretation. Retain it;
  add a physical below-zero fixture when available.
- The physically observed open-probe value is 2800.0 degrees Celsius, raw
  `28000`. The plugin deliberately recognizes that exact raw sentinel. The
  Python logger's broader `abs(value) >= 2000` rule is diagnostic, not evidence
  that every such value is the wire sentinel; do not copy the threshold into
  stored plugin data handling.
- The live decoder may resynchronize after corrupt bytes because later live
  samples have their own receive-time identity. The REC decoder intentionally
  does not resynchronize past corruption because a lost stored sample would
  silently shorten the inferred timeline.

## REC interpretation

The research reinforces the current plugin design:

- no per-sample timestamp, recording start time, total sample count, sequence
  number, session marker or proven transfer-end marker has been observed;
- the interval field in EnvironmentalTester controls PC live polling and is not
  evidence of the meter's internal REC interval;
- the plugin therefore requires explicit interval input, uses sample index for
  inferred relative time, keeps epoch zero as an unknown-time placeholder and
  does not infer recording sessions;
- 1.5 seconds without bytes is only a receive endpoint, never proof that all
  memory was downloaded; and
- the plugin's all-or-nothing import is intentionally stricter than the Python
  logger's forensic CSV behavior. A corrupt or cancelled transfer must publish
  no partial DataExplorer dataset.

## UI and connection findings

- EnvironmentalTester's alarm thresholds and WAV playback are PC-side. They do
  not justify adding meter alarm configuration or acknowledgement commands.
- EnvironmentalTester 1.22 adds TA612C auto-connect behavior. DataExplorer uses
  explicit port selection; the plugin's constructor already registers a closed
  serial backend so ports appear before Start. Automatic port selection would
  be a separate UX feature, not a protocol correction.
- RTS and DTR were enabled in the accepted DataExplorer hardware trials and the
  XML still marks them for verification. The research establishes 9600/8N1 but
  does not establish that either control line is required.

## Potential future benefits

1. Use EnvironmentalTester 1.22 communication logging for a dynamic cadence
   capture and for checking whether Alarm UI changes emit any TA612C TX frame.
2. Add a sanitized physical below-zero frame as a shared protocol fixture.
3. Consider an explicitly enabled diagnostic raw-byte transcript for future
   hardware investigations. Keep it separate from normal OSD import, avoid
   publishing captures implicitly, and do not weaken atomic import semantics.
4. Consider auto-connect only as a user-selected DataExplorer UX feature with
   deterministic device/port selection and no command sent during discovery.

## Later physical `0x04` result

A controlled trial after this source review sent `AA 55 04 03 06` once to the
firmware 3.50 device. The immediate pre/post REC streams were identical: two
frames, nine sample groups, 82 bytes and SHA-256
`aaef715625c41298ddbbb5f3e27e914cc8d266531a6a55a04f3a324f7be62422`.
This supports no plugin implementation: the command had no observable erase
effect in the tested state, returned no acknowledgement and remains potentially
destructive outside that state. The detailed record is
[`data-erase-trial-2026-09-15.md`](../../../research/data-erase-trial-2026-09-15.md).
