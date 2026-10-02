"""Read-only loopback public catalog HTTP latency probe; no guide/model calls."""
import datetime
import json
import math
import pathlib
import sys
import time
import urllib.parse
import urllib.request

url, output, database = sys.argv[1:]
parsed = urllib.parse.urlparse(url)
if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"} or parsed.username or parsed.password or parsed.query or parsed.path != "/api/shop/v1/products":
    raise SystemExit("Only uncredentialed loopback public product endpoint is allowed")
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
for _ in range(5):
    opener.open(url, timeout=15).read()
latencies, counts = [], set()
for _ in range(30):
    start = time.perf_counter()
    raw = opener.open(url, timeout=15).read()
    latencies.append((time.perf_counter() - start) * 1000)
    products = json.loads(raw)
    if any(p["evidence"]["dataOrigin"] != "LOCAL_TEST" for p in products):
        raise SystemExit("Expected LOCAL_TEST public fixtures")
    counts.add(len(products))
ordered = sorted(latencies)
result = {
    "capturedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "endpoint": url, "database": database, "samples": 30, "warmup": 5,
    "catalogCounts": sorted(counts),
    "p50Ms": ordered[math.ceil(len(ordered) * .50) - 1],
    "p95Ms": ordered[math.ceil(len(ordered) * .95) - 1],
    "latencyScope": "HTTP GET public catalog, database eligibility, mapping and serialization; no intent model, search or projection",
    "samplesMs": latencies,
}
path = pathlib.Path(output)
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({k: v for k, v in result.items() if k != "samplesMs"}))
