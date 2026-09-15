# TA612C logger/downloader

`ta612c_logger.py` is a self-contained Linux helper currently at version 3.2.

It provides:

- `live` — four-channel live logging to CSV
- `download` — read-only REC download
- checksum validation and frame resynchronization
- parsed REC CSV output
- frame-level CSV sidecar
- exact host-side TX/RX transcript JSONL
- metadata, diagnostics and SHA-256 hashes

The downloader intentionally contains **no erase command**.

## Dependency

```bash
python3 -m pip install pyserial
```

## Examples

```bash
python3 ta612c_logger.py --port /dev/ttyUSB0 live
```

```bash
python3 ta612c_logger.py --port /dev/ttyUSB0 download --interval 5
```

The `--interval` value is an assumption used to reconstruct REC elapsed time. No per-sample REC timestamp has been confirmed in the downloaded payload.

## Current caveat

Live mode in v3.2 starts with one live-data request and repeats the request after more than 1.2 seconds without a valid frame. Vendor documentation and Artisan both support treating live traffic as explicit request -> one response polling, so this behaviour is a candidate for cleanup in a future version.
