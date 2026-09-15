# TA612C physical alarm behaviour

Physical observations on firmware 3.50:

1. A configured HI alarm sounds when the measured temperature rises above the HI threshold.
2. Entering settings with `MODE` stops the sound immediately.
3. If the HI value is left unchanged and measurement mode is resumed while the temperature is still above the threshold, the alarm resumes.
4. No separate front-panel acknowledge/mute/silence operation has been found so far.
5. Power-off stops the alarm but also interrupts REC recording.

The practical missing operation is:

> Silence the audible alarm while leaving the HI threshold unchanged and allowing measurement and REC recording to continue.

## What software research has shown

- The 2022 protocol document did not reveal a TA612C alarm acknowledge/mute command.
- Artisan does not implement one.
- EnvironmentalTester 1.21 and 1.22 static analysis did not reveal one in the TA612C path.
- EnvironmentalTester's own Alarm UI appears to implement PC-side threshold comparisons and WAV playback rather than configuring the meter's physical HI/LO alarm.

## Open question

Firmware 3.50 may still contain an undocumented front-panel operation or device command. A manufacturer support question has been sent asking whether such a procedure exists.
