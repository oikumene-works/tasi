# Three reproduced GUI findings in beta 3

These observations concern the build identified in the [test report](README.md).
Codex operated DataExplorer, inspected native state and saved files, and wrote
this report. The operator handled the physical meter and probes and personally
clicked the REC menu in the final menu test. The [source analysis](SOURCE_ANALYSIS.md)
is separate, so the reproductions can be assessed without its explanations.

## REC menu does not open after an OSD is opened

**Effect:** the REC interval dialog becomes inaccessible in the same application
process even though its import menu item remains enabled. No error is displayed.

Reproduced sequence:

1. Start DataExplorer with TA612C selected and acquisition stopped.
2. Select **File > Import > Download TA612C REC memory... / Cancel REC**.
   The interval dialog opens with a blank interval field.
3. Cancel the dialog. Open `2026-10-16-REC-T2-T4-beta3.osd` from the evidence
   archive through the native File > Open action.
4. Select the same REC menu item again.

Expected: the same interval dialog opens. Observed: no interval dialog or alert;
the saved REC graph remains visible. The native UI inspection found the menu
enabled and serial closed. The operator performed both menu selections and
reported the second failure; Codex verified the resulting UI. No download was
submitted in this sequence. Fresh startup restored access in the observed tests.
A cancellation-only experiment was not performed.

Dialog after fresh startup:

![Fresh REC interval dialog](images/final-round-rec-menu-fresh-dialog.png)

Application after the unsuccessful post-OSD menu selection:

![Saved REC graph remains visible](images/final-round-rec-menu-after-osd.png)

The second static image alone cannot prove the menu click occurred; that part
comes from the operator's report and subsequent Codex UI inspection.

## Table headers do not follow the active probes

**Effect:** correct values can appear beneath another probe's name, and a valid
probe may have no visible column. This can misidentify a measurement when reading
the table. The checked OSD records retain the correct physical channel mapping.

Live reproduction, also repeated after restarting DataExplorer:

1. Connect only T2 and T4; leave T1 and T3 empty.
2. Open `2026-10-16-T1-beta3.osd` and select Table, showing Time/T1.
3. Select File > New, start TA612C live acquisition, then inspect Table.
4. Stop and save. Open that saved OSD through File > Open and inspect Table again.

Expected: the new live table identifies T2 and T4. Observed before saving:
Time/T1 headers remain, T2 values appear under T1, and no T4 column is present.
The native reopened table correctly identifies T2/T4, with matching values
and displayed times. The repeated test's saved output is
`2026-10-16-restart-retest-beta3.osd`.

![Stale T1 header during the restarted T2/T4 trial](images/restart-retest-live-stale-table.png)

![Correct T2 and T4 headers after native reopen](images/restart-retest-reopened-table.png)

Related reproduced cases:

- A fresh T2/T4 REC import retained Time/T1/T2/T3/T4 headers. T2 values appeared
  under T1 and T4 values under T2; the remaining columns were empty. Reopen
  restored Time/T2/T4. Use the archive's REC file to inspect the stored mapping;
  reproducing the pre-save state requires the native REC import with a known
  recording interval (5 seconds in this test).
- During the all-probes-absent test, the new T3-only segment retained the older
  table labels, displaying T3 values under T1. All four probes together appeared
  correctly because the four existing headers matched that configuration.

![REC values beneath the old four-probe headers](images/rec-test-before-save-table.png)

![REC table after reopening the saved OSD](images/rec-test-reopened-table.png)

The supplied table JSON preserves the actual headers and rows, including the
misleading captures. Codex's comparisons do not relabel columns to make them
pass. Some captured windows have narrow time columns; the JSON retains their
full displayed text. Images are unchanged captures, not reconstructed tables.

## A later segment has a line before its first stored sample

**Effect:** a graph shows a temperature over an earlier period for which that
segment has no recorded data. This can suggest continued measurement during
probe absence. The extra line is not present as points in the OSD payload.

Reproduction using the saved file, without a connected meter:

1. Open `2026-10-16-all-probes-absent-T3-return-beta3.osd`.
2. Select record set **3) live acquisition T3** in the normal, unzoomed Graphic
   view.
3. Compare the left part of the curve with the first row in Table.

Expected: the curve starts with the first stored T3 sample at 53.8227 s,
22.6 °C. Observed: a horizontal 22.6 °C line extends from zero to that first
sample, along the lower plot border in this capture. The native table begins at
53.822 s (millisecond display); the OSD retains 53.8227 s. The segment contains
51 samples and no earlier point.

![T3 curve extends to zero before its first sample](images/all-probes-absent-reopened-T3-graph.png)

The same behavior appeared in the second and third sets of
`2026-10-16-T4-disconnect-reconnect-beta3.osd`, whose first stored times are
21.4947 s and 35.3271 s. Each segment retains its position on the acquisition's
shared elapsed-time axis. Zoom, scope, comparison and GPS views were not tested
for this finding.

## Supporting interruption result

The USB test is included as a successful recovery observation, not a fourth GUI
finding. The operator reported simultaneous meter power loss on unplugging USB.
DataExplorer retained the collected T3 values and displayed this notice:

![Connection loss notice preserving collected data](images/usb-interruption-after-unplug-window-2.png)

After USB and power were restored, an explicit new Start worked in the same
application process. The [report](README.md) records the scope and sample counts.
