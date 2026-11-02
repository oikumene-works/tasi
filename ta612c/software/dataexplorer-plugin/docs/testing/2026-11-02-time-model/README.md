# Segment-time correction and beta 4 source patch — 2026-11-02

New live and REC RecordSets now start at relative zero. Live sets have their own
absolute epochs; REC retains unknown-date epoch zero and saves its inferred
recording offset in the description. Earlier files are read without migration.

At the operator's request, Codex implemented the corrections, wrote and ran the
software checks, adapted the changes to this repository and prepared this
report. No physical meter test or independent human validation was performed
for these corrections. The earlier physical acceptance remains historical.

## Standalone implementation in this repository

The module still targets DataExplorer 4.0.7. No core, transport, other plugin,
wire command, retry or cleanup policy changed.

- Live uses the initial host wall-clock anchor plus each new probe-mask
  segment's monotonic offset, rounded to whole milliseconds. Elapsed time
  resets for each segment. All-open frames end a segment without creating
  temperature data; gaps remain on the overall acquisition timeline. An
  injected clock allows deterministic checks of clock adjustments and wrap.
- REC uses `(sample index - segment first index) * explicit interval` for
  segment time. Its REC v2 description saves `segment first index * interval`
  as the recording offset, alongside original indices, frame ranges, all-open
  ranges and inference/completion caveats. The live description is also updated
  to describe its segment origin.
- The standalone menu already stores the same `ta612c-rec` marker that its
  guard checks. It needs no identifier change. An expanded regression check
  preserves the existing action, unrelated entries, label and single listener
  through 20 additional setup calls.

Epoch zero in REC is an unknown-date placeholder, not an actual 1970 recording.
Absolute-date comparison cannot automatically align separate REC segments;
their descriptions retain the inferred positions. Removing or editing those
descriptions can remove that provenance. The existing approximately 59.6-hour
whole-REC-span limit remains. Silence after 1.5 seconds still does not establish
that the whole meter memory was transferred.

The [historical source annex](../2026-10-16-beta3/SOURCE_ANALYSIS.md#curve-drawing-adds-a-line-before-the-first-stored-sample)
now corrects the earlier interpretation: a positive first relative time was a
plugin mismatch, not evidence of a DataExplorer core defect. The original
observations, stored values and recordings remain historical evidence.

## Standalone validation

The complete `scripts/test.sh all` command passed with JDK 21.0.12.1, Java 19
compile target, Ant and the DataExplorer 4.0.7 core. The run used Xvfb, a separate
profile/output, private device namespace, empty `/sys` and no network. No meter
was accessed. No assertion was bypassed or treated as an expected failure.

| Check | Passing assertions |
| --- | ---: |
| Live protocol/lifecycle | 109 |
| REC protocol/lifecycle | 135 |
| Deterministic live clocks | 4,774 |
| REC segment time, provenance and OSD | 499 |
| Live segment time and OSD | 10,728 |
| REC import, fixtures, OSD and UI | 60,718 |
| XML, loader and OSD integration | 52 |

Synthetic live scenarios cover leading/all-open gaps, a probe mask returning,
all 15 nonempty masks, clock adjustment, clock wrap and rounding boundaries:
417 nonempty segments and 850 stored rows. Maximum observed absolute-time
error was 0.560687 ms; maximum gap error was 0.949999 ms, within the 0.6/1.2 ms
bounds. These bounds reflect integer epoch milliseconds and 0.1 ms OSD storage.

REC scenarios cover leading/internal/trailing all-open ranges, direct mask
changes, one-point segments, real zero and negative values, signed limits and
intervals of 1 ms, 1.001 s, 2.5 s and one day. Two OSD round trips preserve values,
relative origins and saved offsets. Existing CSV reconstructions, sparse data,
legacy live OSD, cancellation, malformed input and ownership checks also pass.
The existing fixture files are unchanged. CSV-derived frames are synthetic
reconstructions, not new meter traffic. No new physical recordings are included.

To reproduce with the existing local dependencies, from the module root:

```sh
export JAVA_HOME=/path/to/jdk-21
export DATAEXPLORER_ROOT=/path/to/dataexplorer-4.0.7
scripts/test.sh all
```

The normal helper supports `DATAEXPLORER_JAR` for a matching prebuilt core and
`XVFB_RUN_BIN` for Xvfb. See the [build instructions](../../../README.md#build-and-tests).
The committed [validation receipt](validation.json) identifies the tested source,
core and output artifact. Generated binaries and full logs stay outside Git.

## Separate integrated beta 4 correction

[beta4-combined-fixes.patch](beta4-combined-fixes.patch) is the exact patch for
the separately tested DataExplorer 4.0.8 beta 4 Tasi plugin. It does not apply
to this standalone module. Its paths start with `Tasi/src/` and it applies with
zero fuzz to retained upstream source at Savannah commit
`a43484d58b4c106291a7140897e9a10388b45301`. Correspondence checks reproduced the
original beta 4 worker and importer classes from that retained source. No
claim is made about application to a newer upstream checkout.

The patch contains the two time corrections plus a beta-specific resource fix:
`GDE_MSGT4102` changes from `TA612C-rec` to `ta612c-rec` in the default, English
and German bundles, matching the existing Java guard. Deliberately repeated
setup exposed that mismatch; duplication in ordinary user workflows was not
established. Visible labels remain unchanged.

The resource files retain ISO-8859-1 bytes and their original final-newline
state, so the patch must not be transcoded as UTF-8. In a disposable matching
source checkout containing `Tasi/`, its application command is:

```sh
patch --batch --fuzz=0 -p1 < /path/to/beta4-combined-fixes.patch
```

The corrected beta 4 binary passed its full strict suite and API/SWT application
checks separately. At startup and after OSD opening, 20 additional setup calls
each retained one REC action and one listener. Its interval dialog opened and
was cancelled at both checkpoints; no download was submitted. REC chart, table
and duration checks also passed. Only two classes and three message bundles
differ from the original beta 4 JAR; the other 38 entries and DataExplorer core
are unchanged. That JAR is not the standalone artifact built by this module.

Neither validation establishes physical timing, USB recovery, long-run
stability, native mouse-path behavior or Windows/macOS behavior. The normal
Test 9 installation, original recordings and prepared beta 4 delivery package
were left unchanged. No maintainer email or binary release is part of this
repository update. Integration follows the repository's separate review and
project-owner decision in [GOVERNANCE.md](../../../../../../GOVERNANCE.md).
