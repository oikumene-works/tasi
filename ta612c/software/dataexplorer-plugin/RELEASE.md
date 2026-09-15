# Accepted baseline — 2026-09-15

The first repository baseline is the accepted standalone TA612C plugin for
DataExplorer 4.0.7. It includes read-only REC download and live acquisition with
partially connected probes.

## Validation

- 109 live protocol/lifecycle assertions
- 135 REC protocol/lifecycle assertions
- 57,343 REC import/OSD/fixture/UI assertions
- 52 XML/loader/OSD/serial integration assertions
- 57,639 assertions total; `BUILD SUCCESSFUL`
- short physical T1-only and T2+T4 trials accepted within their bounded scope

## Preserved local artifacts

These files are copied under ignored `local/` directories and are not intended
for Git history or an automatic public push:

| Local file | SHA-256 |
|---|---|
| `local/artifacts/TA612C.jar` | `6b557f3b804902feba52ae9e33f568695e0e109c0b4646ff4f5a9dbf8bcbf8c5` |
| `local/artifacts/TA612C-source-accepted.zip` | `ed94916672b7fdf6d1685b6886a25cb6dd57e3906ac84777e1a6d909f556309d` |
| `local/validation/full-test-2026-09-15.log` | `ddda6a21865b58a7784928fc1e442f133f522f7c9bfa13b3a61d4bd261a64920` |
| `local/evidence/test1-T1-live-and-REC.osd` | `0321c9638e223a8bbd78077c33711d530663bfa4fc15c86fa81cfdabadf9881f` |
| `local/evidence/test2-T2-T4-live-and-REC.osd` | `373901a453ed19781143549b5c96be6f5410c0f8a12ec6c6c19ed73eea1c1dca` |

If a remote release is created later, upload a freshly rebuilt and revalidated
JAR deliberately as a release asset rather than committing it to the source
tree. The implementation version remains aligned with DataExplorer 4.0.7; the
Git tag records this project baseline separately.
