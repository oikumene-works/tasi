# TASI devices

Open protocol research, software tools and integrations for selected TASI measurement devices.

This repository is organized by device model. Shared vendor context can stay together while model-specific protocol notes, software and experiments remain clearly separated.

## Devices

### TA612C

Four-channel thermocouple thermometer.

Current work includes:

- USB/serial protocol research
- a Linux live logger and read-only REC downloader
- DataExplorer integration work
- cross-checking against TASI EnvironmentalTester and Artisan
- documented physical-device observations, including alarm behaviour

See [`ta612c/`](ta612c/).

## Repository principles

- Separate confirmed observations, source-derived claims, inference and open questions.
- Preserve source provenance for protocol claims.
- Keep potentially destructive commands explicit and isolated.
- Prefer reusable fixtures and documented raw-frame examples over undocumented assumptions.
- Do not redistribute vendor binaries unless redistribution rights are known.

## Licensing

No repository-wide license has been selected yet. Until one is added, normal copyright rules apply. Third-party software and documentation remain under their respective owners' terms.
