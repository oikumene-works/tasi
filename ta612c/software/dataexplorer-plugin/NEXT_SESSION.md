# TA612C project handoff

Updated by Codex on 2026-11-02 for the authorized repository update.

## Current state and gate

The standalone DataExplorer 4.0.7 plugin now uses a separate relative-zero time
origin for each live/REC segment. Live epochs preserve the acquisition timeline;
REC keeps unknown-date epoch zero and persists inferred recording offsets.
The complete device-free suite passed. No new physical validation is claimed.

Branch: `codex/segment-time-corrections`, based on `main` at
`371be9bf8c41e424cec507b12bfd564f0f5363d4`. The operator authorized updating this
repository with the corrections. The change is prepared for the separate review
and project-owner integration decision required by
[GOVERNANCE.md](../../../GOVERNANCE.md). No self-review is presented as independent
review. The next step is review of the published change, then an integration
decision; no hardware test or background follow-up is running.

## Evidence and scope

- [Current correction and validation](docs/testing/2026-11-02-time-model/README.md)
  owns the behavior, reproducible test command, limits and source hashes.
- The same record includes the separately applicable integrated beta 4 patch.
  Its localized menu-marker defect is absent from the standalone source; the
  standalone menu regression has been strengthened.
- [Historical beta 3 report](docs/testing/2026-10-16-beta3/README.md) retains its
  observations. The curve-origin interpretation is explicitly corrected in its
  source annex; original recordings and values were not rewritten.
- `VALIDATION.md` and `RELEASE.md` retain the earlier standalone physical
  baseline. The prepared beta 4 binary/email delivery is a separate operator
  review item, not a repository release or a sent maintainer message.

Codex implemented, adapted, tested and documented this change at the operator's
request. Independent review and maintainer acceptance remain unestablished.

## Boundaries and reproduction

Use JDK 21, Java 19 target and Ant; run `scripts/test.sh all` with the local
DataExplorer 4.0.7 dependencies. See [build instructions](README.md#build-and-tests).
Generated files and raw logs remain ignored; fixture files are unchanged.

Preserve read-only REC, exclusive live/REC ownership, atomic failed/cancelled
imports, empty/hidden open probes and legacy OSD compatibility. The explicit
interval is inferred provenance, epoch zero is unknown time, and 1.5-second
silence is not proof of complete REC memory. No new meter commands, core
changes, normal-installation replacement or old-file migration are included.

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
