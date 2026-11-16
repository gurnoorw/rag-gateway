#!/usr/bin/env python3
"""Samples the app while a scenario runs (t08, scenarios 7 and 7b) until <stop-file> exists:

  sampler.py <prometheus-url> <pid> <stop-file> <out.json>

  * heap used: sum of jvm_memory_used_bytes{area="heap"} from /actuator/prometheus, every 0.5 s;
  * live virtual threads: `jcmd <pid> Thread.dump_to_file -format=text`, every 2 s, counting the thread headers
    that end in "virtual".

Why a thread dump: jvm_threads_live (ThreadMXBean) counts platform threads only, and the JDK 21 has no metric
for live virtual threads. A dump lists every virtual thread the JDK tracks (jdk.trackAllThreads, on by default),
which includes the request threads Tomcat starts and the adapter's "claude-call" threads (D-37 c).

Both are samples, so a "peak" is the largest value sampled, not a continuous maximum; the summary notes say so.
"""
import json
import os
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import prom  # noqa: E402

HEAP_EVERY_S = 0.5
THREADS_EVERY_S = 2.0


def count_virtual_threads(pid, workdir):
    path = os.path.join(workdir, f"dump-{time.time_ns()}.txt")
    try:
        subprocess.run(["jcmd", str(pid), "Thread.dump_to_file", "-format=text", path],
                       check=True, capture_output=True, timeout=30)
        with open(path, errors="replace") as f:
            return sum(1 for line in f if line.startswith("#") and line.rstrip().endswith(" virtual"))
    finally:
        if os.path.exists(path):
            os.remove(path)


def main():
    if len(sys.argv) != 5:
        sys.exit("usage: sampler.py <prometheus-url> <pid> <stop-file> <out.json>")
    url, pid, stop, out = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
    peak_heap, peak_vt, heap_n, vt_n, errors = 0.0, 0, 0, 0, []
    next_vt = 0.0
    with tempfile.TemporaryDirectory(prefix="bench-sampler-") as workdir:
        while not os.path.exists(stop):
            now = time.monotonic()
            try:
                peak_heap = max(peak_heap, prom.heap_used_bytes(prom.parse(prom.scrape(url, timeout=5))))
                heap_n += 1
            except Exception as e:  # a scrape that fails under load is reported, not hidden
                errors.append(f"heap scrape: {type(e).__name__}: {e}")
            if now >= next_vt:
                try:
                    peak_vt = max(peak_vt, count_virtual_threads(pid, workdir))
                    vt_n += 1
                except Exception as e:
                    errors.append(f"thread dump: {type(e).__name__}: {e}")
                next_vt = now + THREADS_EVERY_S
            time.sleep(max(0.0, HEAP_EVERY_S - (time.monotonic() - now)))
    with open(out, "w") as f:
        json.dump({"peak_heap_used_mb": round(peak_heap / (1024 * 1024), 1), "peak_virtual_threads": peak_vt,
                   "heap_samples": heap_n, "thread_dumps": vt_n, "sample_errors": errors[:20],
                   "sample_error_count": len(errors)}, f, indent=1)


if __name__ == "__main__":
    main()
