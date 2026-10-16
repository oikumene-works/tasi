# Annex: source explanations for the four beta 3 findings

Prepared by Codex on 2026-10-16. This optional technical annex accompanies the
[physical test report](README.md) and [reproduction steps](GUI_FINDINGS.md).
It records causes without proposing fixes or assigning work to the maintainer.

Source tracing explains four reproduced behaviors in DataExplorer 4.0.8
beta 3: the REC action belongs to a different device instance from the active
one, table rows refresh without rebuilding their headers, and curve drawing
extends the first value to the left boundary before the first stored sample.
The later saved-file follow-up found that menu/keyboard tab navigation changes
selection without running the listener that refreshes Graphic.
The earlier OSD checks established correct stored values and timestamps for the
affected recordings; the table and curve findings concern their presentation.

Codex retrieved and examined the source, traced the relevant control flow,
compared compiled classes with the distributed beta and wrote this analysis.
During the earlier physical tests, the operator handled the meter, probes and
USB, supplied readings and manually exercised the REC menu. Codex operated the
other application steps and checked UI and file evidence. No independent human
source review is claimed.

## Source correspondence to the tested beta

The upstream snapshot is commit
`a43484d58b4c106291a7140897e9a10388b45301`, dated 2026-10-14, retrieved from
the [Savannah repository](https://git.savannah.nongnu.org/git/dataexplorer.git).
The source is identified by commit and file hashes in the published evidence.
All source paths and line numbers below refer to that snapshot.

The distributed core manifest records build time `2026-10-15 12:18:13`, and
the Tasi plugin records `2026-10-15 12:18:32` (manifest time zones unspecified).
The tested JAR hashes are:

- `DataExplorer.jar`: `637a24be90e34c8e081d229a0e55d18cc92340d45ebe43130a9fb2b4fb1617a4`
- `Tasi.jar`: `6738ecd6a704b97c346a1be4052851d7bfed45a1efbc9c318c4c7e7bc07d0829`

For correspondence checking, Codex compiled 14 relevant, unmodified Java files
with JDK 21.0.12.1, debug metadata and the Java 19 target, against the shipped
dependencies. **All 171 resulting class files, including nested classes, match
the distributed classes byte for byte.** These include the principal classes
in each causal chain: TA612C, TA612CRecImport, TA612CRecDialog, DataExplorer,
DeviceSelectionDialog, DeviceConfiguration, FileHandler, MenuBar,
DataTableWindow, RecordSet, Channel, CurveUtils, Record and TimeSteps.
The three Tasi message bundles also match byte for byte.

This establishes correspondence for those classes and resources; it does not
claim a reproducible build of the entire distribution. The first three findings
combine the earlier runtime observations with source and bytecode analysis.
No debugger was attached to their live acquisition process. The fourth finding
adds saved-file UI runs and a read-only agent that captured SWT/model state in
disposable processes. No new physical acquisition was performed for this analysis.

[Binary identities][binary-id], [pinned source identities][source-id],
[comparison command and result][compile], [class comparisons][classes] and
[resource comparisons][resources] retain the verification details.

## REC menu action uses a device instance that is not active

**Observed behavior.** In a fresh process the operator opened File > Import >
Download TA612C REC memory... / Cancel REC. Codex verified the interval dialog
and cancelled it. After Codex opened the saved T2/T4 REC OSD, the operator
selected the same menu action again. It remained enabled but opened neither a
dialog nor an error message. No download was submitted in this sequence.
The [manual test record][manual-rec] and [post-OSD UI evidence][rec-after]
preserve the steps and results.

**Source cause.** Opening an OSD for the already selected, single-channel
TA612C creates a new menu owner but leaves the old instance active:

1. `FileHandler.java`, `openOsdFile(String)`, lines 300–323:
   the special branch for multiple outlet channels does not apply to this
   one-channel device. The `else` calls `setupDevice(...)`, even when the
   file's device name already matches the selected device.
2. `DeviceSelectionDialog.java`, `setupDevice()`,
   lines 1022–1045, cleans the device menus and obtains a new device instance.
   Its `getInstanceOfDevice()` delegates to
   `DeviceConfiguration.java`, `defineInstanceOfDevice()`,
   lines 549–566, which invokes a constructor and returns a new object.
3. `TA612C.java`, lines 85–116: that new object's constructor builds
   the REC item. The selection listener captures this new TA612C object.
4. `DataExplorer.java`, `setActiveDevice(IDevice)`,
   lines 1422–1426, replaces the analyzer's active device only if it is absent
   or its **name differs**. A new TA612C with the same name therefore does not
   replace the previously active TA612C object.
5. `TA612C.java`, `downloadRec()`, line 127, compares object identity:
   `if (application.getActiveDevice() != this) return;`. The new menu owner's
   object differs from the retained active object. Execution returns before
   the dialog call at line 133, without a message.

At startup the menu owner and active device are the same object. After the OSD
setup they are different objects with the same device name. The core removes
the old menu items in `MenuBar.java`, lines 1391–1398. The newly built
listener is bound to an instance that the core did not adopt as active.

This also explains why live Start can still work: its menu callback queries
the active device at the time of the click (`MenuBar.java`,
lines 520–525). The REC callback instead uses its captured owner.

Cancelling the first dialog is part of the recorded reproduction, but the code
does not leave a cancellation latch: `TA612CRecDialog.java`,
lines 65–78, returns the initially null result after disposing the dialog;
`downloadRec()` returns at line 135 before assigning a downloader. The
same-name setup and identity mismatch provide the causal explanation without
requiring a preceding cancellation. A cancellation-only GUI experiment was not
performed.

**Separate existing observation.** The identifier check at TA612C line 110 uses
`ta612c-rec`, while line 114 stores the localized `TA612C-rec`. All three
distributed bundles retain this mismatch. It can defeat the duplicate guard
when menu setup is repeated without cleanup, but duplicate entries were not
reproduced. It is not the cause of the observed no-op after OSD opening, whose
setup path explicitly cleans the menu. This was noted separately before this test round and is confirmed present in beta 3.

## Table values refresh under headers from an earlier probe set

**Observed behavior.** Opening a T1 OSD, selecting File > New and acquiring
T2/T4 retained Time/T1 headers: T2 values appeared under T1 and there was no T4
column. This reproduced in the same process and after restarting. A fresh REC
import instead retained the initial four-probe headers, putting T2 values under
T1 and T4 values under T2. A later T3-only live segment likewise displayed its
values under T1. Reopening the saved OSD restored the correct headers, and the
stored physical channels and values were correct. See the
[restart reproduction][table-restart], [REC table evidence][rec-table] and
[all-probes-absent verification][absent-check].

**Source cause.** The plugin changes the active segment and visible records but
requests a data refresh that deliberately does not rebuild the table header.
Header creation and row population then use different generations of state.

- `TA612C.java`, lines 251–271, selects a new live record set,
  configures its probe mask, updates the visible records and calls
  `updateAllTabs(false)`. The final live update at line 289 also passes false.
  The REC publication path at lines 159–165 similarly selects the imported
  set and passes false to both UI update calls.
- `Channel.java`, `setActiveRecordSet(String)`, lines 578–585,
  changes the active record reference without rebuilding table columns.
- `DataExplorer.java`, lines 2064–2079, forwards the force flag
  to `updateDataTable`. In `updateDataTable`, lines 996–1017, `setHeader()`
  executes only when `forceClean` is true; false updates the row count and
  scrolling position. With no active set, lines 1022–1035 clean rows only.
- `DataTableWindow.java`, `cleanTable()`, lines 543–546, preserves
  columns. This accounts for T1 headers surviving File > New. Fresh device
  setup, by contrast, builds headers from all configured measurements when
  there is no active set (`setHeader()`, lines 517–529).
- The SWT `SetData` listener at DataTableWindow lines 358–367 obtains row
  values from the **current** active set. `RecordSet.java`,
  lines 555–565, delegates value formatting to the device.
  `TA612C.java`, lines 438–443, writes the currently visible and
  displayable records consecutively from column 1 onward.

The tested profile has `is_partial_data_table=true`. The current record list
therefore contains only the selected, displayable probes
(`RecordSet.java`, lines 702–717). A T2/T4 row consequently supplies
time, T2, T4; it does not replace an existing T1 column label or create a missing
T4 column. With initial four-probe headers, the same row appears under the first
two old labels. Four-probe acquisition can look correct because its values
happen to match those initial headers.

The native OSD reopening path reaches `Channels.switchChannel()`
(`OsdReaderWriter.java`, lines 458–465), which updates the visible record
list and calls `updateAllTabs(true)` (`Channels.java`,
lines 217–227). Header reconstruction then uses the current records. This
explains the observed recovery on reopening without any change to stored data.

## Curve drawing adds a line before the first stored sample

**Observed behavior.** After T4 removal and return, the second and third live
segments begin at 21.4947 s and 35.3271 s in their stored data, but their graphs
extend the first value back to zero. After removing all probes and reconnecting
only T3, the last segment begins at **53.8227 s and 22.6 degrees C**, yet its
graph includes a horizontal 22.6-degree line from zero. The native table and
OSD payload have no earlier points in that segment. See the
[transition verification][transition-check], [T3 verification][absent-check]
and [saved T3 graph][absent-graph].

**Source cause.** In the normal, unzoomed, non-GPS view, the core explicitly
joins a calculated left-edge endpoint to the first actual measurement point:

1. `CurveUtils.java`, `drawCurve()`, line 253, initializes `oldPoint`
   with `record.getDisplayEndPoint(0)` instead of using the first sample's
   coordinate.
2. `Record.java`, lines 1739–1749 and 1780–1782, calculates the
   endpoint's value at display time zero. In this view
   `getDrawTimeOffset_ms()` returns zero (lines 1542–1554).
3. `TimeSteps.java`, `findBoundingIndexes(0)`, lines 301–326,
   returns indices `[0, 0]`, even when sample zero has a positive timestamp.
   Record lines 1806–1809 therefore use the first sample's temperature for
   that left-edge endpoint.
4. The ordinary curve loop at `CurveUtils.java`, lines 299–304,
   starts with `j = 0`. `getDisplayPoint(0, ...)` uses the first sample's
   **actual timestamp** (`Record.java`, lines 1635–1638).
   `gc.drawLine(...)` joins the two coordinates. Their temperatures match,
   producing the horizontal line from time zero to the first recorded time.

For the T3 example, those endpoints represent `(0 s, 22.6 C)` and
`(53.8227 s, 22.6 C)`. The first coordinate is manufactured for drawing; it is
not inserted into the recording. The line can overlap the lower plot border,
as in the retained screenshot.

The plugin preserves acquisition-relative elapsed times across segments
(`TA612C.java`, lines 312–340), so a later segment legitimately starts
after zero. The generic core drawing path assumes a value is available at the
left boundary. That combination explains the display extension. This analysis
does not establish behavior in zoom, scope, comparison or GPS views.

## Menu and keyboard tab navigation leave the previous Graphic cache visible

**Observed behavior.** After opening a second saved OSD from Table, returning
to Graphic through the View menu or Ctrl+PageUp retained the first file's curve
image and selector. Pointer tab selection refreshed both. The second file's
196 rows, four probe arrays and timestamps were already loaded and did not
change during recovery. The [reproduction][graphic-refresh] uses the unchanged
beta and two existing archive files.

**Source cause.** The menu route changes selection without executing the
application's tab-selection refresh:

1. `MenuBar.java`, lines 968–990, connects previous/next tabulator and the
   Ctrl+PageUp/PageDown accelerators to `switchPreviousTabulator()` and
   `switchNextTabulator()`.
2. `DataExplorer.java`, lines 3260–3289, changes the selected index by calling
   `CTabFolder.setSelection(...)`. Neither switch method invokes the Selection
   listener or explicitly refreshes the selected view.
3. In the distributed SWT jar, public `CTabFolder.setSelection(int)` does not
   notify Selection listeners. Its separate internal overload with a notify
   argument performs that notification. This distinction was checked in the
   shipped bytecode, not assumed from the method name.
4. `DataExplorer.java`, lines 753–802, places view refresh and
   `tabSelectedIndex` bookkeeping in its Selection listener. Selecting Graphic
   through that listener calls `updateGraphicsWindow()`.
5. `updateGraphicsWindow(boolean)`, lines 2095–2131, redraws the normal graph
   only when Graphic is selected. Opening another OSD while Table is selected
   therefore leaves the hidden graph image unchanged. Returning without the
   Selection listener can expose that old image and selector.

The twelve [snapshot extracts][graphic-refresh-evidence] show the missing
listener path directly: after Ctrl+PageUp returned from Table to Graphic,
the actual selected index was 0 while `tabSelectedIndex` remained 2. Pointer
selection restored it to 0. The cached image bytes remained equal to the first
file until pointer recovery; the active values and timestamps already belonged
to the second file.

`GraphicsWindow.redrawGraphics(true)` regenerates the curve image, captions and
selector. `GraphicsComposite.doRedrawGraphics()` draws the current active record
set into its cached image; the ordinary paint path can reuse that image.
The working pointer route reaches the full refresh. The graph title/comment
can therefore show current metadata while the unreplaced curve image still
represents the earlier file.

The earlier 171-class comparison includes DataExplorer and MenuBar. The
additional GraphicsWindow/GraphicsComposite source identities and the SWT jar
hash are retained with this follow-up; their relevant distributed bytecode was
inspected without rebuilding or changing the application. Native Ctrl+PageDown
return to Graphic was not separately tested, although its menu binding reaches
the same next-tab method. No claim is made for other platforms or graph modes.

## Scope and source retrieval

The explanations are Codex source analysis supported by the scoped class
comparison, distributed bytecode and runtime evidence described above. No
source fix or additional acquisition was performed. The fourth finding's
read-only agent attachment is distinct from the original source-only analysis.
This annex is published with the test
package at the operator's request; no separate maintainer message has been sent.
The case-sensitive menu identifier observation above remains a source-only note.

Source paths and line numbers refer to the pinned upstream commit, not the
standalone 4.0.7 plugin in this repository. Retrieve the source in a separate
directory (or use an existing upstream checkout):

```sh
git clone https://git.savannah.nongnu.org/git/dataexplorer.git dataexplorer-source
git -C dataexplorer-source show a43484d58b4c106291a7140897e9a10388b45301:Tasi/src/gde/device/tasi/TA612C.java
```

The public Git web viewer was unavailable during packaging, so source references
use the exact commit, paths and line numbers rather than unverified web links.
The recorded compilation command uses symbolic directory placeholders; it needs
the matching distributed dependencies. Its 171 matching classes establish only
the stated subset, not an independently reproduced whole distribution.

### Pinned source paths

- `DataExplorer/src/gde/data/Channel.java`
- `DataExplorer/src/gde/data/Channels.java`
- `DataExplorer/src/gde/data/Record.java`
- `DataExplorer/src/gde/data/RecordSet.java`
- `DataExplorer/src/gde/data/TimeSteps.java`
- `DataExplorer/src/gde/device/DeviceConfiguration.java`
- `DataExplorer/src/gde/io/FileHandler.java`
- `DataExplorer/src/gde/io/OsdReaderWriter.java`
- `DataExplorer/src/gde/ui/DataExplorer.java`
- `DataExplorer/src/gde/ui/dialog/DeviceSelectionDialog.java`
- `DataExplorer/src/gde/ui/menu/MenuBar.java`
- `DataExplorer/src/gde/ui/tab/DataTableWindow.java`
- `DataExplorer/src/gde/ui/tab/GraphicsWindow.java`
- `DataExplorer/src/gde/ui/tab/GraphicsComposite.java`
- `DataExplorer/src/gde/utils/CurveUtils.java`
- `Tasi/src/gde/device/tasi/TA612C.java`
- `Tasi/src/gde/device/tasi/TA612CRecDialog.java`

[binary-id]: verification/binary-identity.json
[source-id]: verification/source-identity.json
[compile]: verification/comparison-compile.json
[classes]: verification/class-comparison.json
[resources]: verification/resource-comparison.json
[manual-rec]: GUI_FINDINGS.md#rec-menu-does-not-open-after-an-osd-is-opened
[rec-after]: images/final-round-rec-menu-after-osd.png
[table-restart]: GUI_FINDINGS.md#table-headers-do-not-follow-the-active-probes
[rec-table]: images/rec-test-before-save-table.png
[absent-check]: verification/recordings.json
[absent-graph]: images/all-probes-absent-reopened-T3-graph.png
[transition-check]: verification/offline-recheck.json
[graphic-refresh]: GUI_FINDINGS.md#graphic-retains-the-previous-file-after-returning-from-table
[graphic-refresh-evidence]: verification/graphic-refresh.json
