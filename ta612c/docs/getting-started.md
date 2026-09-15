# Getting started on Linux

## USB serial

A tested TA612C appears through a QinHeng CH340 USB-to-serial adapter and is typically available as:

```text
/dev/ttyUSB0
```

Expected serial settings are 9600 baud, 8N1.

## Logger

Install pyserial and run the self-contained helper from [`../software/logger/`](../software/logger/).

Live logging:

```bash
python3 ta612c_logger.py --port /dev/ttyUSB0 live
```

Read-only REC download:

```bash
python3 ta612c_logger.py --port /dev/ttyUSB0 download --interval 5
```

Choose `--interval` to match the REC interval used on the meter. The downloaded payload itself has not shown per-sample timestamps.
