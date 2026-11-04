#!/usr/bin/env python3
"""A fake of the scan API of Tisma Cloud, only for the app's tests (CloudClientServerTest).

It answers as Tisma Cloud does (its README, «Escaneos 3D (Tisma Scanner)»): login, pieces of a scan with the 409 that
says where to go on, complete -> a job, the job queued -> running -> done, and the result zip. It does not rebuild
anything: the result has a small pieza.stl and its report. One account: e2e@tisma.test / clave-e2e.

    python3 fake_tisma_cloud.py --port N --cert cert.pem --key key.pem [--chunk BYTES]
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
    def __init__(self, chunk: int) -> None:
        self.chunk = chunk
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

        def do_POST(self) -> None:
            path = urlparse(self.path).path
            data = self.body()
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
                j = json.loads(data)
                sid = str(uuid.uuid4())
                state.scans[sid] = {"id": sid, "size": int(j["size"]), "data": bytearray(), "job_id": None}
                return self.send(201, {"id": sid, "size": int(j["size"]), "received": 0, "chunk_size": state.chunk,
                                       "job_id": None})
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
    a = ap.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", a.port), handler(State(a.chunk)))
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
