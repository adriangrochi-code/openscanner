#!/usr/bin/env python3
"""
Tisma Server — recibe escaneos de la app Tisma Scanner, los procesa y devuelve la pieza (STL).

Pensado para ejecutarse como TismaServer.exe en tismaservidores.net (también funciona con
`python tisma_server.py` en Linux/Windows). Usa sólo la biblioteca estándar para HTTP.

API (todas menos /api/health requieren "Authorization: Bearer <clave>"):
  GET    /api/health                      estado y versión
  POST   /api/scans          {"size","name"} -> {"id","chunk_size"}   crea una subida
  PUT    /api/scans/<id>/data?offset=N    trozo del .zip (reanudable: si N no cuadra -> 409 con lo recibido)
  POST   /api/scans/<id>/complete         subida terminada -> a la cola de proceso
  GET    /api/scans/<id>                  estado, progreso, registro y resultados
  GET    /api/scans/<id>/files/<nombre>   descargar un resultado (pieza.stl, informe…)
  DELETE /api/scans/<id>                  borrar el trabajo
  GET    /api/scans                       últimos trabajos

Uso:
  TismaServer.exe                  arranca (la 1ª vez crea tisma_server.json con una clave nueva)
  TismaServer.exe --config ruta    otra configuración
  TismaServer.exe --selftest       prueba completa en local (subida reanudable + proceso + malla)
"""
import argparse
import contextlib
import hmac
import json
import os
import queue
import re
import secrets
import shutil
import ssl
import sys
import threading
import time
import traceback
import urllib.parse
import uuid
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

VERSION = "0.1.0"

# Al congelar con PyInstaller, los módulos del pipeline viajan dentro del .exe; en desarrollo
# están en ../tools.
HERE = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
for p in (HERE, HERE.parent / "tools", HERE / "tools"):
    if p.exists() and str(p) not in sys.path:
        sys.path.insert(0, str(p))

BASE = Path(sys.executable).resolve().parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parent
ID_RE = re.compile(r"^[0-9a-f]{32}$")
RESULT_NAMES = {"pieza.stl", "pieza.ply", "pieza_informe.json", "pieza_nube.ply", "mal_registradas.txt",
                "textura.zip", "log.txt", "quality_summary.json"}

DEFAULT_CONFIG = {
    "host": "0.0.0.0",
    "port": 8443,
    "api_keys": [],
    "data_dir": "datos",
    "colmap": "",                 # p.ej. C:\\COLMAP\\bin\\colmap.exe (vacío = buscar en el PATH)
    "openmvs_dir": "",            # carpeta con DensifyPointCloud.exe, etc. (vacío = PATH)
    "dense": "openmvs",           # openmvs | colmap (requiere CUDA) | none
    "chunk_mb": 16,
    "max_upload_gb": 8,
    "max_unzipped_gb": 12,
    "retention_days": 14,
    "tls_cert": "",               # PEM; vacío = HTTP (poner detrás de IIS/Caddy con HTTPS)
    "tls_key": "",
    "allow_without_colmap": False,  # sólo pruebas: procesa imágenes aunque no haya COLMAP
    "models_dir": "modelos",      # modelos de IA para la app (p.ej. da3_small.onnx), GET /models/<archivo>
}


def log(msg):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), msg, file=sys.__stdout__, flush=True)


# ------------------------------------------------------------------ configuración

def load_config(path: Path) -> dict:
    if not path.exists():
        cfg = dict(DEFAULT_CONFIG)
        cfg["api_keys"] = [secrets.token_urlsafe(32)]
        path.write_text(json.dumps(cfg, indent=2))
        log(f"Configuración nueva en {path}")
        log(f"CLAVE DE API (ponla en la app: Ajustes → Nube): {cfg['api_keys'][0]}")
    cfg = {**DEFAULT_CONFIG, **json.loads(path.read_text())}
    if not cfg["api_keys"]:
        raise SystemExit("La configuración no tiene ninguna clave en api_keys")
    data = Path(cfg["data_dir"])
    cfg["data_dir"] = str(data if data.is_absolute() else path.parent / data)
    models = Path(cfg["models_dir"])
    cfg["models_dir"] = str(models if models.is_absolute() else path.parent / models)
    return cfg


# ------------------------------------------------------------------ trabajos

class Jobs:
    """Trabajos en disco: <data>/scans/<id>/{upload.zip, status.json, work/, results/, log.txt}."""

    def __init__(self, cfg):
        self.cfg = cfg
        self.root = Path(cfg["data_dir"]) / "scans"
        self.root.mkdir(parents=True, exist_ok=True)
        # Reentrante: TODAS las lecturas/escrituras de status.json pasan por él. En Windows no se
        # puede reemplazar un archivo que otro hilo tiene abierto (WinError 5): leer el estado
        # mientras el trabajador lo actualiza mataba al trabajador.
        self.lock = threading.RLock()
        self.queue = queue.Queue()
        # Trabajos que quedaron en cola o a medias si el servidor se reinició.
        for d in self.root.iterdir():
            st = self._read(d)
            if st and st["state"] in ("queued", "processing"):
                st["state"] = "queued"; self._write(d, st); self.queue.put(d.name)

    def dir(self, job_id):
        if not ID_RE.match(job_id or ""):
            raise KeyError(job_id)
        d = self.root / job_id
        if not d.exists():
            raise KeyError(job_id)
        return d

    def _read(self, d: Path):
        with self.lock:
            try:
                return json.loads((d / "status.json").read_text())
            except (OSError, ValueError):
                return None

    def _write(self, d: Path, st):
        with self.lock:
            tmp = d / "status.json.tmp"
            tmp.write_text(json.dumps(st, indent=2))
            # Windows: un antivirus/indexador puede tener el archivo abierto un instante.
            for attempt in range(40):
                try:
                    os.replace(tmp, d / "status.json")
                    return
                except PermissionError:
                    if attempt == 39:
                        raise
                    time.sleep(0.025)

    def status(self, job_id):
        d = self.dir(job_id)
        st = self._read(d) or {}
        logf = d / "log.txt"
        if logf.exists():
            st["log_tail"] = logf.read_text(errors="replace").splitlines()[-25:]
        res = d / "results"
        st["results"] = sorted(p.name for p in res.iterdir()) if res.exists() else []
        rep = res / "pieza_informe.json"
        if rep.exists():
            st["report"] = json.loads(rep.read_text())
        return st

    def update(self, job_id, **kw):
        with self.lock:
            d = self.dir(job_id)
            st = self._read(d) or {}
            st.update(kw, updated=time.time())
            self._write(d, st)
            return st

    def create(self, size, name):
        if size <= 0 or size > self.cfg["max_upload_gb"] * 1e9:
            raise ValueError(f"tamaño no permitido (máx. {self.cfg['max_upload_gb']} GB)")
        job_id = uuid.uuid4().hex
        d = self.root / job_id
        d.mkdir()
        (d / "upload.zip").touch()
        self._write(d, {"id": job_id, "state": "uploading", "size": size, "received": 0,
                        "name": str(name)[:120], "created": time.time(), "updated": time.time(),
                        "stage": "subiendo"})
        return job_id

    def append(self, job_id, offset, data: bytes):
        with self.lock:
            d = self.dir(job_id)
            st = self._read(d)
            if st["state"] != "uploading":
                raise ValueError("la subida ya terminó")
            if offset != st["received"]:
                return st["received"], False          # el cliente reanuda desde aquí
            if st["received"] + len(data) > st["size"]:
                raise ValueError("más datos de los anunciados")
            with open(d / "upload.zip", "ab") as f:
                f.write(data)
            st["received"] += len(data); st["updated"] = time.time()
            self._write(d, st)
            return st["received"], True

    def complete(self, job_id):
        with self.lock:
            d = self.dir(job_id)
            st = self._read(d)
            if st["state"] != "uploading":
                return st
            if st["received"] != st["size"]:
                raise ValueError(f"faltan datos: {st['received']}/{st['size']}")
            check_zip(d / "upload.zip", self.cfg["max_unzipped_gb"] * 1e9)
            st.update(state="queued", stage="en cola", updated=time.time())
            self._write(d, st)
        self.queue.put(job_id)
        return st

    def delete(self, job_id):
        shutil.rmtree(self.dir(job_id), ignore_errors=True)

    def list(self, n=50):
        sts = [s for s in (self._read(d) for d in self.root.iterdir()) if s]
        return sorted(sts, key=lambda s: -s.get("created", 0))[:n]

    def cleanup(self):
        limit = time.time() - self.cfg["retention_days"] * 86400
        for d in self.root.iterdir():
            st = self._read(d)
            if st and st.get("updated", 0) < limit:
                shutil.rmtree(d, ignore_errors=True)


def check_zip(path: Path, max_unzipped):
    """Comprobaciones antes de procesar: zip válido, sin rutas peligrosas ni 'bomba' de descompresión."""
    try:
        with zipfile.ZipFile(path) as z:
            total = 0
            for info in z.infolist():
                n = info.filename
                if n.startswith(("/", "\\")) or ".." in Path(n).parts or ":" in n:
                    raise ValueError(f"ruta no permitida en el zip: {n}")
                total += info.file_size
            if total > max_unzipped:
                raise ValueError("el zip descomprimido es demasiado grande")
            if not any(i.filename.endswith("session.json") for i in z.infolist()):
                raise ValueError("no es un escaneo de Tisma Scanner (falta session.json)")
    except zipfile.BadZipFile:
        raise ValueError("el archivo no es un zip válido")


class _Tee:
    """stdout del trabajo -> log.txt (y la última línea como 'etapa' visible en la app)."""

    def __init__(self, f, on_line):
        self.f, self.on_line, self.buf = f, on_line, ""

    def write(self, s):
        self.f.write(s); self.f.flush()
        self.buf += s
        while "\n" in self.buf:
            line, self.buf = self.buf.split("\n", 1)
            if line.strip():
                self.on_line(line.strip())
        return len(s)

    def flush(self):
        self.f.flush()


def stage_of(line):
    for key, txt in (("[hdr]", "preparando imágenes"), ("[sfm]", "fotogrametría (SfM)"), ("[run]", None),
                     ("[ins]", "comprobando con la INS"), ("[scale]", "escala métrica"),
                     ("[dense]", "nube densa"), ("[post]", "postprocesado de la malla")):
        if line.startswith(key):
            if key == "[run]":
                tool = Path(line.split()[1]).stem if len(line.split()) > 1 else ""
                return {"colmap": "fotogrametría (COLMAP)", "DensifyPointCloud": "nube densa",
                        "ReconstructMesh": "malla", "RefineMesh": "refinando malla",
                        "TextureMesh": "texturizando"}.get(tool, "procesando")
            return txt
    return None


def worker(jobs: Jobs, cfg):
    import reconstruct
    if cfg["colmap"]:
        reconstruct.COLMAP_EXE = cfg["colmap"]
    if cfg["openmvs_dir"]:
        reconstruct.OPENMVS_DIR = cfg["openmvs_dir"]
    have_colmap = bool(shutil.which(reconstruct.COLMAP_EXE) or Path(reconstruct.COLMAP_EXE).exists())
    while True:
        job_id = jobs.queue.get()
        # Pase lo que pase con un trabajo, el trabajador sigue con el siguiente (antes una
        # excepción fuera del pipeline lo mataba y la cola se quedaba parada para siempre).
        try:
            try:
                d = jobs.dir(job_id)
            except KeyError:
                continue
            jobs.update(job_id, state="processing", stage="empezando", started=time.time())
            log(f"[{job_id[:8]}] procesando")
            work = d / "work"
            res = d / "results"
            res.mkdir(exist_ok=True)
            argv = [str(d / "upload.zip"), "--work", str(work), "--dense", cfg["dense"]]
            if not have_colmap:
                if not cfg["allow_without_colmap"]:
                    jobs.update(job_id, state="error", stage="falta COLMAP en el servidor",
                                error="COLMAP no está instalado/configurado (campo 'colmap' en tisma_server.json)")
                    continue
                argv.append("--prepare-only")
            last = {"t": 0.0}

            def on_line(line):
                st = stage_of(line)
                now = time.time()
                if st or now - last["t"] > 2:
                    last["t"] = now
                    jobs.update(job_id, **({"stage": st} if st else {}), last_line=line[:200])

            try:
                with open(d / "log.txt", "w", encoding="utf-8") as lf:
                    tee = _Tee(lf, on_line)
                    with contextlib.redirect_stdout(tee), contextlib.redirect_stderr(tee):
                        reconstruct.main(argv)
                collect_results(work, res)
                shutil.copy(d / "log.txt", res / "log.txt")
                jobs.update(job_id, state="done", stage="terminado", finished=time.time())
                log(f"[{job_id[:8]}] terminado")
            except BaseException as e:   # SystemExit del pipeline incluido
                with open(d / "log.txt", "a", encoding="utf-8") as lf:
                    traceback.print_exc(file=lf)
                jobs.update(job_id, state="error", stage="error", error=str(e)[:500])
                log(f"[{job_id[:8]}] error: {e}")
            finally:
                # El .zip ya no hace falta (los resultados quedan): ahorra disco.
                if (d / "status.json").exists() and jobs._read(d).get("state") == "done":
                    with contextlib.suppress(OSError):
                        (d / "upload.zip").unlink()
        except Exception as e:
            log(f"[{job_id[:8]}] fallo inesperado del trabajador: {e!r}")
            with contextlib.suppress(Exception):
                jobs.update(job_id, state="error", stage="error", error=f"fallo interno: {e}"[:500])


def collect_results(work: Path, res: Path):
    for name in ("pieza.stl", "pieza.ply", "pieza_informe.json", "pieza_nube.ply", "mal_registradas.txt"):
        if (work / name).exists():
            shutil.copy(work / name, res / name)
    raw = work / "raw"
    for qs in raw.glob("*/quality_summary.json") if raw.exists() else []:
        shutil.copy(qs, res / "quality_summary.json")
    # Malla texturizada de OpenMVS (para verla con color), en un zip.
    und = work / "undistorted"
    tex = [p for p in und.glob("*texture*") if p.suffix.lower() in (".obj", ".mtl", ".png", ".jpg")] if und.exists() else []
    if tex:
        with zipfile.ZipFile(res / "textura.zip", "w", zipfile.ZIP_DEFLATED) as z:
            for p in tex:
                z.write(p, p.name)


# ------------------------------------------------------------------ HTTP

def make_handler(jobs: Jobs, cfg):
    keys = [k.encode() for k in cfg["api_keys"]]
    chunk_max = cfg["chunk_mb"] * 1024 * 1024 + 1024

    class H(BaseHTTPRequestHandler):
        server_version = f"TismaServer/{VERSION}"
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            log(f"{self.client_address[0]} {fmt % args}")

        def _json(self, code, obj):
            body = json.dumps(obj, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _auth(self):
            h = self.headers.get("Authorization", "")
            tok = h[7:].encode() if h.startswith("Bearer ") else b""
            if not any(hmac.compare_digest(tok, k) for k in keys):
                self._json(401, {"error": "clave de API no válida"})
                return False
            return True

        def _body(self, limit):
            n = int(self.headers.get("Content-Length") or 0)
            if n > limit:
                raise ValueError("cuerpo demasiado grande")
            return self.rfile.read(n) if n else b""

        def _route(self):
            u = urllib.parse.urlparse(self.path)
            return [p for p in u.path.split("/") if p], urllib.parse.parse_qs(u.query)

        def do_GET(self):
            parts, _ = self._route()
            try:
                if parts == ["api", "health"]:
                    return self._json(200, {"ok": True, "version": VERSION, "queue": jobs.queue.qsize()})
                if not self._auth():
                    return
                if parts == ["api", "scans"]:
                    return self._json(200, {"scans": jobs.list()})
                # Modelos de IA para el móvil (Depth Anything 3 Small, Apache-2.0): sólo .onnx de la carpeta.
                if len(parts) == 2 and parts[0] == "models":
                    name = parts[1]
                    f = Path(cfg["models_dir"]) / name
                    if not re.fullmatch(r"[A-Za-z0-9._-]+\.onnx", name) or not f.is_file():
                        return self._json(404, {"error": "modelo no disponible"})
                    self.send_response(200)
                    self.send_header("Content-Type", "application/octet-stream")
                    self.send_header("Content-Length", str(f.stat().st_size))
                    self.end_headers()
                    with open(f, "rb") as fh:
                        shutil.copyfileobj(fh, self.wfile)
                    return
                if len(parts) == 3 and parts[:2] == ["api", "scans"]:
                    return self._json(200, jobs.status(parts[2]))
                if len(parts) == 5 and parts[:2] == ["api", "scans"] and parts[3] == "files":
                    name = parts[4]
                    if name not in RESULT_NAMES:
                        return self._json(404, {"error": "archivo no disponible"})
                    f = jobs.dir(parts[2]) / "results" / name
                    if not f.exists():
                        return self._json(404, {"error": "todavía no existe"})
                    self.send_response(200)
                    self.send_header("Content-Type", "application/octet-stream")
                    self.send_header("Content-Length", str(f.stat().st_size))
                    self.send_header("Content-Disposition", f'attachment; filename="{name}"')
                    self.end_headers()
                    with open(f, "rb") as fh:
                        shutil.copyfileobj(fh, self.wfile)
                    return
                self._json(404, {"error": "no existe"})
            except KeyError:
                self._json(404, {"error": "trabajo no encontrado"})

        def do_POST(self):
            parts, _ = self._route()
            if not self._auth():
                return
            try:
                if parts == ["api", "scans"]:
                    body = json.loads(self._body(4096) or b"{}")
                    job_id = jobs.create(int(body.get("size", 0)), body.get("name", "escaneo"))
                    return self._json(201, {"id": job_id, "chunk_size": cfg["chunk_mb"] * 1024 * 1024})
                if len(parts) == 4 and parts[:2] == ["api", "scans"] and parts[3] == "complete":
                    self._body(4096)
                    st = jobs.complete(parts[2])
                    return self._json(202, {"state": st["state"], "queue": jobs.queue.qsize()})
                self._json(404, {"error": "no existe"})
            except KeyError:
                self._json(404, {"error": "trabajo no encontrado"})
            except (ValueError, json.JSONDecodeError) as e:
                self._json(400, {"error": str(e)})

        def do_PUT(self):
            parts, q = self._route()
            if not self._auth():
                return
            try:
                if len(parts) == 4 and parts[:2] == ["api", "scans"] and parts[3] == "data":
                    offset = int(q.get("offset", ["-1"])[0])
                    data = self._body(chunk_max)
                    received, ok = jobs.append(parts[2], offset, data)
                    return self._json(200 if ok else 409, {"received": received})
                self._json(404, {"error": "no existe"})
            except KeyError:
                self._json(404, {"error": "trabajo no encontrado"})
            except ValueError as e:
                self._json(400, {"error": str(e)})

        def do_DELETE(self):
            parts, _ = self._route()
            if not self._auth():
                return
            try:
                if len(parts) == 3 and parts[:2] == ["api", "scans"]:
                    jobs.delete(parts[2])
                    return self._json(200, {"deleted": parts[2]})
                self._json(404, {"error": "no existe"})
            except KeyError:
                self._json(404, {"error": "trabajo no encontrado"})

    return H


def serve(cfg, ready: threading.Event = None):
    jobs = Jobs(cfg)
    # Un solo trabajador: el registro de cada trabajo redirige la salida estándar del proceso
    # (compartida) y, además, la GPU procesa de uno en uno. "workers" queda para el futuro.
    threading.Thread(target=worker, args=(jobs, cfg), daemon=True).start()

    def janitor():
        while True:
            with contextlib.suppress(Exception):
                jobs.cleanup()
            time.sleep(3600)
    threading.Thread(target=janitor, daemon=True).start()

    httpd = ThreadingHTTPServer((cfg["host"], int(cfg["port"])), make_handler(jobs, cfg))
    scheme = "http"
    if cfg["tls_cert"] and cfg["tls_key"]:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(cfg["tls_cert"], cfg["tls_key"])
        httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
        scheme = "https"
    log(f"Tisma Server {VERSION} en {scheme}://{cfg['host']}:{httpd.server_address[1]} · datos en {cfg['data_dir']}")
    cfg["port"] = httpd.server_address[1]
    if ready:
        ready.set()
    httpd.serve_forever()


# ------------------------------------------------------------------ autoprueba

def selftest():
    """
    Prueba de extremo a extremo en esta máquina (la usa también la compilación en Windows):
    servidor en un puerto libre, escaneo sintético subido en trozos con un corte simulado
    (reanudación), proceso del trabajo y postprocesado de una malla sintética (Open3D).
    """
    import tempfile
    import urllib.request
    import numpy as np
    tmp = Path(tempfile.mkdtemp(prefix="tisma_selftest_"))
    log(f"Autoprueba en {tmp}")
    scan_zip = make_demo_scan(tmp)
    key = "clave-de-prueba"
    cfg = {**DEFAULT_CONFIG, "host": "127.0.0.1", "port": 0, "api_keys": [key],
           "data_dir": str(tmp / "datos"), "chunk_mb": 1, "allow_without_colmap": True, "colmap": "no-existe",
           "models_dir": str(tmp / "modelos")}
    (tmp / "modelos").mkdir()
    (tmp / "modelos" / "prueba.onnx").write_bytes(b"\x08\x01" * 1000)
    ready = threading.Event()
    threading.Thread(target=serve, args=(cfg, ready), daemon=True).start()
    ready.wait(10)
    base = f"http://127.0.0.1:{cfg['port']}"

    def call(method, path, body=None, raw=None, auth=True):
        req = urllib.request.Request(base + path, method=method,
                                     data=raw if raw is not None else (json.dumps(body).encode() if body is not None else None))
        if auth:
            req.add_header("Authorization", f"Bearer {key}")
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    assert call("GET", "/api/health", auth=False)[0] == 200
    # Modelos para el móvil: con clave se descargan; sin clave, o con nombres raros, no.
    req = urllib.request.Request(base + "/models/prueba.onnx")
    req.add_header("Authorization", f"Bearer {key}")
    with urllib.request.urlopen(req, timeout=30) as r:
        assert r.read() == b"\x08\x01" * 1000
    assert call("GET", "/models/prueba.onnx", auth=False)[0] == 401
    assert call("GET", "/models/..%2Fconfig.onnx")[0] == 404
    assert call("GET", "/api/scans", auth=False)[0] == 401, "sin clave debe rechazar"
    data = scan_zip.read_bytes()
    code, r = call("POST", "/api/scans", {"size": len(data), "name": "autoprueba"})
    assert code == 201, r
    job, chunk = r["id"], r["chunk_size"]
    # Primer trozo, luego un trozo con offset equivocado (corte simulado): 409 y reanudar.
    call("PUT", f"/api/scans/{job}/data?offset=0", raw=data[:chunk])
    code, r = call("PUT", f"/api/scans/{job}/data?offset=0", raw=data[:chunk])
    assert code == 409 and r["received"] == min(chunk, len(data)), (code, r)
    off = r["received"]
    while off < len(data):
        code, r = call("PUT", f"/api/scans/{job}/data?offset={off}", raw=data[off:off + chunk])
        assert code == 200, r
        off = r["received"]
    assert call("POST", f"/api/scans/{job}/complete", {})[0] == 202
    for _ in range(300):
        code, st = call("GET", f"/api/scans/{job}")
        if st["state"] in ("done", "error"):
            break
        time.sleep(0.5)
    assert st["state"] == "done", st
    log(f"Trabajo de prueba: {st['state']} · {st.get('stage')} · resultados {st['results']}")

    # Segundo trabajo en condiciones "de Windows": reemplazar status.json falla a ratos
    # (archivo abierto por otro hilo o un antivirus) mientras 4 hilos consultan el estado sin
    # parar. Antes esto mataba al trabajador y la cola se quedaba parada.
    import random
    real_replace = os.replace
    rnd = random.Random(7)

    def flaky_replace(a, b):
        if rnd.random() < 0.3:
            raise PermissionError(5, "Access is denied (simulado)")
        return real_replace(a, b)
    os.replace = flaky_replace
    stop = threading.Event()
    try:
        code, r = call("POST", "/api/scans", {"size": len(data), "name": "autoprueba-windows"})
        job2 = r["id"]
        assert call("PUT", f"/api/scans/{job2}/data?offset=0", raw=data[:chunk])[0] == 200
        off = min(chunk, len(data))
        while off < len(data):
            code, r = call("PUT", f"/api/scans/{job2}/data?offset={off}", raw=data[off:off + chunk])
            off = r["received"]

        def hammer():
            while not stop.is_set():
                with contextlib.suppress(Exception):
                    call("GET", f"/api/scans/{job2}")
        pollers = [threading.Thread(target=hammer, daemon=True) for _ in range(4)]
        for t in pollers:
            t.start()
        assert call("POST", f"/api/scans/{job2}/complete", {})[0] == 202
        for _ in range(300):
            st2 = call("GET", f"/api/scans/{job2}")[1]
            if st2.get("state") in ("done", "error"):
                break
            time.sleep(0.3)
        assert st2.get("state") == "done", st2
    finally:
        stop.set()
        os.replace = real_replace
    log("Trabajo con reemplazos fallando al 30 % y consultas concurrentes: done")

    # Postprocesado (Open3D) sobre una caja sintética de 40×30×20 mm encima de una mesa.
    import postprocess
    import open3d as o3d
    rng = np.random.default_rng(0)
    pts = []
    for _ in range(40000):
        f = rng.integers(0, 5); u, v = rng.random(2)
        pts.append([[u * 40, v * 30, 20], [u * 40, 0, v * 20], [u * 40, 30, v * 20], [0, u * 30, v * 20], [40, u * 30, v * 20]][f])
    box = np.array(pts) + rng.normal(0, 0.05, (40000, 3))
    table = np.column_stack([rng.uniform(-50, 90, 30000), rng.uniform(-50, 80, 30000), rng.normal(0, 0.05, 30000)])
    table = table[~((table[:, 0] > -1) & (table[:, 0] < 41) & (table[:, 1] > -1) & (table[:, 1] < 31))]
    o3d.io.write_point_cloud(str(tmp / "denso.ply"), o3d.geometry.PointCloud(o3d.utility.Vector3dVector(np.vstack([box, table]) / 1000)))
    rep = postprocess.postprocess(tmp / "denso.ply", tmp / "pieza", units="m", up=np.array([0, 0, 1.0]))
    dims = sorted(rep["dimensions_mm_xyz"], reverse=True)
    assert rep["watertight"] and all(abs(a - b) < 1.0 for a, b in zip(dims, [40, 30, 20])), rep
    log(f"Malla de prueba: {dims} mm, cerrada, volumen {rep['volume_mm3']:.0f} mm³")
    shutil.rmtree(tmp, ignore_errors=True)
    log("AUTOPRUEBA OK")


def make_demo_scan(tmp: Path) -> Path:
    """Escaneo mínimo con el formato de la app (HDR y barrido de foco) para la autoprueba."""
    import cv2
    import numpy as np
    rng = np.random.default_rng(0)
    scan = tmp / "scan_demo"
    (scan / "images" / "0").mkdir(parents=True)
    tex = cv2.resize((rng.random((150, 200)) * 255).astype(np.uint8), (400, 300), interpolation=cv2.INTER_NEAREST)
    rows = ["frame,trigger,pass,camera_id,t_ns,file,width,height,px,py,pz,qw,qx,qy,qz,stationary"]
    meta, t = [], 1_000_000_000
    for k, ev in enumerate([-2, 0, 2]):
        img = np.clip(tex.astype(float) * 2.0 ** ev, 0, 255).astype(np.uint8)
        f = f"images/0/{t}.jpg"; cv2.imwrite(str(scan / f), img)
        rows.append(f"{k},1,FAR,0,{t},{f},400,300,0,0,0,1,0,0,0,1")
        meta.append({"camera_id": "0", "t_ns": t, "trigger": 1, "bracket_ev": ev}); t += 30_000_000
    for k, d in enumerate(np.linspace(8, 2, 5)):
        img = cv2.GaussianBlur(tex, (0, 0), 0.5 + abs(d - 5) * 0.8)
        f = f"images/0/{t}.jpg"; cv2.imwrite(str(scan / f), img)
        rows.append(f"{10 + k},2,CLOSE,0,{t},{f},400,300,0,0,0,1,0,0,0,1")
        meta.append({"camera_id": "0", "t_ns": t, "trigger": 2, "bracket_ev": 0,
                     "focus_sweep_target_diopters": float(d), "focus_distance_diopters": float(d)}); t += 30_000_000
    (scan / "frames.csv").write_text("\n".join(rows) + "\n")
    (scan / "capture_meta.jsonl").write_text("\n".join(json.dumps(m) for m in meta) + "\n")
    (scan / "session.json").write_text(json.dumps({"device": {"manufacturer": "TEST", "model": "demo"},
                                                   "cameras": [{"id": "0", "focus_distance_calibration": "approximate"}]}))
    z = tmp / "scan_demo.zip"
    with zipfile.ZipFile(z, "w") as zf:
        for p in scan.rglob("*"):
            if p.is_file():
                zf.write(p, f"scan_demo/{p.relative_to(scan).as_posix()}")
    return z


def _utf8_console():
    """Consola de Windows (cp1252) no puede imprimir ↔, ≈, ³…: forzar UTF-8 sin romper nunca."""
    for stream in (sys.stdout, sys.stderr, sys.__stdout__, sys.__stderr__):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass


def main():
    _utf8_console()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config", type=Path, default=BASE / "tisma_server.json")
    ap.add_argument("--selftest", action="store_true", help="prueba completa en local y salir")
    ap.add_argument("--version", action="store_true")
    a = ap.parse_args()
    if a.version:
        print(VERSION); return
    if a.selftest:
        try:
            selftest()
        except BaseException:
            traceback.print_exc()
            log("AUTOPRUEBA FALLIDA")
            sys.exit(1)
        return
    serve(load_config(a.config))


if __name__ == "__main__":
    import multiprocessing
    multiprocessing.freeze_support()   # TismaServer.exe: postprocess runs Poisson in a child process of this same exe
    main()
