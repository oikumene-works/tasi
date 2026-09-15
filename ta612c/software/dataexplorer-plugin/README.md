# TA612C for DataExplorer 4.0.7

Standalone TASI TA612C plugin: four-input live acquisition, download of existing
internal REC memory, and native OSD saving/restoration. No upstream core, other
plugin or master-build changes are required. Code and tests use the bundled
DataExplorer 4.0.7 dependencies.

## Live acquisition

Select TA612C and its serial port, then use Start/Stop. Live T1–T4 share one
variable host-receive time base. A raw value of 28000 means that probe is open.
The session continues with every probe that is present; a new native RecordSet
starts whenever the presence mask changes. Open probes remain empty and hidden,
never replaced by zero, the sentinel or a previous value. A frame with all four
probes open updates connection activity but adds no temperature row. Reconnecting
a probe starts another segment without requiring a manual restart. Zero and
negative values are valid.

The common live epoch is the host receipt time of the first decoded frame in the
acquisition, including an all-open frame. Thus later segments retain their offset
on the same receive-time axis and a period with no probes is not compressed away.
Silence, USB/serial failure and manual Stop retain their existing bounded stop and
cleanup behavior. Device construction registers a closed serial backend for port
discovery before Start; it sends no commands.

DataExplorer 4.0.7 uses `recordSet.get(0)` both as the graph time/index anchor
and to clamp every table-row index. For a sparse segment, the plugin therefore
orders the first genuinely present probe first in the RecordSet's UI name order.
The Record objects keep their physical T1-T4 ordinals, names, properties and
values. Absent probes remain empty and hidden; no temperature is copied or
fabricated. Sparse OSD properties are mapped back by record name and physical
ordinal, so the anchor survives save, reopen and resave without changing the
payload layout. Sparse segments also override the XML scale synchronization
with independent visible scales for the present probes. This avoids the XML
reference to absent T1 while keeping the normal T1–T4 scale synchronization
unchanged when all four probes are present. The persisted override is the valid
integer `-1`; an empty integer property is not used because the 4.0.7 OSD reader
cannot deserialize it.

Short physical trials on 2026-09-15 accepted T1-only and T2+T4 data acquisition,
REC capture and sparse OSD restoration. The T2+T4 trial and a first width-only
correction both failed to update the graph; the latter also exposed repeated
time and temperature cells while table row count grew. The UI-anchor correction
fixed the table in a physical retest, but the graph remained static. The final
sparse-scale correction passes the complete device-free suite. In the final
brief T2+T4 physical retest the user observed that values, table and graph all
worked. This accepts the bounded partial-probe slice; it is not a comprehensive
probe-combination test.

## Download existing REC memory

1. Stop live acquisition and select the intended serial port.
2. Choose **File → Import → Download TA612C REC memory... / Cancel REC**.
3. Enter the interval used when the recording was made, in seconds. There is
   deliberately **no default interval**. The dialog accepts 0.001–86400 seconds,
   with at most three decimals (dot or comma).
4. Choose Download REC. Use Stop or the same REC menu entry to cancel.
5. Inspect the imported segments and their descriptions, then save normally as OSD.

Only `AA 55 02 03 04` is sent for REC. There is no erase, recording-start,
configuration-write or automatic retry command. The Python reference performs a
separate info query; this implementation omits that query because it establishes
neither a proven model identity contract nor REC timing/completion. Whether the
meter needs an info query before REC is still a hardware-validation question.

Live and REC cannot run concurrently in this plugin, including across TA612C
device instances. Ownership extends through the core's asynchronous port close.
A failed contender does not close the active transport. The version-specific
close helper reads the core close thread reflectively because 4.0.7 has no public
wait API; it also works across the plugin/core class-loader boundary. If closure
cannot be confirmed within two seconds, the guard remains locked and the message
requires an application restart before another acquisition.

### Time provenance and transfer completeness

REC payloads contain temperature sample groups. The inspected protocol does not
provide a sampling interval, recording start time, per-sample timestamps, sample
sequence numbers, total sample count or a proven transfer-end marker.

The imported time is **inferred** as `original_sample_index * user_interval`.
The local Python logger's default five seconds and its CSV `elapsed_s` column
are the same assumption, not independent device evidence. No actual recording
interval has been established from the supplied sources. Enter the recording
setting only if known; Cancel otherwise. Changing the REC interval does not change
the live device configuration.

Each REC name says `REC inferred`; each description records the supplied interval,
original sample/frame ranges, missing-data ranges, and unverified completion.
The absolute recording time is unknown. OSD requires a numeric epoch, so REC sets
use **epoch 0 (1970) as an explicit unknown-time placeholder**, never the download
time. Absolute-time views must not be interpreted as real recording dates. These
labels and metadata survive OSD save, reopen and resave.

The downloader waits up to five seconds for the first byte, then ends after
1.5 seconds without **bytes**. This follows the Python silence heuristic, while
allowing fragmented frames to progress. Empty REC frames are counted and do not
terminate the transfer. No response is an error, not proof of empty memory.
An empty response or all-open probes creates no temperature set and does not
prove the memory is empty.

A checksum error, unexpected bytes/command, malformed payload, truncated tail,
read timeout, disconnect, cancellation or transfer-limit failure discards the
entire pending import. No partial data is published and earlier record sets stay
intact. An undetectably lost *whole* frame or premature silence can still make a
transfer incomplete: therefore even successful imports remain **unverified**.
Bounds are implementation safeguards, not claims about meter capacity: five
minutes, 100000 sample groups, 200000 frames and 1000 import segments.

### Partially connected probes

The user explicitly chose to retain valid measurements from partially connected
probes. A new native record set starts whenever the set of present probes changes.
Each segment retains the T1–T4 definitions; only present probes have points.
Absent probes stay empty and hidden, never replaced by zero, the sentinel or a
previous value. The original sample indices remain on the shared inferred time
axis; segments prevent curves from bridging a probe-availability transition.

Groups with all four probes absent have no temperature points to import. Their
original ranges are listed in every segment's description. If every group is
absent, the final status reports the received group count and zero segments.

Live acquisition now uses the same fixed-mask segmentation and sparse storage.
It reports mask changes in the status line and continues without sending any new
meter command. The existing `ta612c_rec_present` OSD property is reused as the
backwards-compatible sparse-storage marker for both REC and new live segments.

REC OSD files use the core's native subset-of-stored-records mechanism, with a
`ta612c_rec_present` property on each probe. The plugin reconstructs the storage
mask before the core sizes a lazy-loaded payload. Use this REC-capable plugin to
reopen such files; the previous live-only TA612C build cannot read sparse REC OSD.

## Protocol and OSD representation

Live command: `AA 55 01 03 03`. Live response: `55 AA 01 0B`, eight payload bytes,
checksum (13 bytes total). REC response: `55 AA 02 length payload checksum` with
`total_size = length + 2`; nonempty payload length is a multiple of eight.
Each group is four signed little-endian int16 temperatures in tenths Celsius.
Checksum is the sum of preceding unsigned bytes modulo 256. DataExplorer points
are raw values multiplied by 100 (thousandths Celsius).

The live decoder retains its bounded resynchronization behavior. The separate
REC decoder rejects corruption instead of resynchronizing past unknown sample
loss. Its buffer is at most 257 bytes; serial read chunks are at most 256 bytes.

OSD uses big-endian int32 values, with variable timestamps in 0.1 ms preceding
the stored measurement columns. The loader validates the whole payload before
appending values. Signed timestamp storage limits the supported REC span to about
59.6 hours; overflow is rejected before publishing. Existing fixed-step and
four-column variable-step live OSD files remain supported; new sparse live OSD
uses the same persisted storage mask as sparse REC. Calibration remains available
through the usual factor, offset and reduction properties.

## Files

| File | Responsibility |
|---|---|
| `TA612C.java` | Device, menus, live/REC UI lifecycle, OSD restore |
| `TA612CSerialPort.java`, `TA612CSerialClose.java` | Core serial adapter and exclusive lifetime |
| `TA612CFrameDecoder.java`, `TA612CGathererThread.java` | Live decoding/acquisition, including partial-probe continuation |
| `TA612CRecDecoder.java`, `TA612CRecDownloader.java` | Strict REC framing and bounded provisional transfer |
| `TA612CRecImport.java`, `TA612CRecDialog.java` | Probe segments, time provenance, explicit interval |
| `src/resource/TA612C.xml` | V50 definition; live configuration unchanged |
| `test/gde/device/tasi/` and `test/fixtures/` | Device-free protocol, lifecycle, UI, fixture and OSD tests |
| `scripts/test.sh` | Portable package, protocol and full-test entry point |
| `AGENTS.md` | Durable project boundaries for future Codex tasks |
| `RELEASE.md` | Accepted baseline, checksums and validation counts |
| `REMOTE.md` | Historical standalone publication checklist |
| `docs/TROUBLESHOOTING.md` | Reproducible build and repository-integration problems and resolutions |
| `docs/PROTOCOL_RESEARCH_REVIEW.md` | Impact of EnvironmentalTester and Artisan findings on plugin decisions |

Java production sources are under `src/gde/device/tasi/`.

## Build and tests

Use JDK 21 (compile target Java 19), Ant and the matching local source release.
No dependency downloads or physical port access are part of these tests. When
the ignored `local/deps/dataexplorer-4.0.7` tree is present, the helper finds it
automatically.

```sh
export JAVA_HOME=/path/to/jdk-21

scripts/test.sh package
scripts/test.sh protocol
scripts/test.sh all
```

Set `DATAEXPLORER_ROOT=/path/to/dataexplorer-4.0.7` to override the local tree.
`scripts/test.sh all` likewise prefers the ignored
`local/tools/xvfb/usr/bin/xvfb-run`, then falls back to `xvfb-run` from `PATH`;
set `XVFB_RUN_BIN` to override both. A sandbox may need permission to create its
local display sockets. Tests use an isolated Java user home and test
application, not an installed DataExplorer profile.

The helper reuses `DataExplorer/build/DataExplorer.jar` below the selected
dependency root when available. Otherwise it builds `build/core/DataExplorer.jar`
once. Set
`DATAEXPLORER_JAR=/path/to/DataExplorer.jar` to use another matching core JAR.

If no core JAR exists, run `ant prepare-core` first. From a separate module copy,
pass `-Ddataexplorer.root=/path/to/dataexplorer-4.0.7` and, if needed,
`-Ddataexplorer.jar=/path/to/DataExplorer.jar`. Only module output is generated.

`build/TA612C.jar` is the plugin artifact, with service
`TA612C:TASI:SERIAL_IO`. Builds do not install it into the private trial or a
running application. Generated build output is disposable. Local instrument
evidence, accepted binaries and dependency trees belong under the ignored
`local/` directory, never under `build/`.

See `VALIDATION.md` for measured results, `RELEASE.md` for the accepted baseline,
and `NEXT_SESSION.md` for the current handoff. Automated CSV replays reconstruct
synthetic frames; they do not establish wire-level correctness or actual timing
on the meter.

## License

GPL-3.0-or-later. See `COPYING`. DataExplorer integration follows the existing
GPL code by Winfried Bruegmann; relevant attribution remains in source.
