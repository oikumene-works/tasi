# TA612C tests with DataExplorer 4.0.8 beta 3

On 2026-10-16 the integrated TA612C plugin recorded the connected probes,
imported an operator-made REC recording and preserved the checked OSD values
and timestamps. Four GUI problems were reproduced: the REC menu stopped
opening its dialog after an OSD was opened, table headers could describe the
wrong probes, and later live segments acquired a drawn line before their first
stored sample. A later saved-file follow-up reproduced an old curve and selector
after opening another OSD from Table and returning through the View menu or
Ctrl+PageUp. These findings remain open; this package contains no fixes.

Start with [the findings and reproduction steps](GUI_FINDINGS.md). The
[source explanations](SOURCE_ANALYSIS.md) are a separate optional annex.
[Evidence access and checksums](EVIDENCE.md) identify the downloadable OSD and
table package. The [retained helpers](../../../scripts/gui_test/README.md)
support later inspection and testing.

## Who did what

The operator connected, warmed and disconnected the physical probes, operated
the meter's REC recording, unplugged and reconnected USB, supplied display
readings, and manually exercised the REC menu in the final reproduction.
Codex operated the other DataExplorer steps, inspected native GUI state and
screenshots, decoded saved files, compared their data with native table
readbacks, investigated the source and prepared this report and its attachments.
There was no independent human review of the source analysis or automated data
comparisons. Operator reports and Codex-derived measurements are distinguished
below. Finger warming checks channel identity and response, not calibration.

Codex alone performed the later saved-file refresh reproduction, read-only
model/cache inspection and source tracing. It used the unchanged beta in an
isolated profile on a 1280×720, 96-DPI Xvfb display with GTK3/X11 and made no new
physical acquisition. The screenshots for that finding show this unmodified
beta. The same two OSD files are already in the original evidence archive.

## Tested environment

| Item | Tested configuration |
|---|---|
| Application | Distributed DataExplorer 4.0.8 beta 3; supplied integrated Tasi plugin |
| Build manifests | Core: 2026-10-15 12:18:13; Tasi: 12:18:32; time zone unspecified |
| Runtime | Bundled OpenJDK 25.0.4+7-1-24.04-Ubuntu; no application rebuild |
| Host | Linux x86_64, kernel 6.18.49-1-MANJARO; SWT GTK on X11 |
| UI/profile | English, isolated fresh profile; `is_partial_data_table=true` |
| Meter connection | USB serial, Linux `ch341` driver, USB ID `1a86:7523`, 9600 baud/8N1 |
| Probes | T1–T4; operator identified the added T1/T3 probes as K type with fiberglass leads marked AWG30 |
| Dates/times | Physical tests on 2026-10-16, Europe/Helsinki; file timestamps also retain UTC/epoch values |

TA612C is the device name used in this repository and DataExplorer. The same
meter was sometimes called TAC-612 or TC612C during the conversation. No claim
of support for another model follows from those names. The prior project record
identifies firmware 3.50; firmware was not queried again during this beta round.

The supplied runtime and program files were left unchanged. Accessibility and
the X11 backend were enabled for native UI inspection. The isolated profile
enabled TA612C through configuration and disabled update checks and serial
availability probing. This does not verify the first-use device-selection flow.
No personal profile or private correspondence is part of this publication.

The tested core SHA-256 is
`637a24be90e34c8e081d229a0e55d18cc92340d45ebe43130a9fb2b4fb1617a4`;
the Tasi JAR SHA-256 is
`6738ecd6a704b97c346a1be4052851d7bfed45a1efbc9c318c4c7e7bc07d0829`.
The downloaded archive SHA-256 is
`d2232f8711b405f3204dcb2fc95c2258dde37b3ce50b6f28fbcf6396103a7c50`.
[Manifest details](verification/binary-identity.json) identify the tested build
without redistributing it or its private download link.

## Test results

Counts below are read from saved data by Codex. A row is one timestamp in one
record set; it can contain more than one temperature. Each new recording was
saved and reopened through DataExplorer's native OSD path. Displayed table time
has millisecond precision; OSD stores 0.1 ms units.

| Test | Observation and checked data | Result / qualification |
|---|---|---|
| T1 only | Operator: initially 22.4 °C, warmed to 35.0 °C and saw the correct curve rise. Saved: 75 samples over 113.6304 s; maximum 35.0 °C. | Values and times survived reopen; other probes empty. The retained table JSON has rows but no header array. |
| T2 + T4 | Operator warmed probes in turn and confirmed the correct curves; reported 35.0 °C. Saved maxima were 35.0 °C for each, at different times; 118 rows, 236 temperatures over 179.5308 s. | Channel mapping and saved data correct; stale table headers occurred. |
| Table reproduction | Open T1 OSD, File > New, acquire T2/T4. Repeated in the same process and after normal restart, 20 rows each. | Both reproduced Time/T1 headers with T2 values and no T4 column. Native reopen restored Time/T2/T4 and the stored values. |
| T4 unplug/return with T2 retained | Three segments: 14 rows T2+T4, 9 rows T2, 29 rows T2+T4. Total 95 temperatures over 78.2905 s. | T4 absent from 21.4947 through 33.7891 s; returned at 35.3271 s. No replacement points for T4. Later segments showed the leading-line finding. |
| REC T2 + T4 | Operator made an approximately three-minute recording at confirmed `00:05 SEC`. One native download received 36 groups in six nonempty REC frames. Saved: 72 temperatures at inferred 5 s steps from 0 to 175 s. | Values, times and provenance comment survived reopen. Initial table labels wrong; completeness and absolute recording time remain unknown. |
| Manual REC menu | Operator opened the interval dialog after fresh startup; Codex cancelled and opened the saved REC OSD; operator selected REC again. | Enabled menu opened neither a dialog nor a message after OSD opening. No Download was submitted in this reproduction. |
| All four probes | 196 rows, 784 temperatures over 299.1568 s. Operator warmed T3 then T1 and reported 35.0 °C and 34.8 °C on the correct curves; saved peaks match. | All four channels and table labels correct. Operator attributed a separate T2 excursion to moving leads/probes during handling. |
| All probes removed, T3 returned | Three segments: 20 rows all four, 1 row T2/T3/T4 during removal, 51 rows T3. Total 134 temperatures. Last pre-gap time 30.7585 s; T3 resumes at 53.8227 s. | 23.0642 s stored sample gap; no absent-probe replacement points. T3-only table had stale labels before save. Reopened graph draws the first value back to zero. |
| Combined USB and power loss | Operator unplugged USB and reported that the meter immediately powered off. DataExplorer stopped, released serial and retained nine T3 samples over 12.3116 s. | Notice explained connection loss and retained data. Codex had not pressed Stop and its stop timer had not fired. This was not isolated data-link loss. |
| Recovery after reconnection | Operator restored USB/power and reported T3 at 23.2 °C. Codex explicitly started a new acquisition in the same application process. | Twenty T3 samples over 29.2268 s, 23.2–23.3 °C; save/reopen matched. No application restart or automatic retry. |
| Earlier OSD compatibility | Two September files contained 5 and 8 sets: 2,381 rows and 4,598 temperatures across file entries. Native open/resave preserved values, times, epochs, physical probe masks, names and comments. | First five sets in the second file duplicate the first file; counts are not independent experiments. Sparse T4 metadata moved T4 first in UI name order without changing physical mappings. |
| Saved OSD view refresh | Open the 20-row T3 recovery file, then the 196-row four-probe file from Table. Return to Graphic through the View menu or Ctrl+PageUp. | The old T3 image/selector remained while the new values and times were already loaded. Pointer tab selection refreshed both; no loaded-data change was observed. |

The [recording inventory](verification/recordings.json) supplies exact filenames,
hashes, sizes and segment times. [Offline publication checks](verification/offline-recheck.json)
repeat the saved table comparisons for nine recordings (all their sets), the
T1 row-only comparison and both legacy original/resaved comparisons. These are
checks of retained evidence, not additional physical tests. Wider ranges in the
legacy recordings sometimes made native UI temperatures round to whole degrees;
the original and resaved OSD values remained exact.

## REC interpretation

The operator supplied the known recording interval; the importer did not read
it from the meter. REC sample times are inferred from order and that interval.
Epoch zero is an explicit unknown-time placeholder, not a real recording date.
The 1.5-second silence endpoint does not establish that the whole meter memory
was received. The recording duration and 36 groups are consistent with the
operator's short trial, but that consistency does not prove completeness.

The REC peaks of 34.6 °C on T2 and 34.8 °C on T4 are Codex-derived saved values,
not separately supplied peak readings from the operator. Only the accepted live
and REC read paths were used; this round introduced no new meter commands.

## Boundaries and current state

This is a short physical functional test of one meter and one Linux environment.
It does not establish temperature accuracy, high-temperature performance,
long-duration stability, every probe combination, other operating systems,
all graph modes or REC memory completeness. Corruption, truncated transfers,
cancelled physical downloads and transfer limits were not newly exercised here.
The older standalone automated suite is separate evidence; it was not rerun
against this distributed beta. Packaging did not change production Java code.

The source annex explains the four observed GUI behaviors against pinned
upstream source. The original comparison of fourteen unmodified files produced
171 classes matching
the distributed beta byte for byte; three message bundles also matched. This
does not claim a reproducible build of the entire application or independent
human verification of the explanation. The refresh follow-up also inspected
the distributed tab-selection bytecode and captured the active model and cached
image through a read-only agent in disposable test processes.

The retained test application was stopped with serial closed after the final
recovery measurement. The helpers are editable working aids saved for continued
DataExplorer work. This publication makes the material available for review;
it does not merge changes, request fixes or send a separate maintainer message.
