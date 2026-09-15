# TASI TA612C

Open research, software and integration work for the TASI TA612C four-channel thermocouple thermometer.

## Current state

The USB interface is usable on Linux as a 9600 baud, 8N1 serial connection through a CH340 USB-to-serial device. A physical TA612C with firmware 3.50 has been used to verify device-info, live-data and REC-download traffic.

The current public work is divided into:

- [`protocol/`](protocol/) — current protocol model and machine-readable facts
- [`software/logger/`](software/logger/) — self-contained Python logger/downloader
- [`software/dataexplorer-plugin/`](software/dataexplorer-plugin/) — integration workspace and handoff target
- [`research/`](research/) — source-by-source reverse-engineering notes and uncertainty
- [`docs/`](docs/) — usage and safety notes
- [`examples/`](examples/) — future sanitized fixtures and example captures

## Important safety note

EnvironmentalTester 1.22 labels the common serial-layer command `AA 55 04 03 06` as `DATA_ERASE`. Its applicability to the TA612C has not been physically tested and the TA612C UI path does not appear to call it. Treat it as potentially destructive and **do not send it experimentally**.

See [`docs/safety.md`](docs/safety.md).
