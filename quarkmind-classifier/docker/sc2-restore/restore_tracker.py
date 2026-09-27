"""Restore tracker events in Blizzard API ladder replays.

Uses PySC2's run_configs to launch SC2 with correct version/data settings,
then plays each replay with record_replay=True and saves the re-recorded
replay which includes regenerated tracker events.

Usage (inside Docker):
  python3 restore_tracker.py --input /data/input --output /data/output --workers 4

Each worker starts its own SC2 instance via PySC2.
"""
import argparse
import io
import json
import multiprocessing
import os
import sys
import time
from pathlib import Path

os.environ["PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION"] = "python"

from absl import flags
flags.FLAGS(sys.argv[:1])

import mpyq
from s2clientprotocol import sc2api_pb2 as sc_pb
from pysc2 import run_configs


def get_replay_version(replay_data):
    """Extract game version from replay metadata."""
    archive = mpyq.MPQArchive(io.BytesIO(replay_data)).extract()
    meta = json.loads(archive[b"replay.gamemetadata.json"])
    return ".".join(meta["GameVersion"].split(".")[:-1])


def restore_one_replay(controller, replay_data):
    """Load replay with record_replay, step to end, save with tracker events.

    Returns (saved_bytes, error_msg). saved_bytes is None on failure.
    """
    interface = sc_pb.InterfaceOptions(raw=True, score=True)
    req = sc_pb.RequestStartReplay(
        replay_data=replay_data,
        options=interface,
        observed_player_id=1,
        record_replay=True,
    )

    try:
        controller.start_replay(req)
    except Exception as e:
        return None, f"StartReplay: {e}"

    step_count = 0
    while True:
        try:
            obs = controller.observe()
        except Exception as e:
            return None, f"Observe: {e}"

        if obs.player_result:
            break
        try:
            controller.step(2000)
        except Exception as e:
            return None, f"Step: {e}"

        step_count += 1
        if step_count > 500:
            return None, "Exceeded 500 step iterations"

    try:
        saved_data = controller.save_replay()
    except Exception as e:
        return None, f"SaveReplay: {e}"

    if not saved_data:
        return None, "SaveReplay returned empty data"

    try:
        archive = mpyq.MPQArchive(io.BytesIO(saved_data))
        has_tracker = any(b"tracker" in f for f in archive.files)
        if not has_tracker:
            return None, "Saved replay has no tracker events"
    except Exception as e:
        return None, f"MPQ verify: {e}"

    return saved_data, None


def worker_loop(worker_id, replay_files, output_dir, result_queue):
    """Worker process: start SC2 via PySC2, process assigned replays."""
    ok_count = 0
    fail_count = 0
    controller = None
    proc = None
    current_version = None

    try:
        for i, replay_path in enumerate(replay_files):
            output_path = output_dir / replay_path.name

            if output_path.exists():
                ok_count += 1
                continue

            replay_data = replay_path.read_bytes()

            # Start or restart SC2 if version changed
            version = get_replay_version(replay_data)
            if version != current_version:
                if proc:
                    proc.close()
                rc = run_configs.get(version=version)
                proc = rc.start(want_rgb=False)
                controller = proc.controller
                current_version = version
                print(f"[W{worker_id}] SC2 started for version {version}", flush=True)

            saved_data, error = restore_one_replay(controller, replay_data)

            if saved_data:
                output_path.write_bytes(saved_data)
                ok_count += 1
            else:
                fail_count += 1
                if fail_count <= 10:
                    print(f"[W{worker_id}] FAIL {replay_path.name}: {error}", flush=True)

                # Restart SC2 on failure
                try:
                    proc.close()
                except Exception:
                    pass
                rc = run_configs.get(version=current_version)
                proc = rc.start(want_rgb=False)
                controller = proc.controller

            if (i + 1) % 50 == 0:
                print(
                    f"[W{worker_id}] Progress: {i+1}/{len(replay_files)} "
                    f"({ok_count} ok, {fail_count} fail)",
                    flush=True,
                )

    except Exception as e:
        print(f"[W{worker_id}] Fatal: {e}", flush=True)
        import traceback
        traceback.print_exc()

    finally:
        if proc:
            try:
                proc.close()
            except Exception:
                pass

    result_queue.put((worker_id, ok_count, fail_count))


def main():
    parser = argparse.ArgumentParser(description="Restore tracker events in SC2 replays")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--offset", type=int, default=0)
    args = parser.parse_args()

    args.output.mkdir(parents=True, exist_ok=True)

    replay_files = sorted(args.input.glob("*.SC2Replay"))
    replay_files = replay_files[args.offset:]
    if args.limit:
        replay_files = replay_files[:args.limit]

    # Skip already-restored replays
    existing = set(f.name for f in args.output.glob("*.SC2Replay"))
    remaining = [f for f in replay_files if f.name not in existing]

    total = len(replay_files)
    skipped = total - len(remaining)
    print(f"Restoring tracker events: {total} replays, {skipped} already done, {len(remaining)} to process")
    print(f"Workers: {args.workers}")
    print(f"Input:  {args.input}")
    print(f"Output: {args.output}")

    if not remaining:
        print("Nothing to process")
        return

    # Split across workers
    chunks = [[] for _ in range(args.workers)]
    for i, rp in enumerate(remaining):
        chunks[i % args.workers].append(rp)

    result_queue = multiprocessing.Queue()
    processes = []

    t0 = time.time()
    for w in range(args.workers):
        if not chunks[w]:
            continue
        p = multiprocessing.Process(
            target=worker_loop,
            args=(w, chunks[w], args.output, result_queue),
        )
        p.start()
        processes.append(p)

    total_ok = skipped
    total_fail = 0
    for _ in range(len(processes)):
        wid, ok, fail = result_queue.get()
        total_ok += ok
        total_fail += fail
        print(f"[W{wid}] Done: {ok} ok, {fail} fail")

    for p in processes:
        p.join()

    elapsed = time.time() - t0
    rate = len(remaining) / elapsed if elapsed > 0 else 0
    print(f"\nComplete: {total_ok} restored, {total_fail} failed in {elapsed:.0f}s ({rate:.1f} replays/s)")
    print(f"Safe to stop — all completed replays are saved to {args.output}")


if __name__ == "__main__":
    main()
