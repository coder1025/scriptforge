#!/usr/bin/env python3
"""Exercise a running DEMO server through HTTP. Standard library only; never calls a real model."""
import argparse
import json
import os
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--timeout", type=int, default=90)
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    token = os.environ.get("ADMIN_TOKEN", "")

    def request(path, data=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(base + path, headers=headers,
                                     data=json.dumps(data).encode() if data is not None else None)
        with client.open(req, timeout=10) as response:
            return json.load(response)

    config = request("/api/config")
    if config.get("name") != "ScriptForge" or config.get("mode") != "DEMO":
        raise SystemExit("Smoke test requires a ScriptForge DEMO server; no MODEL task was created.")
    fixture = Path(__file__).resolve().parents[1] / "docs/examples/demo-task.json"
    body = json.loads(fixture.read_text(encoding="utf-8"))
    body["requestId"] = "smoke-" + str(uuid.uuid4())
    started = time.monotonic()
    task = request("/api/tasks", body)
    if request("/api/tasks", body)["id"] != task["id"]:
        raise SystemExit("Create idempotency failed")
    while task["status"] in ("QUEUED", "RUNNING", "REVIEWING"):
        if time.monotonic() - started > args.timeout:
            raise SystemExit("Timed out; saved task remains available: " + task["id"])
        time.sleep(0.5)
        task = request("/api/tasks/" + task["id"])
    assert task["status"] == "DEMO_READY", task.get("error")
    assert task["draftRevision"] == 4 and task["reviewRound"] == 2
    assert task["report"]["draftRevision"] == 4 and task["report"]["verdict"] == "PASS"
    assert task["reservedTokens"] == 0
    assert not any("承重100公斤" in s["text"] for s in task["sections"])
    print(json.dumps({"result": "PASS", "mode": "DEMO", "taskId": task["id"],
                      "draftRevision": task["draftRevision"], "reviewRound": task["reviewRound"],
                      "modelCalls": task["modelCalls"], "fixtureTokens": task["usedTokens"],
                      "elapsedSeconds": round(time.monotonic() - started, 2)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        raise SystemExit("HTTP " + str(error.code) + ": check server mode, readiness and ADMIN_TOKEN")
