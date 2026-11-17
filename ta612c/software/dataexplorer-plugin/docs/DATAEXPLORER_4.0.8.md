# TA612C in the official DataExplorer 4.0.8 release

The official DataExplorer 4.0.8 release includes the TASI TA612C plugin.
The [upstream download page](https://www.nongnu.org/dataexplorer/download.html)
lists 4.0.8 as stable, and the
[release version file](https://download.savannah.nongnu.org/releases/dataexplorer/VersionRelease.txt)
dates it to 15 November 2026. The
[supported-device list](https://www.nongnu.org/dataexplorer/index.html#Introduction)
includes TA612C. These pages were checked on 17 November 2026, when Jyri shared
Winfried's release announcement.

At Jyri's request, Codex implemented and tested the preceding corrections and
prepared this notice. Winfried integrated the changes and built and released
DataExplorer. During physical testing, Jyri operated the meter and probes;
Codex operated the application and checked the stored data.

## Which version to use

Use the upstream DataExplorer distribution for the integrated plugin. This
repository retains its separate [standalone 4.0.7 module](../README.md), tests
and historical patches. Its sparse-storage behavior and compatibility rules
are not the specification for the integrated plugin. This notice does not
replace that module's source or provide a new standalone JAR.

## Storage change and earlier OSD recordings

During 4.0.8 integration, live and REC storage changed from sparse columns to
four physical columns in T1–T4 order. Missing probes have numeric zero
placeholders plus explicit presence flags and remain hidden. A connected probe
measuring 0 °C is still a valid measurement. A probe-availability change starts
a new record set. The new marker is `ta612c_probe_present`; the earlier
experimental marker was `ta612c_rec_present`.

The integrated reader tested in beta 8 explicitly rejected all 14 earlier-marked
examples exercised, directing users to the original, unmodified 4.0.8 beta 7
reader. That earlier reader had been verified against those files. Two unmarked
four-column live fixtures still opened in beta 8. Thus neither universal old-file
compatibility nor rejection of every older OSD is claimed.

Keep original recordings and the matching earlier reader. Use the standalone
4.0.7 plugin for its own recordings, or the original beta 7 for the earlier
integrated examples covered above. There is no automatic migration. Removing
a marker does not convert a payload and can make its columns misinterpretable.

REC still requires the recording interval supplied by the user. Its recording
date is unknown: epoch zero is a placeholder, while descriptions retain the
inferred segment offsets. A 1.5-second receive silence does not prove that the
entire meter memory was downloaded.

## Validation boundary

The original Linux beta 8 passed device-free regression and English/German
application checks on 7 November 2026. These covered live-table headings (F4),
immediate REC table refresh, missing-probe storage, synchronized scales with T1
absent, genuine-zero averages and OSD round trips. F4 and scale synchronization
were separate observations.

A short physical T1/T3 test, including T1 removal and return, preserved 157 live
rows and 19 REC groups through OSD save/reopen. REC used the operator-confirmed
10-second interval. The controller needed recovery from a polling stall; its
four-minute safeguard stopped and saved live before REC and file checks
continued. No product binaries were replaced.

These are beta 8 results, not a rerun against the final 4.0.8 release. The final
release binaries have not been downloaded, compared or executed in this notice
update. Long-run stability, USB-loss recovery, Windows/macOS behavior and REC
transfer completeness were not established by the beta 8 round.

Earlier public context: [beta 3 observations](testing/2026-10-16-beta3/README.md)
and [segment-time corrections](testing/2026-11-02-time-model/README.md).
Those reports remain evidence about their named versions.
