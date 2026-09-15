# TASI TA612C

Open research, software and integration work for the TASI TA612C four-channel thermocouple thermometer.

## Current state

The USB interface is usable on Linux as a 9600 baud, 8N1 serial connection through a CH340 USB-to-serial device. A physical TA612C with firmware 3.50 has been used to verify device-info, live-data and REC-download traffic.

The current public work is divided into:

- [`protocol/`](protocol/) — current protocol model and machine-readable facts
- [`software/logger/`](software/logger/) — self-contained Python logger/downloader
- [`software/dataexplorer-plugin/`](software/dataexplorer-plugin/) — standalone
  DataExplorer 4.0.7 plugin with live acquisition, read-only REC import, tests
  and release notes
- [`research/`](research/) — source-by-source reverse-engineering notes and uncertainty
- [`docs/`](docs/) — usage and safety notes
- [`examples/`](examples/) — future sanitized fixtures and example captures

## Important safety note

EnvironmentalTester 1.22 labels the common serial-layer command
`AA 55 04 03 06` as `DATA_ERASE`. The TA612C UI path does not appear to call it,
and one controlled physical send produced no response and left the immediate
REC readback unchanged. That does not establish safety or general
non-applicability. Treat it as potentially destructive and **do not send it as
a discovery probe**.

See [`docs/safety.md`](docs/safety.md).
