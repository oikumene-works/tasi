# TA612C project handoff

Updated 2026-10-16 for publication of the integrated beta test material.
Communicate with the user in Finnish; keep repository code and documentation
in English.

## Current checkpoint: beta test report prepared for review

The operator authorized one PR to `oikumene-works/tasi` containing the beta 3
test report, selected evidence, editable test helpers and a separate source
annex. Publication branch: `codex/beta3-test-report`. Base at preparation:
`1f27ba5bdbe87fd83643ad06dd5145d52cc53279`.

The [report](docs/testing/2026-10-16-beta3/README.md) owns the observations,
attribution and limits. Its [evidence index](docs/testing/2026-10-16-beta3/EVIDENCE.md)
links the separate GitHub attachment; physical OSD files are not committed.
The [source annex](docs/testing/2026-10-16-beta3/SOURCE_ANALYSIS.md) records the
three causes without fixes. Codex performed the software operations, data and
source analysis, and drafting; the operator performed the physical actions and
the final manual REC menu checks. No independent source review is claimed.

Next allowed step is review of this material. Integration still needs the
repository's separate review and project-owner decision. No merge, new hardware
test, code fix or separate maintainer message is authorized by this publication.
The [helpers](scripts/gui_test/README.md) are retained working aids for future
DataExplorer work, not a frozen interface. They may be adapted as needed.

## Historical accepted standalone baseline

The bounded partial-probe slice is accepted. Short physical trials covered T1
only and T2+T4, including live acquisition, meter REC, sparse OSD restoration,
advancing table values and the live graph. The complete device-free regression
suite passed 57,639 assertions. See `VALIDATION.md` and `RELEASE.md`.

The existing REC download remains read-only. Its successful end is inferred from
1.5 seconds without bytes and does not prove that the meter's entire memory was
received. REC time remains inferred from the explicit user-supplied interval;
absolute recording time is unknown and epoch zero is only a placeholder.

## Non-negotiable boundaries

- Do not add erase, recording-start, configuration-write or other new meter
  commands without separate protocol evidence and explicit user scope.
- A malformed, cancelled or failed REC import must publish no partial dataset.
- Never describe silence-terminated REC as certainly complete.
- Open probes store no zero, sentinel or previous-value replacement.
- Keep old four-column live OSD and sparse REC/live OSD restoration working.
- Keep `local/`, downloaded dependencies, generated builds and physical meter
  evidence out of Git.

## Build and validation

Use JDK 21. The helper automatically uses the ignored
`local/deps/dataexplorer-4.0.7` tree when present and supports:

```sh
scripts/test.sh package
scripts/test.sh protocol
scripts/test.sh all
```

The full test requires Xvfb. The ignored `local/tools/xvfb` copy is detected
automatically. Set `DATAEXPLORER_ROOT` or `XVFB_RUN_BIN` only to override these
local defaults.

## Earlier proposed next slice (not active)

No further partial-probe work is required. A clean next slice would be visible
REC-download progress plus an optional repeated read-only verification mode:

- show received frames, sample groups, bytes and elapsed time, not a fabricated
  percentage;
- keep ordinary Download as one transfer;
- make a separate user-selected Download and verify mode perform two transfers;
- compare both structure and a content digest;
- report only that two consecutive reads matched, never that memory completeness
  was proven.

Lock the UI semantics before implementing that feature. Do not combine it with
CSV import, live segmentation changes or reverse engineering of time metadata.
