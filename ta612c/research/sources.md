# Sources and provenance

## Physical device

TASI TA612C, firmware 3.50, accessed on Linux over a CH340 USB serial adapter at 9600 8N1.

Physically verified in the current project:

- device info request (`0x00`)
- live data request (`0x01`)
- REC download request (`0x02`)
- one explicitly authorized send of the `0x04` common-layer `DATA_ERASE`
  candidate; it returned no bytes and the immediate REC readback was unchanged
- HI alarm activation and front-panel behaviour
- open-probe value of 2800.0 °C

The `0x04` observation is a bounded no-effect result, not confirmation that the
command is safe or that it can erase TA612C memory. See
[`data-erase-trial-2026-09-15.md`](data-erase-trial-2026-09-15.md).

## TASI protocol document

Public document titled **TA Series Communication Protocols**, revision dated 2022-07-25. The public copy used during research described TA612-family commands and serial framing. The example firmware in that document was older than the physical device firmware 3.50.

Public URL observed during research:

`https://www.mikrocontroller.net/attachment/668956/TA_Series_Communication_Protocols.pdf`

The document itself is not redistributed in this repository.

## TASI EnvironmentalTester 1.21

Static analysis target:

- executable size: 2,326,016 bytes
- SHA-256: `29618357e560231377eee00c1486ef06f57637e13c4d348d06024cd05dda4876`
- embedded source path contains `TA_Env_V1.21_PRD`

Vendor binary is not redistributed here.

## TASI EnvironmentalTester 1.22

Static analysis target:

- executable size: 2,783,744 bytes
- SHA-256: `ac9a9d3415b9de12958b794f49e1d9e351a38979eef4b368ce4975a2fb123bad`
- file timestamp in obtained package: 2026-09-04 12:57:16 UTC
- `config.ini` includes `[Debug] EnableCommunicationLog=true`

Vendor binary is not redistributed here.

## Artisan

Third-party open-source implementation in `artisan-roaster-scope/artisan`. TA612C support appeared in the 2025 development history and currently uses the live-data command for T1..T4 acquisition.

Artisan code is referenced for comparison; it is not copied into this repository.
