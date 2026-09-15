# TA612C project handoff

Updated 2026-09-15 after the standalone project migration. Communicate with the
user in Finnish; keep repository code and documentation in English.

## Accepted baseline

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

## Possible next slice

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
