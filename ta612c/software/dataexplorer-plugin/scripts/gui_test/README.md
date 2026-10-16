# Native DataExplorer test helpers

Retained at the operator's request on 2026-10-16. Codex wrote or extracted these
helpers while operating DataExplorer 4.0.8 beta 3 on Linux and checking physical
TA612C recordings. The operator connected, warmed and disconnected probes and
USB, and supplied meter readings. These helpers do not replace those human
observations, provide sensor calibration, or establish REC download completeness.

The [beta 3 test report](../../docs/testing/2026-10-16-beta3/README.md) owns
the published results. `NEXT_SESSION.md` owns the continuation point.
Profiles and original recordings remain under ignored `local/` paths; selected
recordings are available in the report's separately attached evidence archive.
These scripts are retained source files; preserving them does not start a test.
They are working aids retained for future DataExplorer work, not a frozen or
authoritative interface. Adapt, extend or replace them as later work requires;
keep the evidence needed to understand earlier results.

## Contents

- `ui.py`: PID-scoped AT-SPI inspection, named native actions, tab/record-set
  navigation, measurement-table capture and native OSD opening.
- `stop_deadline.py`: one scheduled native **Stop gathering** action. It checks
  the process start ticks and whether that process still holds a USB serial
  descriptor. It never starts a measurement or opens the serial port itself.
- `inspect_osd.py`: read-only decoding of the variable-time sparse TA612C OSD
  layout used in these tests, including presence flags, temperatures, comments
  and timestamps. Its assertions intentionally reject unsupported layouts.
- `verify_table.py`: comparison of decoded data with saved native table JSON,
  checking physical channel headers, every temperature and displayed time.

## Use an identified isolated trial

The UI helpers require Python 3 with PyGObject/AT-SPI 2, a running desktop
accessibility session, and the native DataExplorer application. File chooser
handling also requires X11 and XTest libraries. The tested launcher enables
accessibility and uses the X11 GTK backend. Native Wayland operation and other
DataExplorer versions have not been verified.

Run ordinary Python, without `-O` or `-OO`: the retained helpers rely on
assertions, including checks around native actions.

Set `TA612C_TEST_ROOT` explicitly to the isolated trial directory. It must
contain the reviewed `process.json` with the intended application PID. The
helpers do not discover a personal profile or launch DataExplorer automatically.
For example, from the plugin module root, this only reads the selected UI:

```sh
TA612C_TEST_ROOT="$PWD/local/beta-4.0.8-b3-2026-10-16" \
PYTHONPATH="$PWD/scripts/gui_test" PYTHONDONTWRITEBYTECODE=1 \
python3 -c 'import json; from ui import application, compact_ui; print(json.dumps(compact_ui(application()), indent=2))'
```

Inspect the process identity, selected TA612C device, serial port, current file
and modal dialogs before calling a mutating action. Hardware acquisition still
requires the current task's authorization and operator readiness. Use only the
accepted live/REC implementation; do not add raw meter commands or automatic
start/retry behavior. Only one transport owner may exist at a time.

The deadline script takes a trial-relative record filename as its one argument.
That JSON must contain `process_id`, `process_start_ticks`, `deadline_unix` and
a unique trial-relative `deadline_evidence` output path. Start it only for the
intended bounded acquisition, record its PID, and cancel it when that acquisition
ends early. Do not leave it running across later acquisitions in the same app
process. Inspect its result and actual serial/UI state: a deadline is not proof
that cleanup succeeded. Preserve existing evidence rather than reusing output
paths. The deadline must also fit the operator's current work/rest boundaries.

## Inspect and compare saved data

These two commands do not use the meter or GUI:

```sh
python3 scripts/gui_test/inspect_osd.py recording.osd decoded.json
python3 scripts/gui_test/verify_table.py decoded.json table-evidence.json comparison.json
```

Use a new output path. The decoder retains the original 0.1 ms timestamp units
and milli-degree temperature values. The comparison allows only the native
table's millisecond time truncation; temperatures must match exactly. A wider
range may make the GUI round temperatures more coarsely, in which case a failure
needs inspection rather than silently relaxing the comparison. The report says
whether all decoded record sets or only the supplied subset were covered.

Supported table evidence is either a single `table`/`reopened_table` object or
`records` containing one-based `record_index` and `table` entries. A table has
`headers` and `rows`. Do not remap a misleading header to make a failed
comparison pass: retain the display observation separately.

## Lessons verified during this round

- Normalize surrounding whitespace in combo-box text. The live set name may
  contain a trailing space, and temperature/time headers include units.
- Select the measurement table by its **Time** header and visible state.
  Choosing the table with the most rows can select Statistics when a measurement
  segment has only one row. The corrected selector passed that one-row case.
- Focus the file chooser's **Files** table before Ctrl+L, check the entered
  absolute path, allow the GTK update to settle, activate the actual **Open**
  button, and verify the resulting title. An earlier attempt opened the old
  selected file; that result was excluded. The corrected helper succeeded in
  the interrupted and recovery recording checks.
- Prefer table capture after acquisition stops. A live accessibility tree can
  change while it is being traversed; a stale tool read is not an app defect.
- AT-SPI methods can have name collisions. Use `Atspi.Text.get_text` explicitly;
  native named actions use `get_action_iface().do_action(0)`.
- A native dialog titled **Warning** can be the normal retained-data stop
  notice. Record its actual text and resulting state.
- The user reported meter power loss when USB was unplugged. That test supports
  combined USB/power-loss recovery; it does not isolate data-link-only loss.

The known table-header and graph-left-extension observations are in the
[published findings](../../docs/testing/2026-10-16-beta3/GUI_FINDINGS.md). These helpers preserve evidence; they do not fix or explain those bugs.
No screenshot skill code or personal profile is copied into this directory.
