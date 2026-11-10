#!/usr/bin/env python3
"""A fake of the scan API of Tisma Cloud, only for the app's tests (CloudClientServerTest).

It answers as Tisma Cloud does (its README, «Escaneos 3D (Tisma Scanner)»): login, pieces of a scan with the 409 that
says where to go on, complete -> a job, the job queued -> running -> done, and the result zip. It does not rebuild
anything: the result has a small pieza.stl and its report. One account: e2e@tisma.test / clave-e2e.

    python3 fake_tisma_cloud.py --port N --cert cert.pem --key key.pem [--chunk BYTES] [--flaky-jobs N]

With --flaky-jobs N, the first N requests for a job fail as a bad network would: the odd ones close the
connection without an answer, the even ones answer 503.
"""

from __future__ import annotations

import argparse
import io
import json
import ssl
import threading
import time
import uuid
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

EMAIL, PASSWORD = "e2e@tisma.test", "clave-e2e"


class State:
    def __init__(self, chunk: int, flaky_jobs: int = 0) -> None:
        self.chunk = chunk
        self.flaky_jobs = flaky_jobs
        self.full = 0
        self.files: dict[str, dict] = {}       # af_… -> item
        self.peer_keys: dict[str, str] = {}    # token -> public key (base64)
        self.tokens: set[str] = set()
        self.scans: dict[str, dict] = {}
        self.jobs: dict[str, dict] = {}
        self.lock = threading.Lock()


def result_zip() -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("pieza.stl", "solid pieza\nendsolid pieza\n")
        z.writestr("pieza_informe.json", json.dumps({"dimensions_mm_xyz": [40.2, 30.2, 20.3], "watertight": True}))
        z.writestr("log.txt", "[hdr] ok\n")
    return buf.getvalue()


def result_files() -> list[dict]:
    """The files of a scan result kept in the account: path, size and sha256, as in POST /v1/files."""
    import hashlib
    with zipfile.ZipFile(io.BytesIO(result_zip())) as z:
        return [{"path": n, "size": len(z.read(n)), "sha256": hashlib.sha256(z.read(n)).hexdigest()}
                for n in ("pieza.stl", "pieza_informe.json")]


def canonical(files: list[dict]) -> bytes:
    keep = [{k: f[k] for k in ("path", "role", "sha256", "size")} for f in sorted(files, key=lambda f: f["path"])]
    return json.dumps({"files": keep}, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def handler(state: State):
    class H(BaseHTTPRequestHandler):
        def log_message(self, *args) -> None:
            pass

        def send(self, code: int, obj: dict | None = None, raw: bytes | None = None,
                 kind: str = "application/json") -> None:
            body = raw if raw is not None else json.dumps(obj or {}).encode()
            self.send_response(code)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def body(self) -> bytes:
            length = self.headers.get("Content-Length")
            if length is None:
                return b""
            return self.rfile.read(int(length))

        def authed(self) -> bool:
            auth = self.headers.get("Authorization", "")
            if auth.startswith("Bearer ") and auth[7:] in state.tokens:
                return True
            self.send(401, {"detail": "Invalid access token"})
            return False

        def job_view(self, job: dict) -> dict:
            # The job moves on each time it is asked for: queued, running, done.
            job["polls"] += 1
            status = "queued" if job["polls"] < 2 else "running" if job["polls"] < 4 else "done"
            return {"id": job["id"], "kind": "scan", "job_class": "scan", "status": status,
                    "progress": {"queued": 0, "running": 50, "done": 100}[status], "error": "",
                    "summary": {"dimensions_mm_xyz": [40.2, 30.2, 20.3]} if status == "done" else {}}

        def server_full(self) -> bool:
            """The disk is full for this upload request (set by POST /test/full): 507 with Retry-After."""
            with state.lock:
                full, state.full = state.full, max(0, state.full - 1)
            if not full:
                return False
            body = json.dumps({"detail": "Insufficient Storage"}).encode()
            self.send_response(507)
            self.send_header("Content-Type", "application/json")
            self.send_header("Retry-After", "300")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return True

        def do_POST(self) -> None:
            path = urlparse(self.path).path
            data = self.body()
            if path == "/test/full":
                state.full = int(json.loads(data or b"{}").get("n", 1))
                return self.send(200, {"full": state.full})
            if path == "/v1/auth/login":
                j = json.loads(data or b"{}")
                if j.get("email") != EMAIL or j.get("password") != PASSWORD:
                    return self.send(401, {"detail": "Wrong e-mail or password"})
                token = "tu_" + uuid.uuid4().hex
                state.tokens.add(token)
                return self.send(200, {"token": token, "email": EMAIL})
            if not self.authed():
                return
            if path == "/v1/auth/logout":
                state.tokens.discard(self.headers["Authorization"][7:])
                return self.send(200, {"ok": True})
            if path == "/v1/scans":
                if self.server_full():
                    return
                j = json.loads(data)
                sid = str(uuid.uuid4())
                state.scans[sid] = {"id": sid, "size": int(j["size"]), "data": bytearray(), "job_id": None}
                return self.send(201, {"id": sid, "size": int(j["size"]), "received": 0, "chunk_size": state.chunk,
                                       "job_id": None})
            token = self.headers["Authorization"][7:]
            if path == "/v1/files":
                j = json.loads(data or b"{}")
                job = state.jobs.get(j.get("scan_job", ""))
                if job is None:
                    return self.send(404, {"detail": "No such scan job"})
                if job["polls"] < 4:
                    return self.send(409, {"detail": "The scan is not done"})
                for item in state.files.values():
                    if item["source_job"] == job["id"]:
                        return self.send(200, item)
                fid = "af_" + uuid.uuid4().hex[:12]
                item = {"id": fid, "name": j.get("name") or "scan", "kind": "scan", "source_job": job["id"],
                        "created_at": "2026-11-10T00:00:00Z", "size": sum(f["size"] for f in result_files()),
                        "files": result_files(), "published_model": None}
                state.files[fid] = item
                return self.send(201, item)
            if path == "/v1/library/peer-key":
                state.peer_keys[token] = json.loads(data)["public_key"]
                return self.send(201, {"ok": True})
            if path == "/v1/library/models":
                return self.publish(token, json.loads(data or b"{}"))
            parts = path.strip("/").split("/")
            if len(parts) == 4 and parts[:2] == ["v1", "scans"] and parts[3] == "complete":
                scan = state.scans.get(parts[2])
                if scan is None:
                    return self.send(404, {"detail": "No such scan"})
                if len(scan["data"]) != scan["size"]:
                    return self.send(409, {"detail": "The scan is not complete"})
                if scan["job_id"] is None:
                    jid = str(uuid.uuid4())
                    state.jobs[jid] = {"id": jid, "polls": 0}
                    scan["job_id"] = jid
                return self.send(201, {"id": scan["job_id"], "kind": "scan", "status": "queued"})
            self.send(404, {"detail": "Not Found"})

        def publish(self, token: str, j: dict) -> None:
            import base64
            from cryptography.exceptions import InvalidSignature
            from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
            if token not in state.peer_keys:
                return self.send(409, {"detail": "The peer key is not bound to this account"})
            item = state.files.get(j.get("from_file", ""))
            if item is None:
                return self.send(422, {"detail": "No such file"})
            kept = {f["path"]: f for f in item["files"]}
            files = j.get("files") or []
            roles = {"pieza.stl": "project", "pieza_informe.json": "analysis"}
            if not any(f.get("path") == "pieza.stl" for f in files) or any(
                    f.get("path") not in kept or f.get("role") != roles.get(f.get("path"))
                    or f.get("size") != kept[f["path"]]["size"] or f.get("sha256") != kept[f["path"]]["sha256"]
                    for f in files):
                return self.send(422, {"detail": "The files do not match the kept file"})
            if not (0 < len(j.get("name", "")) <= 120 and 0 < len(j.get("author_name", "")) <= 60):
                return self.send(422, {"detail": "Bad name or author"})
            try:
                Ed25519PublicKey.from_public_bytes(base64.b64decode(state.peer_keys[token])).verify(
                    base64.b64decode(j.get("manifest_signature", "")), canonical(files))
            except (InvalidSignature, ValueError):
                return self.send(422, {"detail": "Bad manifest signature"})
            if len(base64.b64decode(j.get("thumbnail_webp", "") or "")) > 64 * 1024:
                return self.send(422, {"detail": "Thumbnail over 64 KB"})
            lm = "lm_" + uuid.uuid4().hex[:12]
            item["published_model"] = lm
            return self.send(201, {"id": lm, "revision": 1, "name": j["name"], "license": j.get("license")})

        def do_PUT(self) -> None:
            url = urlparse(self.path)
            parts = url.path.strip("/").split("/")
            if self.headers.get("Content-Length") is None:
                return self.send(411, {"detail": "Content-Length is required"})
            data = self.body()
            if not self.authed():
                return
            if len(parts) == 4 and parts[:2] == ["v1", "scans"] and parts[3] == "data":
                scan = state.scans.get(parts[2])
                if scan is None:
                    return self.send(404, {"detail": "No such scan"})
                offset = int(parse_qs(url.query)["offset"][0])
                if self.server_full():
                    return
                with state.lock:
                    if offset != len(scan["data"]):
                        return self.send(409, {"detail": "The piece does not go there", "received": len(scan["data"])})
                    if len(data) > state.chunk:
                        return self.send(413, {"detail": "A piece is too large"})
                    scan["data"] += data
                    return self.send(200, {"received": len(scan["data"])})
            self.send(404, {"detail": "Not Found"})

        def do_GET(self) -> None:
            path = urlparse(self.path).path
            if path == "/v1/health":
                return self.send(200, {"ok": True})
            if not self.authed():
                return
            parts = path.strip("/").split("/")
            if len(parts) == 3 and parts[:2] == ["v1", "scans"]:
                scan = state.scans.get(parts[2])
                if scan is None:
                    return self.send(404, {"detail": "No such scan"})
                return self.send(200, {"id": scan["id"], "size": scan["size"], "received": len(scan["data"]),
                                       "chunk_size": state.chunk, "job_id": scan["job_id"]})
            if len(parts) == 3 and parts[:2] == ["v1", "jobs"] and parts[2] in state.jobs:
                with state.lock:
                    flaky, state.flaky_jobs = state.flaky_jobs, max(0, state.flaky_jobs - 1)
                if flaky % 2 == 1:  # the connection drops: no answer at all
                    self.close_connection = True
                    self.connection.close()
                    return
                if flaky:
                    return self.send(503, {"detail": "Service Unavailable"})
                return self.send(200, self.job_view(state.jobs[parts[2]]))
            if len(parts) == 4 and parts[:2] == ["v1", "jobs"] and parts[3] == "result" and parts[2] in state.jobs:
                return self.send(200, raw=result_zip(), kind="application/zip")
            self.send(404, {"detail": "Not Found"})

    return H


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--cert", required=True)
    ap.add_argument("--key", required=True)
    ap.add_argument("--chunk", type=int, default=64 * 1024)
    ap.add_argument("--flaky-jobs", type=int, default=0)
    a = ap.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", a.port), handler(State(a.chunk, a.flaky_jobs)))
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(a.cert, a.key)
    server.socket = ctx.wrap_socket(server.socket, server_side=True)
    print("ready", flush=True)
    while True:
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            break
        time.sleep(0.1)


if __name__ == "__main__":
    main()
