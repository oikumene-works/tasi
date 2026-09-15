# Validation — live missing-probe continuation, 2026-09-15

Decision: **the bounded partial-probe slice is accepted. T1-only and T2+T4 data
acquisition, REC capture and sparse OSD restoration passed short physical trials.
The final T2+T4 retest also showed advancing values, table and graph with T1
absent.** The REC implementation and command boundary are unchanged. This is not
a comprehensive probe-combination or long-duration hardware validation.

The live worker no longer treats the exact raw open-probe value 28000 as a fatal
condition. Every decoded frame reaches the live listener and resets the live
activity timer. The DataExplorer integration starts a new fixed-mask RecordSet
when probe availability changes, stores only the present probes in T1–T4 order,
and uses the same host-receive epoch across the acquisition. Consecutive all-open
frames create no temperature rows or empty RecordSets; acquisition waits for a
probe to return. Existing silence retries, manual Stop, USB failure handling,
exclusive live/REC ownership and close behavior are retained.

The T1-only physical trial displayed and stored T1 while T2-T4 remained empty.
The T2+T4 trial stored and restored both connected probes while T1 and T3 stayed
empty. REC preserved the same intended heating events in both trials. In the
second trial the values updated but the live graph did not. A width-only
correction also failed physically; the Table tab then showed the decisive detail:
row count increased while every row repeated the first time and temperatures.
DataExplorer maps table indices and graph time/index through `recordSet.get(0)`.
The plugin now orders the first genuinely present probe first in the RecordSet UI
name list. Probe records retain their physical ordinals and values, and absent
probes remain empty. OSD restoration maps metadata by name and physical ordinal.
That correction fixed the Table tab in a physical T2+T4 retest, while the graph
still remained static. The remaining core dependency was XML scale synchronization
to absent T1. Sparse segments now give each probe an independent scale by using
the valid integer override `-1`; present probes therefore have visible scales,
while all-probe segments retain the normal XML synchronization. Using `-1` also
avoids DataExplorer 4.0.7's inability to deserialize an empty integer property.

Validation in the preserved standalone module used JDK 21, the Java 19 target,
the official DataExplorer 4.0.7 source/dependencies and an isolated Xvfb
display/profile. The user reported the final full run as `BUILD SUCCESSFUL`:

- **109 live protocol/lifecycle assertions PASS** (previously 94).
- **135 unchanged REC protocol/lifecycle assertions PASS**.
- **57343 REC import/OSD/fixture/UI assertions PASS**.
- **52 XML/loader/OSD/serial integration assertions PASS** (previously 50).
- **57639 assertions total; BUILD SUCCESSFUL**.

The sparse integration checks exercise the exact sparse-live RecordSet helper:
open probes have zero points and remain hidden, a present negative value, present
zero and host timestamp survive, and a sample cannot cross into a segment with a
different mask. The live checks now append two T2+T4 rows and require the shared
time plus both table temperatures to advance while T1 and T3 remain empty. Every
synthetic REC segment must promote a present probe as the UI anchor before and
after OSD reopening. Existing integration coverage also reopened both legacy
live OSD fixtures, replayed all four REC fixtures, and completed sparse OSD
save/read/lazy-load/resave/read cycles. They also require both T2 and T4 to have
independent visible scales, verify those scales after REC OSD reopening, and
confirm that a normal all-probe live set retains the XML T1 scale group.

Retained local log: `local/validation/full-test-2026-09-15.log` (intentionally
ignored by Git; checksum recorded in `RELEASE.md`).
The final Xvfb run was launched from the user's normal terminal because the Codex
sandbox maps the host-owned `/tmp/.X11-unix` directory to `nobody`, preventing a
second X server from creating its socket. This was an environment constraint, not
a test failure. The Ant test still used the module's isolated test home and did
not access a physical serial port.

The final bounded T2+T4 live retest with the sparse-scale candidate passed by
user observation: values, table and graph worked while T1 and T3 were absent.
No REC repetition or additional OSD save was required because those paths had
already passed the preceding physical checks and the final OSD regressions.
Repeating the earlier probe combinations, USB disconnect or a comprehensive
transition matrix remains outside this acceptance boundary.

---

# Historical validation — REC implementation, 2026-09-12

The section below is the pre-hardware-check snapshot retained as historical
evidence. The later `TA612C-ChatGPT-jatkoraportti-2026-09-12.md` records the
successful bounded REC hardware check and supersedes the pending status below.

Decision: **REC implementation passes device-free regression tests; physical REC
acceptance remains pending.** The accepted live MVP is preserved. No physical
serial port was opened, no meter command was executed against hardware, no meter
memory was changed, and no trial application was closed or updated during this
implementation.

## Current automated result

JDK 21, Java 19 target, Ant, bundled DataExplorer 4.0.7 core/dependencies and a
separate Xvfb display/profile:

- **135 REC protocol/lifecycle assertions PASS**.
- **94 existing live protocol/lifecycle assertions PASS**.
- **57239 REC import/OSD/fixture/UI assertions PASS** (includes per-value checks).
- **40 XML/loader/OSD/serial integration assertions PASS** (the original 37 plus
  REC disconnect and exact read-only command checks).
- **57508 assertions total; BUILD SUCCESSFUL**.

Retained final log: `build/validation/rec-20260912.log`. The tests ran from an
isolated module copy using the canonical core JAR. The tested sources, fixtures
and packaged candidate were then transferred into the canonical TA612C module;
`build/device-trial`, its installed JAR, profile and original recordings were
preserved. `ant clean` was not run.

The sandbox prevented GTK from opening its local virtual-display sockets. The
same tests succeeded with permission to use an isolated Xvfb display. One later
automatic permission review timed out; its permitted retry succeeded. This was
an execution-environment issue, not a device or protocol result.

Tests cover strict variable framing, all frame split points, multi-sample and
maximum aligned payloads, all 16 probe masks, signed values, noise, mixed commands,
checksum failures, incomplete trailing frames, empty responses, initial silence,
fragmented byte progress, cancellation before/during transfer, read timeout,
open/write/read/close failures, duration/size bounds, port ownership and explicit
interval parsing. Failures do not produce a partial import.

Integration exercises all 15 nonempty probe masks (including T1 absent), exact
original-index time offsets, sparse payload rejection before appending, OSD
save/read/lazy-load/resave/read, preservation of absent probes and provenance,
zero epoch, overflow rejection, unchanged live interval, the REC menu and interval
dialog, and port close waiting across separate core/plugin class loaders.

## Local evidence and reference replays

| Decoded export | Groups | Valid temperature points retained | All-absent groups | Partially present groups | Segments |
|---|---:|---:|---:|---:|---:|
| `ta612c_rec_20260909_142327.csv` | 760 | 2819 | 55 | 1 | 2 |
| `ta612c_rec_20260910_034221.csv` | 2250 | 6831 | 542 | 1 | 3 |
| `ta612c_rec_20260910_050104.csv` | 80 | 222 | 24 | 2 | 2 |
| `ta612c_rec_20260911_085955.csv` | 1141 | 1592 | 400 | 687 | 3 |

All **11464 valid temperatures** from 4231 sample groups survived native import
and OSD restoration. The fourth export was found during this session. The user
explicitly requested preserving readings from partially connected probes.
Groups with all probes absent produce no temperature rows; their sample ranges
remain in segment descriptions. Source CSV copies and their hashes are recorded
in `test/fixtures/README.md`.

These are **synthetic protocol replays of decoded exports**, not raw instrument
captures. Tests rebuild checksums/headers from the decoded values and frame
grouping; they cannot independently validate real wire framing, missed whole
frames or any recording interval. Every CSV has five-second elapsed increments,
but the inspected `ta612c_logger_v2.py` generates that column as
`sample_index * args.interval` and defaults to five seconds. No source examined
establishes the interval or absolute recording start as meter-read facts.

Existing instrument OSD files were reopened using the new plugin: the original
41-row live set and all five restart/fault sets (10, 13, 4, 14, 6 rows; 47 total).
Their four columns and monotonic host timestamps remain intact. Original files
still have the hashes documented in the historical validation below. Tests use
byte-identical copies and check that those copies also remain unchanged.

## What is implemented vs still uncertain

REC sends the download command once and buffers an entire provisional transfer.
No erasing, recording-start or settings commands exist in this implementation.
An info query is not sent; its necessity before REC is unverified on hardware.
The strict decoder rejects any detectable loss of continuity. Silence after
1.5 seconds of no bytes is a heuristic endpoint, not verified completion. Missing
whole frames, pauses between complete frames and an unknown stored sample count
remain limitations even when the checksum-valid received data imports successfully.

An explicit user-supplied interval determines relative time. Epoch zero is a
labelled unknown-date placeholder required by the OSD model; absolute-time views
must not be treated as recording dates. REC OSD files with absent probes require
this REC-capable plugin; the old live-only build cannot restore sparse payloads.

The remaining device check is bounded: download existing memory in the isolated
trial using a known recording interval; compare counts/values and save/reopen.
Do not infer a timing setting from the existing CSV alone. Do not erase memory,
change meter settings or start a new internal recording. A longer live trial is
not required. See `NEXT_SESSION.md` for the handoff.

---

# Historical live validation — 2026-09-10

Decision: **GO for the four-probe live MVP within the tested short,
room-temperature acquisition boundary.** The basic instrument checks are complete:
four matching temperatures, immediate port selection, manual Stop/restart,
T4-disconnect stop/recovery, USB-disconnect stop/recovery, and OSD save/restoration.
The latest automated run passed **131 assertions**. Longer recordings, sampling
cadence characterization and physical negative/zero-temperature checks remain
later validation; REC and continued acquisition with missing probes remain
outside this implementation boundary.

## First instrument trial and port-discovery correction

The user reported that all four temperatures appeared and matched the meter's
display. The selected port was `ttyUSB0`; read/write permission was checked
without opening it. The trial uses its own application copy and profile under
`build/device-trial`. This confirms basic live acquisition with the configured
9600/8N1 and enabled RTS/DTR, not that these line settings are required.

The user also reported that the port was initially missing from device settings
and became available after leaving and re-entering them. The exact interaction
sequence is unknown. Initial device activation also required checking TA612C
under Used devices despite the seeded settings.

Source inspection found a definite initialization defect in the plugin:
`DeviceCommPort.listConfiguredSerialPorts()` returns an empty list until its
static serial backend has been registered. TA612C previously registered it only
when Start created the transport. Both device constructors now prepare a closed
transport, registering that backend before port selection. Construction does
not enumerate ports, open them, or send instrument commands. The main program,
upstream dialog and acquisition loop are unchanged. This defect is a plausible
explanation for the observation; the exact user sequence was not reproduced.

The new regression check failed against the old implementation at
"XML constructor enables core port discovery before Start". After the fix,
`ant test` under Xvfb passed **92 protocol/lifecycle + 33 integration assertions
(125 total)**. Both loader constructors register the correct backend while
leaving the transport disconnected. Logs: `build/validation/port-before.log`
and `build/validation/port-fixed.log`.

After the private application was restarted with the corrected plugin and the
saved OSD reopened, the user opened Device configuration and confirmed that the
port appeared immediately on the first opening. This closes the initial port
selection UI check; USB removal/reconnection is tracked separately below.

For two requested short Start/Stop cycles, the user reported that, in their
view, all four readings updated on both runs. The user then explicitly confirmed
that stopping worked. Basic manual Stop is confirmed, and updates after restart
are supported by the user observation. No precise stop latency was measured.

The user unplugged T4 during acquisition and supplied a screenshot showing
"TA612C stopped: open probe T4", the green "start gathering" control, and the
retained temperature curves around room temperature without a visible 2800 C
spike. The user reported that the stopped state and message remained after T4
was reconnected. This matches the current stop-on-missing-probe policy: the
worker exits and closes the port, so reconnection alone cannot resume sampling
or update the previous stop reason. A manual Start begins a new recording.
T4 detection, stopping, and visible retention passed this instrument check.
After reconnecting T4 and manually starting a new acquisition, the user confirmed
that all four readings updated again. Recovery after probe reconnection is now
confirmed by the user; there is no automatic continuation of the old recording.

## USB removal and negative available-byte correction

The user unplugged USB during acquisition and confirmed that collection stopped.
Their screenshot shows the green Start control, retained curves and the status
"TA612C stopped: -1. Collected data is retained." The stopping and visible data
retention worked, but the numeric explanation prompted the correction below.

Inspection of the matching jSerialComm JAR and upstream core identified a path
consistent with this symptom: its stream's `available()` forwards the native
byte count, including a possible negative result. The adapter treated any count
less than or equal to zero as silence. On a later retry, the core's input cleanup
allocates `new byte[available]`, which can throw `NegativeArraySizeException(-1)`.
No captured stack trace proves this was the exact exception in the user's run.

The adapter now distinguishes zero available bytes from a negative error result.
A negative count immediately becomes an IOException with the explanation
"USB/serial connection unavailable. Check cable and press Start". A negative
buffer allocation during the core write's cleanup is also translated into that
explanation, retaining the original exception as its cause. Acquisition still
stops through the existing worker cleanup and retains earlier accepted samples.
No upstream core changes or automatic reconnect behavior were introduced.

The regression uses the real core adapter with in-memory input/output streams,
not a physical serial port. Before the fix it failed because a negative count
was silently accepted. After the fix, **94 protocol/lifecycle + 37 integration
assertions (131 total) passed**. Checks include no command bytes written after
the simulated disconnect, preserved exception cause, and an accepted sample
retained when the following read fails. Logs: `build/validation/usb-before.log`
and `build/validation/usb-fixed.log`.

After the user returned and saved `test-2.osd`, all five recording sets were
loaded with the corrected plugin in an isolated profile: **47 rows total**, four
probes per row, monotonic timestamps within each set, and no stored open-probe
sentinel. The sets contain 10, 13, 4, 14 and 6 rows respectively. This verifies
retention and restoration across the manual restart and fault-test recordings.
The inspection did not open a serial port. Log: `build/validation/test-2-osd.log`.
The recording's SHA-256 is
`1bbd5baeffe154d5269231ba401af0ce5503f5845c31e9c1abeb15d0fec66f02`.

The private trial's old process had no open serial descriptor. It was closed,
the tested USB-correction JAR installed, and the application restarted with
`test-2.osd`. The recording remained byte-for-byte unchanged. The previous JAR
is retained outside the plugin discovery directory under `previous-version`.
The current trial and module JAR match.

In the final instrument check, the user supplied a screenshot showing the new
status: "TA612C stopped: USB/serial connection unavailable. Check cable and press
Start. Collected data is retained." The user also confirmed that acquisition
restarted successfully after USB reconnection. This confirms the revised
disconnect report and manual recovery with the current trial build, and closes
the basic instrument acceptance sequence. Automatic reconnection is not provided.

## First instrument recording

The user's file `build/device-trial/recordings/TA612C/2026-09-10_.osd` was read
with the real DataExplorer reader and corrected plugin in a separate profile:

- **41 rows**, four temperature records, **61.4066 seconds** between first and
  last accepted sample; timestamps are monotonic and no sentinel is stored.
- First T1-T4 values: **24.4, 22.5, 23.0, 22.4 degrees Celsius**.
- Last T1-T4 values: **24.3, 22.7, 23.0, 22.7 degrees Celsius**.
- SHA-256: `afcb0cb0a8d26d25e70c6c0882634d92460ed14b6f37edace4afccbc948c734a`.
- The inspection left the source recording unchanged and did not open a port.
  Log: `build/validation/first-instrument-osd.log`.

Later validation: negative/zero instrument readings and longer recording/cadence
validation. No further user action is required for this bounded MVP acceptance.
Generated files under `build`, including instrument recordings, are removed by
`ant clean`; preserve wanted recordings elsewhere before cleaning.

## Observed build and tests

- Upstream source release: DataExplorer 4.0.7, schema DeviceProperties_V50.
- Compiler: OpenJDK javac 21.0.12, compiling with `--release 19`.
- Build runner: Apache Ant 1.10.15, Linux x86_64.
- Clean build compiled the 337 upstream Java sources into a private core JAR,
  then all four plugin production files and the test class.
- Final command targets: `clean prepare-core test` under a temporary Xvfb display.
- Terminal result: **BUILD SUCCESSFUL**, total reported build time 4 seconds.
- **92 protocol and lifecycle assertions passed.**
- **30 XML, loader and OSD integration assertions passed.**
- The actual DataExplorer OSD writer and reader preserved both test rows,
  relative timestamps, the absolute start timestamp, and a calibration factor.
- This includes malformed/truncated OSD buffer rejection before appending data,
  fixed-step and variable-step restore, zero-degree visibility, failed-open and
  disconnect cleanup, bounded silent retries, user cancellation, and sentinel stop.

The retained `build/validation/build.log` contains the successful terminal output.
`build/test-output/ta612c-roundtrip.osd` is synthetic data produced by the actual
DataExplorer writer. It is not a captured meter recording.

## Scope and limits

During the automated checks, no physical serial port was opened. The instrument
trial used a separate application/profile. Upstream core, AV4ms, CSV2SerialAdapter and
master-build files were not edited. The standalone module is self-contained;
generated core artifacts stay under this module's `build` directory.

Xvfb was downloaded from the configured Ubuntu package repository and unpacked
under a temporary directory for tests. It was not installed into the operating
system. Integration settings were created under an isolated Java user-home
directory in the build output, not the user's existing DataExplorer profile.

Open-probe policy is deliberately limited: all four probes must be connected,
and one open-probe value stops the session before adding that row. Continuing
with a subset of probes, per-probe graph gaps and REC download require later
work. Basic live readings work with the configured line settings; their
necessity was not isolated by these tests.

Initial test failures were corrected in the test setup: upstream SWT requires a
display, fresh upstream settings require their parent directories, and a manually
written OSD test vector encoded -10 where its expected value was -100. Final
results above are from a clean rebuild after those corrections.
