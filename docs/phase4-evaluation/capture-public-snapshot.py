"""Read-only LOCAL_TEST public directory capture. Run only against an existing local Demo.

Usage: python3 docs/phase4-evaluation/capture-public-snapshot.py URL OUTPUT DATABASE_LABEL
Database label must be supplied from verified runtime configuration; HTTP does not prove MySQL.
No credentials, model calls, seeding, or business writes.
"""
import datetime
import hashlib
import json
import pathlib
import subprocess
import sys
import urllib.parse
import urllib.request

url, output, database = sys.argv[1:]
parsed = urllib.parse.urlparse(url)
if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"} or parsed.username or parsed.password or parsed.query:
    raise SystemExit("Only uncredentialed loopback HTTP public product endpoint is allowed")
if parsed.path != "/api/shop/v1/products":
    raise SystemExit("Expected public product endpoint")
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
raw = opener.open(url, timeout=20).read()
products = json.loads(raw)
if len(products) != 65 or any(p["evidence"]["dataOrigin"] != "LOCAL_TEST" or len(p["skus"]) != 1 for p in products):
    raise SystemExit("v1 requires exactly 65 single-SKU LOCAL_TEST products")
category_names = {}
for product in products:
    code = product["skus"][0]["code"]
    if code.startswith(("TEST-BLUE-", "TEST-IVORY-", "TEST-GRAY-", "EXP-BLUE-", "EXP-IVORY-")):
        name = "上衣 女装"
    elif code.startswith(("TEST-BAG-", "EXP-BAG-")):
        name = "文件袋 日用与文具"
    elif code.startswith(("TEST-NOTE-", "EXP-NOTE-")):
        name = "纸质笔记本 日用与文具"
    else:
        raise SystemExit("Unknown fixed fixture code")
    key = str(product["categoryId"])
    if key in category_names and category_names[key] != name:
        raise SystemExit("Unexpected shared category across fixture groups")
    category_names[key] = name
snapshot = {
    "metadata": {
        "capturedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "source": url,
        "database": database,
        "productCount": len(products),
        "categoryMapSource": "TestSupplyConfig fixture taxonomy mapped by observed categoryId, not database taxonomy export",
        "codeRevision": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "publicResponseSha256": hashlib.sha256(raw).hexdigest(),
    },
    "categoryNames": category_names,
    "products": products,
}
path = pathlib.Path(output)
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"productCount": len(products), "databaseLabel": database, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}))
