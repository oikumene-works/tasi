# DataExplorer plugin import provenance

This path was originally reserved for the TA612C DataExplorer integration while
the plugin was developed in a separate working tree. The original placeholder
entered the TASI history in commit `01dad45`; its rename history is retained by
this file.

The standalone repository was imported here with `git subtree` without
squashing. The imported history ends at commit `7323738`, and the accepted
partial-probe baseline remains identified by the annotated tag
`partial-probes-accepted-2026-09-15` at commit `6981734`.

The import preserves the plugin's source, tests, fixtures, release notes,
license and standalone migration record under this directory. It does not
replace or combine the separate Python logger, protocol model or research
material elsewhere under `ta612c/`.

The tracked CSV and OSD fixtures originate from physical user measurements.
They are required regression inputs and must receive an explicit public-release
review before the integration branch is published.
