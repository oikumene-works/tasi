# Fixture provenance

These files were copied byte-for-byte from the user's local Quattroprobe data on
2026-09-12. Tests never write to them or access the physical meter.

The REC CSVs are decoded Python exports, **not raw serial captures**. The test
constructs synthetic REC frames using their T1–T4 values and source-frame grouping.
CSV elapsed values were derived by the Python logger from its supplied/default
interval; they are not evidence that the instrument used five seconds. The tests
explicitly choose five seconds solely for repeatable time reconstruction.

`live-first.osd` is the accepted 41-row `2026-09-10_.osd`; `live-restarts.osd` is
the accepted `test-2.osd` containing five sets with 47 rows total. Both originate
from `TA612C/build/device-trial/recordings/TA612C/`. Tests reopen copies only.

| File | SHA-256 |
|---|---|
| `live-first.osd` | `afcb0cb0a8d26d25e70c6c0882634d92460ed14b6f37edace4afccbc948c734a` |
| `live-restarts.osd` | `1bbd5baeffe154d5269231ba401af0ce5503f5845c31e9c1abeb15d0fec66f02` |
| `ta612c_rec_20260909_142327.csv` | `250a24f72fe60f693073fc4cf92171ab0be37eb7cf4a3d63d1d9e02a9a138ec8` |
| `ta612c_rec_20260910_034221.csv` | `21cca640636738f18253486fa269065f1ab25d64803290ad6a23ad29da37e5cd` |
| `ta612c_rec_20260910_050104.csv` | `0c399d4792ee0a6bb9086096b742ae3ff7c12411bbeacd41ef55ad87d09e7234` |
| `ta612c_rec_20260911_085955.csv` | `e928efc7e308d76bbcf09fcc2bfe26a3a16f52f1e317721bf89d648f4d12b47a` |
