# EnvironmentalTester 1.21 — static analysis notes

## Identification

The analyzed executable contains build/source paths including:

```text
TA_Env_V1.21_PRD/Env_SourceCode/
```

SHA-256:

```text
29618357e560231377eee00c1486ef06f57637e13c4d348d06024cd05dda4876
```

## Serial command layer

`usart_interface::slot_send_data(int)` contains command cases 0 through 4. The first three match the physically used TA612 traffic:

```text
0 -> AA 55 00 03 02
1 -> AA 55 01 03 03
2 -> AA 55 02 03 04
```

Command 3 constructs a time-sync frame starting with `AA 55 03 07`. Static disassembly shows use of Qt `QDateTime::currentDateTime()` and `QDateTime::toTime_t()`, indicating a 32-bit Unix timestamp payload rather than the earlier tentative BCD interpretation.

Command 4 builds:

```text
AA 55 04 03 06
```

In 1.21 the purpose was not as clearly named in strings as in 1.22.

## TA612C interface use

The TA612C-specific class exposes functions such as `get_rt_data()` and `get_save_data()`. Static call-path inspection found normal TA612C UI traffic using live (`1`) and upload/REC (`2`); device-info (`0`) is handled in connection setup. No TA612C alarm-mute, acknowledge or physical HI/LO-setting command was found.

## Alarm controls

The application's channel alarm limits are stored and compared locally by the PC software. When thresholds are crossed, EnvironmentalTester plays resource WAV files such as:

```text
:/res/sound/highalarm.WAV
:/res/sound/lowalarm.WAV
```

No serial write was found on this PC-side alarm path. Therefore the EnvironmentalTester Alarm UI must not be assumed to configure the meter's physical HI/LO alarm.

## Poll interval

The TA612C interval setting updates a Qt timer (`value * 1000` milliseconds) used for PC live polling. It is not evidence of a command that changes the meter's internal REC sampling interval.
