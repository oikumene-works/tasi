#!/usr/bin/env python3
"""
TA612C / "Quatttroprobe" helper for Linux — self-contained v3.2.

One file only. No companion Python modules are required.

Modes:
  download   Read-only download of internally recorded REC data.
             Creates:
               .csv              parsed temperature samples
               .frames.csv       parsed frames + raw frame hex
               .transcript.jsonl exact host-side TX/RX byte transcript
               .meta.json        metadata, diagnostics, SHA-256 hashes
  live       Read four-channel live data over USB and log to CSV.

Indexing policy:
- sample_index: 0-based (sample 0 is t=0)
- frame_number / source_frame: 1-based human-facing references
- frame_sample_index: 0-based within each frame
- transcript seq: 0-based internal sequence

Important:
- The observed REC payload does not include per-sample timestamps.
  assumed_elapsed_s is reconstructed as sample_index * --interval.
- The script does NOT infer REC-session boundaries.
- The script has no erase command and does NOT erase meter memory.
"""

import argparse
import csv
import datetime as dt
import hashlib
import json
import platform
import sys
import time
from collections import Counter
from pathlib import Path

import serial

SCRIPT_VERSION = "3.2"
SCHEMA_VERSION = "ta612c_logger_download_meta_3"
PORT_DEFAULT = "/dev/ttyUSB0"
INFO_CMD = bytes.fromhex("AA 55 00 03 02")
LIVE_CMD = bytes.fromhex("AA 55 01 03 03")
REC_DOWNLOAD_CMD = bytes.fromhex("AA 55 02 03 04")
HEADER = b"\x55\xAA"
OPEN_PROBE_ABS_THRESHOLD_C = 2000.0


def iso_now():
    return dt.datetime.now().astimezone().isoformat(timespec="milliseconds")


def sha256_file(path: Path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def checksum_ok(frame: bytes) -> bool:
    return len(frame) >= 2 and (sum(frame[:-1]) & 0xFF) == frame[-1]


def le_u16(lo: int, hi: int) -> int:
    return lo | (hi << 8)


def le_i16(lo: int, hi: int) -> int:
    value = le_u16(lo, hi)
    return value - 0x10000 if value & 0x8000 else value


def parse_four_temperatures(payload8: bytes):
    if len(payload8) != 8:
        raise ValueError("temperature sample must contain exactly 8 bytes")
    return [le_i16(payload8[i], payload8[i + 1]) / 10.0 for i in range(0, 8, 2)]


def parse_labels(items):
    labels = {}
    for item in items or []:
        if "=" not in item:
            raise ValueError(f"Bad --label {item!r}; expected T1=text, ..., T4=text")
        key, value = item.split("=", 1)
        key = key.strip().upper()
        value = value.strip()
        if key not in {"T1", "T2", "T3", "T4"} or not value:
            raise ValueError(f"Bad --label {item!r}; expected T1=text, ..., T4=text")
        labels[key] = value
    return labels


class Transcript:
    def __init__(self, path: Path):
        self.path = path
        self.f = path.open("w", encoding="utf-8")
        self.t0 = time.monotonic()
        self.seq = 0

    def elapsed(self):
        return time.monotonic() - self.t0

    def record(self, direction: str, data: bytes, label=None):
        if not data:
            return
        row = {
            "seq": self.seq,
            "timestamp_iso": iso_now(),
            "host_elapsed_s": round(self.elapsed(), 6),
            "direction": direction,
            "label": label,
            "n_bytes": len(data),
            "hex": data.hex(" "),
        }
        self.f.write(json.dumps(row, ensure_ascii=False) + "\n")
        self.f.flush()
        self.seq += 1

    def close(self):
        if not self.f.closed:
            self.f.close()

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        self.close()


class FrameReader:
    def __init__(self, ser: serial.Serial, transcript: Transcript | None = None):
        self.ser = ser
        self.transcript = transcript
        self.buf = bytearray()

    def read_frame(self, timeout_s=1.0):
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            chunk = self.ser.read(256)
            if chunk:
                if self.transcript is not None:
                    self.transcript.record("rx", chunk, "serial_read")
                self.buf.extend(chunk)
            while True:
                start = self.buf.find(HEADER)
                if start < 0:
                    if len(self.buf) > 1:
                        del self.buf[:-1]
                    break
                if start:
                    del self.buf[:start]
                if len(self.buf) < 4:
                    break
                total_len = self.buf[3] + 2
                if total_len < 5 or total_len > 257:
                    del self.buf[0]
                    continue
                if len(self.buf) < total_len:
                    break
                frame = bytes(self.buf[:total_len])
                del self.buf[:total_len]
                host_elapsed = self.transcript.elapsed() if self.transcript is not None else time.monotonic()
                return frame, host_elapsed
        return None


def send_command(ser, transcript: Transcript | None, data: bytes, label: str):
    if transcript is not None:
        transcript.record("tx", data, label)
    ser.write(data)
    ser.flush()


def open_meter(port):
    return serial.Serial(port, baudrate=9600, bytesize=serial.EIGHTBITS,
                         parity=serial.PARITY_NONE, stopbits=serial.STOPBITS_ONE,
                         timeout=0.15)


def verify_meter(ser, reader, transcript=None):
    ser.reset_input_buffer()
    send_command(ser, transcript, INFO_CMD, "device_info_request")
    item = reader.read_frame(1.5)
    if item is None:
        raise RuntimeError("TA612C did not answer the device-info request")
    frame, _ = item
    if not checksum_ok(frame):
        raise RuntimeError(f"Bad device-info checksum: {frame.hex(' ')}")
    if len(frame) < 9 or frame[2] != 0x00:
        raise RuntimeError(f"Unexpected device-info frame: {frame.hex(' ')}")
    return le_u16(frame[4], frame[5]), le_u16(frame[6], frame[7]) / 100.0


def timestamped_path(prefix):
    return Path.cwd() / f"{prefix}_{dt.datetime.now().strftime('%Y%m%d_%H%M%S')}.csv"


def sidecar_paths(csv_path: Path):
    if csv_path.suffix.lower() == ".csv":
        base = csv_path.with_suffix("")
    else:
        base = csv_path
        csv_path = Path(str(csv_path) + ".csv")
    return {
        "csv": csv_path,
        "frames": Path(str(base) + ".frames.csv"),
        "transcript": Path(str(base) + ".transcript.jsonl"),
        "meta": Path(str(base) + ".meta.json"),
    }


def is_open_probe(value):
    return abs(value) >= OPEN_PROBE_ABS_THRESHOLD_C


def build_diagnostics(samples, interval, jump_threshold):
    channels = ["T1", "T2", "T3", "T4"]
    result = {
        "open_probe_rule": f"abs(value) >= {OPEN_PROBE_ABS_THRESHOLD_C:g} °C",
        "open_probe_counts": {},
        "open_probe_values": {},
        "abrupt_change_threshold_C_per_sample": jump_threshold,
        "abrupt_change_candidates": [],
        "session_boundary_detection": "not inferred; observed REC payload has no confirmed session marker. Abrupt changes are review points only.",
    }
    for ci, ch in enumerate(channels):
        vals = [row[ci] for row in samples]
        open_vals = [v for v in vals if is_open_probe(v)]
        result["open_probe_counts"][ch] = len(open_vals)
        result["open_probe_values"][ch] = sorted(set(open_vals))[:20]
    abrupt = []
    for i in range(1, len(samples)):
        prev, cur = samples[i - 1], samples[i]
        for ci, ch in enumerate(channels):
            a, b = prev[ci], cur[ci]
            if is_open_probe(a) or is_open_probe(b):
                continue
            delta = b - a
            if abs(delta) >= jump_threshold:
                abrupt.append({
                    "sample_index": i,
                    "assumed_elapsed_s": round(i * interval, 3),
                    "channel": ch,
                    "previous_C": a,
                    "current_C": b,
                    "delta_C": round(delta, 1),
                })
    abrupt.sort(key=lambda x: abs(x["delta_C"]), reverse=True)
    result["abrupt_change_candidates"] = abrupt[:100]
    result["abrupt_change_candidate_count"] = len(abrupt)
    return result


def write_json(path: Path, obj):
    with path.open("w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2, sort_keys=False)
        f.write("\n")


def download_rec(args):
    output = args.output or timestamped_path("ta612c_rec")
    paths = sidecar_paths(output)
    for p in paths.values():
        p.parent.mkdir(parents=True, exist_ok=True)

    labels = parse_labels(args.label)
    started_iso = iso_now()
    started_mono = time.monotonic()
    invoked = Path(sys.argv[0])
    try:
        invoked_resolved = str(invoked.resolve())
    except Exception:
        invoked_resolved = str(invoked)

    metadata = {
        "schema": SCHEMA_VERSION,
        "script_version": SCRIPT_VERSION,
        "status": "started",
        "download_started_iso": started_iso,
        "command_line": sys.argv,
        "implementation": {"single_file": True, "invoked_as": sys.argv[0], "invoked_path_resolved": invoked_resolved},
        "host": {"platform": platform.platform(), "python": platform.python_version()},
        "serial": {"port": args.port, "baudrate": 9600, "format": "8N1"},
        "rec": {
            "assumed_interval_s": args.interval,
            "note": args.note,
            "channel_labels": labels,
            "time_axis_warning": "assumed_elapsed_s is reconstructed from sample_index × user-supplied REC interval; REC sample timestamps were not observed in the payload.",
        },
        "numbering": {
            "sample_index": "0-based; sample 0 corresponds to assumed_elapsed_s = 0",
            "source_frame": "1-based human-facing frame reference",
            "frames_csv_frame_number": "1-based human-facing frame reference",
            "frame_sample_index": "0-based sample-group index within a frame",
            "transcript_seq": "0-based internal host transcript sequence",
        },
        "device": {}, "counts": {}, "frame_sample_group_distribution": {},
        "diagnostics": {}, "files": {k: str(v) for k, v in paths.items()},
        "hashes_sha256": {},
        "safety": {"meter_memory_erased": False, "erase_command_present_in_script": False},
    }

    samples = []
    frame_group_counts = Counter()
    frames_seen = rec_frames = bad_checksums = malformed_frames = 0
    empty_rec_frames = non_rec_frames = 0
    model = fw = None

    try:
        with Transcript(paths["transcript"]) as transcript, \
             paths["csv"].open("w", newline="", encoding="utf-8") as data_f, \
             paths["frames"].open("w", newline="", encoding="utf-8") as frames_f:

            data_writer = csv.writer(data_f)
            data_writer.writerow(["sample_index", "assumed_elapsed_s", "T1_C", "T2_C", "T3_C", "T4_C", "source_frame", "frame_sample_index"])
            frame_writer = csv.writer(frames_f)
            frame_writer.writerow(["frame_number", "host_rx_elapsed_s", "command_hex", "length_byte", "total_bytes", "checksum_ok", "payload_bytes", "rec_sample_groups", "raw_hex"])
            data_f.flush(); frames_f.flush()

            with open_meter(args.port) as ser:
                reader = FrameReader(ser, transcript)
                model, fw = verify_meter(ser, reader, transcript)
                print(f"TA response OK: model {model}, firmware {fw:.2f}")
                print("REC download is read-only; meter memory will not be erased.")
                ser.reset_input_buffer(); reader.buf.clear()
                send_command(ser, transcript, REC_DOWNLOAD_CMD, "rec_download_request")

                while True:
                    item = reader.read_frame(timeout_s=1.5)
                    if item is None:
                        break
                    frame, host_rx_elapsed = item
                    frames_seen += 1
                    frame_number = frames_seen
                    ck_ok = checksum_ok(frame)
                    command = frame[2] if len(frame) >= 3 else None
                    length_byte = frame[3] if len(frame) >= 4 else None
                    payload = frame[4:-1] if len(frame) >= 5 else b""
                    sample_groups = len(payload) // 8 if command == 0x02 and len(payload) % 8 == 0 else 0
                    frame_writer.writerow([
                        frame_number, f"{host_rx_elapsed:.6f}",
                        f"0x{command:02X}" if command is not None else "",
                        length_byte if length_byte is not None else "", len(frame),
                        int(ck_ok), len(payload), sample_groups, frame.hex(" ")])
                    frames_f.flush()

                    if not ck_ok:
                        bad_checksums += 1; continue
                    if command != 0x02:
                        non_rec_frames += 1; continue
                    rec_frames += 1
                    if not payload:
                        empty_rec_frames += 1; frame_group_counts[0] += 1; continue
                    if len(payload) % 8:
                        malformed_frames += 1; frame_group_counts["malformed"] += 1
                        print(f"Warning: REC frame {frame_number} has {len(payload)} payload bytes, not divisible by 8.", file=sys.stderr)
                        continue

                    groups = len(payload) // 8
                    frame_group_counts[groups] += 1
                    for group_index, pos in enumerate(range(0, len(payload), 8)):
                        temps = parse_four_temperatures(payload[pos:pos + 8])
                        sample_index = len(samples)
                        samples.append(tuple(temps))
                        data_writer.writerow([
                            sample_index, f"{sample_index * args.interval:.3f}",
                            *(f"{x:.1f}" for x in temps), frame_number, group_index])
                    data_f.flush()
                    if len(samples) and len(samples) % 100 < max(1, groups):
                        print(f"\rReceived {len(samples)} samples...", end="", flush=True)

        if samples: print()
        else: print("No recorded temperature samples were returned.")
        duration = (len(samples) - 1) * args.interval if len(samples) > 1 else 0.0
        metadata["status"] = "ok" if samples else "no_samples"
        metadata["device"] = {"model": model, "firmware": fw}
        metadata["counts"] = {
            "samples": len(samples), "frames_total_after_rec_request": frames_seen,
            "rec_frames": rec_frames, "non_rec_frames": non_rec_frames,
            "empty_rec_frames": empty_rec_frames, "bad_checksums": bad_checksums,
            "malformed_rec_frames": malformed_frames}
        metadata["reconstructed_span_s"] = duration
        metadata["frame_sample_group_distribution"] = {str(k): v for k, v in sorted(frame_group_counts.items(), key=lambda kv: str(kv[0]))}
        metadata["diagnostics"] = build_diagnostics(samples, args.interval, args.jump_threshold)

        print(f"Downloaded samples: {len(samples)}")
        print(f"Assumed REC interval: {args.interval:g} s")
        print(f"Reconstructed span: {duration:g} s")
        print(f"REC frames: {rec_frames} / total frames after request: {frames_seen}")
        print(f"Empty REC frames: {empty_rec_frames}")
        print(f"Bad checksums: {bad_checksums}")
        print(f"Malformed REC frames: {malformed_frames}")
        print(f"CSV:        {paths['csv']}")
        print(f"Frames:     {paths['frames']}")
        print(f"Transcript: {paths['transcript']}")
        print(f"Metadata:   {paths['meta']}")
        print("Meter memory was NOT erased.")
        print("Frame references: 1-based; sample_index remains 0-based.")
        return 0 if samples else 1

    except Exception as e:
        metadata["status"] = "error"
        metadata["error"] = {"type": type(e).__name__, "message": str(e)}
        metadata["device"] = {"model": model, "firmware": fw}
        metadata["counts"] = {
            "samples_before_error": len(samples), "frames_seen_before_error": frames_seen,
            "rec_frames_before_error": rec_frames, "bad_checksums_before_error": bad_checksums,
            "malformed_rec_frames_before_error": malformed_frames}
        raise

    finally:
        metadata["download_finished_iso"] = iso_now()
        metadata["host_download_elapsed_s"] = round(time.monotonic() - started_mono, 6)
        for key in ("csv", "frames", "transcript"):
            p = paths[key]
            if p.exists():
                try:
                    metadata["hashes_sha256"][key] = sha256_file(p)
                except Exception as hash_error:
                    metadata["hashes_sha256"][key] = f"ERROR: {hash_error}"
        try:
            script_path = Path(__file__).resolve()
            if script_path.exists():
                metadata["hashes_sha256"]["script"] = sha256_file(script_path)
        except Exception as hash_error:
            metadata["hashes_sha256"]["script"] = f"ERROR: {hash_error}"
        try:
            write_json(paths["meta"], metadata)
        except Exception as meta_error:
            print(f"Warning: could not write metadata: {meta_error}", file=sys.stderr)


def live_log(args):
    output = args.output or timestamped_path("ta612c_live")
    output.parent.mkdir(parents=True, exist_ok=True)
    with open_meter(args.port) as ser:
        reader = FrameReader(ser)
        model, fw = verify_meter(ser, reader)
        print(f"TA response OK: model {model}, firmware {fw:.2f}")
        with output.open("w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(["timestamp_iso", "elapsed_s", "T1_C", "T2_C", "T3_C", "T4_C"])
            f.flush(); ser.reset_input_buffer(); reader.buf.clear()
            send_command(ser, None, LIVE_CMD, "live_request")
            print(f"Logging to: {output}"); print("Press Ctrl+C to stop.")
            t0 = last_valid = time.monotonic(); rows = 0
            try:
                while True:
                    if args.duration is not None and time.monotonic() - t0 >= args.duration:
                        break
                    item = reader.read_frame(0.5)
                    if item is None:
                        if time.monotonic() - last_valid > 1.2:
                            send_command(ser, None, LIVE_CMD, "live_request_repeat")
                        continue
                    frame, _ = item
                    if not checksum_ok(frame) or frame[2] != 0x01 or len(frame[4:-1]) != 8:
                        continue
                    temps = parse_four_temperatures(frame[4:-1])
                    elapsed = time.monotonic() - t0
                    writer.writerow([iso_now(), f"{elapsed:.3f}", *(f"{x:.1f}" for x in temps)])
                    f.flush(); rows += 1; last_valid = time.monotonic()
                    print(f"{elapsed:8.2f}s  T1 {temps[0]:6.1f} °C  T2 {temps[1]:6.1f} °C  T3 {temps[2]:6.1f} °C  T4 {temps[3]:6.1f} °C")
            except KeyboardInterrupt:
                print("\nStopped by user.")
    print(f"Rows logged: {rows}"); print(f"CSV saved as: {output}")
    return 0


def build_parser():
    parser = argparse.ArgumentParser(description="TA612C four-channel REC downloader and live logger — self-contained v3.2")
    parser.add_argument("--port", default=PORT_DEFAULT, help=f"serial device (default: {PORT_DEFAULT})")
    sub = parser.add_subparsers(dest="mode", required=True)
    p_download = sub.add_parser("download", help="read-only REC download + forensic sidecars")
    p_download.add_argument("--interval", type=float, default=5.0, help="assumed REC sampling interval in seconds (default: 5)")
    p_download.add_argument("--output", type=Path, default=None, help="main CSV output path; sidecars use the same basename")
    p_download.add_argument("--note", default=None, help="free-form experiment note stored in metadata")
    p_download.add_argument("--label", action="append", default=[], metavar="Tn=text", help="channel meaning, repeatable; e.g. --label T1=oven_air --label T4=food_core")
    p_download.add_argument("--jump-threshold", type=float, default=15.0, help="flag adjacent plausible temperature changes >= this many °C for review (default: 15); diagnostic only")
    p_download.set_defaults(func=download_rec)
    p_live = sub.add_parser("live", help="log live USB temperatures")
    p_live.add_argument("--duration", type=float, default=None, help="optional duration in seconds; otherwise Ctrl+C")
    p_live.add_argument("--output", type=Path, default=None, help="CSV output path")
    p_live.set_defaults(func=live_log)
    return parser


def main():
    args = build_parser().parse_args()
    if hasattr(args, "interval") and args.interval <= 0:
        raise ValueError("--interval must be > 0")
    if hasattr(args, "jump_threshold") and args.jump_threshold <= 0:
        raise ValueError("--jump-threshold must be > 0")
    return args.func(args)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except serial.SerialException as e:
        print(f"Serial error: {e}", file=sys.stderr)
        sys.exit(2)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)