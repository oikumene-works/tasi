# Evidence archive and verification

Download the [TA612C beta 3 reproduction archive](https://github.com/user-attachments/files/33524184/tasi-dataexplorer-beta3-2026-10-16.zip)
(77,619 bytes). Archive SHA-256:

```text
b38cbd163d7a3a16691b062b32d4520f51e893ce642e5a4bbc9f0141fdb9430b
```

The ZIP contains 14 unchanged OSD files: all twelve beta outputs and two earlier
reference originals. It also contains selected native table captures, a README,
recording identities, offline verification results and `SHA256SUMS` for every
other member. The package has 31 files in one named top-level directory.
Recordings are an external GitHub attachment and are not committed to Git.

Codex assembled the archive from the retained test evidence. Headers and table
cells were copied unchanged; unrelated local paths, process details and profile
metadata were omitted from the selected JSON extracts. Each extract names and
hashes its original capture. OSD bytes and screenshots are unmodified. The
archive includes no application binaries, private email, download credentials
or personal profile. The [test report](README.md#who-did-what) explains the
operator's physical actions and Codex's contribution.

After extracting, run `sha256sum -c SHA256SUMS` inside the archive directory.
The archive README explains how to decode an OSD and compare its values with
the included native tables using the [editable helpers](../../../scripts/gui_test/README.md).
These offline commands do not access the meter. To reproduce the graph finding,
open the all-probes-absent file and select its third record set; no meter is
required. The table and REC-menu findings require the sequences in
[GUI_FINDINGS.md](GUI_FINDINGS.md), not merely opening a finished recording.

## Evidence in Git

| Material | Purpose |
|---|---|
| [Recording inventory](verification/recordings.json) | Every attached OSD's size/hash, record sets, present channels and first/last times |
| [Offline recheck](verification/offline-recheck.json) | Nine complete native-table comparisons, T1 row-only comparison, stale-header rejection and two legacy round-trip comparisons |
| [Screenshot identities](verification/screenshots.json) | SHA-256 and size of the eight original captures in `images/` |
| [Beta binary identity](verification/binary-identity.json) | Tested core and plugin manifests and SHA-256 |
| [Source identity](verification/source-identity.json) | Upstream commit and hashes for the relevant source files |
| [Compilation record](verification/comparison-compile.json) | Recorded successful comparison invocation with symbolic paths |
| [Class comparison](verification/class-comparison.json) | 171 compiled/distributed byte identities |
| [Resource comparison](verification/resource-comparison.json) | Three Tasi message-bundle identities |

The table comparisons cover 543 rows and 1,430 temperatures with captured
headers, plus 75 T1 rows/temperatures whose original JSON omitted headers.
The legacy payload comparisons cover 2,381 rows and 4,598 temperatures across
the two file entries; their first five sets overlap. They compare original and
resaved stored values exactly, not rounded GUI text. All twelve original beta
OSD files remained unchanged during packaging.

No full application test suite was run for this documentation/helper publication.
The Python helpers passed syntax checks and the applicable offline data checks;
their executable source is the retained tested version. Helpers remain ordinary
working code that can be adapted for future work, not a frozen interface.
Previously completed source/class checks are retained as evidence, not described
as a new application build or a new physical experiment.
