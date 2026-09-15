# Frame notes

## Serial transport

Observed and documented transport settings:

- 9600 baud
- 8 data bits
- no parity
- 1 stop bit

The physical Linux device has appeared through a QinHeng CH340 USB-to-serial adapter (`1a86:7523`), typically as `/dev/ttyUSB0`.

## Direction and header

Host-to-meter examples begin:

```text
AA 55 ...
```

Meter-to-host response frames begin:

```text
55 AA ...
```

The current frame reader treats the byte at offset 3 as a length field and calculates total response-frame bytes as:

```text
total_length = length_byte + 2
```

## Checksum

Current verified rule:

```text
checksum = sum(all previous frame bytes) & 0xFF
```

The checksum is the final byte.

## Temperature payload

Live and REC samples use 8 payload bytes for T1..T4, two bytes per channel, little-endian, scaled by 0.1 °C in the current parser.

The public logger currently decodes each value as signed 16-bit. This is consistent with the meter's negative-temperature range, but a dedicated below-zero physical capture is still a useful future fixture because Artisan's TA612C implementation has historically decoded these words as unsigned.

## REC timing

No per-sample timestamp has been confirmed in the observed REC payload. The logger therefore reconstructs elapsed time from sample index and a user-supplied assumed REC interval. It does not infer recording-session boundaries.

## Open probe

A physical open-probe condition has been observed as `2800.0 °C`. The logger uses `abs(value) >= 2000 °C` as a diagnostic open-probe rule rather than treating that sentinel as a real temperature.
