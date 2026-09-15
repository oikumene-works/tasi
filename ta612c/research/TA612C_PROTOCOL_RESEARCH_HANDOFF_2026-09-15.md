# TA612C protocol research handoff — 2026-09-15

Purpose: give another developer or coding agent a compact, source-aware picture of what is currently known, what is inferred, what is potentially dangerous, and what remains open.

## 1. Physical device baseline

Device under test:

- TASI TA612C
- firmware 3.50
- Linux serial device through QinHeng CH340 (`1a86:7523`)
- 9600 baud, 8N1

Physically exercised successfully:

- `0x00` device info
- `0x01` live data
- `0x02` REC download

Observed open-probe value: `2800.0 °C`.

## 2. Wire commands

Known command frames from host to meter/common vendor serial layer:

```text
0x00  AA 55 00 03 02                  device info / stop-info path
0x01  AA 55 01 03 03                  live data
0x02  AA 55 02 03 04                  recorded REC data upload
0x03  AA 55 03 07 <4 bytes> <sum>     clock synchronization
0x04  AA 55 04 03 06                  labelled DATA_ERASE by EnvironmentalTester 1.22
```

**Do not experimentally send `0x04`.** Its TA612C applicability is unconfirmed, but the vendor software label makes it potentially destructive.

## 3. Framing

Meter response header:

```text
55 AA
```

Current response reader uses:

```text
total_frame_length = frame[3] + 2
checksum_ok = (sum(frame[:-1]) & 0xFF) == frame[-1]
```

Temperature sample payload is 8 bytes, T1..T4, 2 bytes each, little-endian, 0.1 °C scale.

The local parser uses signed 16-bit values. This is consistent with negative-temperature capability, but a deliberate below-zero capture should eventually become a regression fixture because Artisan has used unsigned decoding.

## 4. REC data

Observed REC payload has no confirmed per-sample timestamps and no confirmed session-boundary marker.

Current logger therefore:

- keeps `sample_index` as the primary sample identity
- reconstructs `assumed_elapsed_s = sample_index * --interval`
- does not infer sessions
- preserves raw frame and transcript evidence for later reinterpretation

## 5. Vendor protocol document

The 2022 `TA Series Communication Protocols` document supports:

- serial settings
- frame/checksum structure
- commands `0x00`..`0x03` for the relevant family context
- a `0x04` function-setting concept in other model context

Its TA612 example firmware is older than the tested firmware 3.50.

An earlier reading of the clock-sync payload as BCD should be considered superseded by vendor-software evidence: EnvironmentalTester 1.21/1.22 calls Qt `QDateTime::toTime_t()` and sends the resulting 32-bit Unix timestamp payload.

## 6. EnvironmentalTester 1.21

Analyzed executable:

```text
SHA-256  29618357e560231377eee00c1486ef06f57637e13c4d348d06024cd05dda4876
size     2326016 bytes
```

Embedded paths identify `TA_Env_V1.21_PRD`.

Important findings:

- common serial send switch contains cases `0..4`
- `0`, `1`, `2` produce the known info/live/REC frames
- `3` constructs clock sync from `currentDateTime().toTime_t()`
- `4` constructs `AA 55 04 03 06`
- TA612C normal UI paths use live and upload/REC; no TA612C alarm mute/ack path found
- EnvironmentalTester Alarm thresholds are compared locally on the PC and trigger bundled high/low alarm WAV resources
- TA612C interval setting changes a PC-side Qt polling timer, not the meter's REC interval

## 7. EnvironmentalTester 1.22

Analyzed executable:

```text
SHA-256  ac9a9d3415b9de12958b794f49e1d9e351a38979eef4b368ce4975a2fb123bad
size     2783744 bytes
```

Important differences/findings:

- same five command cases remain
- TX logging strings name them `START`, `PAUSE`, `UPLOAD`, `SYNC`, `DATA_ERASE`
- `AA 55 04 03 06` is therefore associated with the label `DATA_ERASE` in the common serial layer
- TA612C-specific work appears focused on auto-connect rather than new protocol commands
- `config.ini` enables communication logging with `[Debug] EnableCommunicationLog=true`
- no new TA612C alarm mute/ack command found statically

Future dynamic test: change EnvironmentalTester's Alarm field while communication logging is enabled and inspect TX traffic. If no new TX frame appears, that strongly confirms the Alarm UI remains PC-side in 1.22.

### Controlled `DATA_ERASE` candidate trial

After explicit authorization and a validated nine-group REC backup, the bare
`AA 55 04 03 06` command was sent exactly once to the physical firmware 3.50
TA612C. It returned no bytes. One immediate read-only REC request returned the
same two frames and 82 bytes as before the command; the complete pre/post streams
were byte-for-byte identical with SHA-256
`aaef715625c41298ddbbb5f3e27e914cc8d266531a6a55a04f3a324f7be62422`.

This is a bounded no-effect observation, not evidence that `0x04` is safe on
other firmware or in another device state. Do not put it into normal tooling or
guess variants. See `data-erase-trial-2026-09-15.md` for the procedure and
interpretation.

## 8. Artisan

Artisan provides an independent live implementation:

- 9600/8N1
- sends `AA 55 01 03 03`
- reads four channels
- useful UI/graph integration

Its TA612C reader is intentionally much narrower than the local research logger: no info verification, REC download, sync or alarm path, and less defensive frame handling. It has historically interpreted the channel words as unsigned.

## 9. Local Python logger v3.2

Canonical current script: `software/logger/ta612c_logger.py`.

Capabilities:

- device-info verification
- live logging
- read-only REC download
- response header search/resynchronization
- checksum verification
- signed little-endian channel parser, scale 0.1 °C
- raw frame CSV
- exact TX/RX transcript JSONL for REC download
- metadata and SHA-256 hashes
- open-probe and abrupt-change diagnostics
- no erase command

Potential cleanup:

- live mode currently repeats `LIVE_CMD` after >1.2 s without a valid frame; explicit one-request/one-response polling would more closely match the vendor protocol wording and Artisan's behaviour
- `verify_meter()` returns model and firmware but does not yet enforce `model == 612`

## 10. Alarm state

Physically confirmed on firmware 3.50:

- HI alarm sounds above threshold
- `MODE` into settings silences it
- returning to measurement without changing HI causes alarm to resume while still above threshold
- no separate acknowledge/mute operation found
- power-off silences but interrupts REC

No software source examined so far has revealed a TA612C physical-alarm acknowledge/mute command.

## 11. Rules for downstream code

1. Treat `0x00`, `0x01`, `0x02` as the currently verified non-destructive core.
2. Do not send `0x04` casually or as a discovery probe.
3. Do not treat EnvironmentalTester's Alarm fields as evidence of meter-side HI/LO configuration.
4. Preserve raw bytes where practical so later protocol corrections do not destroy evidence.
5. Keep observed facts, vendor-document claims, static-analysis findings and inference distinguishable.
6. Do not infer REC session boundaries from temperature discontinuities alone.
7. Keep negative-temperature signedness as a fixture-worthy test until physically captured below zero.
