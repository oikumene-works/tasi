# Artisan TA612C implementation — comparison notes

Artisan is useful as an independent open-source implementation, especially for live plotting and integration.

Observed characteristics of its TA612C path during this research:

- uses serial settings 9600 baud, 8N1
- sends the live request `AA 55 01 03 03`
- expects a fixed-size live response and extracts four temperature words for T1..T4
- uses T1/T2 as the main meter channels and T3/T4 through its extra-device support
- does not implement TA612C REC download, device-info verification, clock sync or alarm control in this reader path
- does not provide the same frame-resynchronization, checksum validation and forensic sidecars as the local v3.2 logger
- historically decodes channel words as unsigned; this remains worth comparing against a below-zero physical fixture

Artisan's strengths are broader application UI, graphing and integration. The local logger's strengths are protocol transparency, REC capture and preservation of raw evidence.

No Artisan source code is copied here.
