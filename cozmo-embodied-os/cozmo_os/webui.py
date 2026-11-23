"""Web UI del Digital Twin: streaming MJPEG, eventi SSE, comandi JSON.

Pensata per essere aperta dal telefono sulla stessa rete: il pulsante
"Chiedi a Cozmo" invia un testo che il robot deve interpretare (L3), mentre
"Prendi / Rilascia" simulano la presa umana.
"""

from __future__ import annotations

import asyncio
import json
import os
import queue
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

import numpy as np

from .constants import CAMERA_H, CAMERA_W

WEB_PORT = int(os.environ.get("COZMO_WEB_PORT", "8760"))

PAGE = """<!doctype html>
<html lang="it"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Cozmo Digital Twin</title>
<style>
 body{margin:0;background:#0b0f0e;color:#dfe8e4;font-family:system-ui,sans-serif}
 header{padding:10px 14px;background:#111a17;border-bottom:1px solid #24332e;
        display:flex;gap:10px;align-items:center;justify-content:space-between}
 h1{font-size:16px;margin:0}
 .wrap{display:grid;grid-template-columns:1fr 320px;gap:12px;padding:12px}
 @media(max-width:760px){.wrap{grid-template-columns:1fr}}
 video{width:100%;background:#000;border-radius:10px}
 .card{background:#111a17;border:1px solid #24332e;border-radius:10px;padding:10px;
       margin-bottom:10px}
 button{background:#1f8a68;color:#04120d;border:0;border-radius:8px;padding:9px 12px;
        font-weight:600;margin:3px 4px 3px 0}
 button.ghost{background:#22302b;color:#cfe0d8}
 input{width:100%;padding:8px;border-radius:8px;border:1px solid #2d3c36;
       background:#0e1513;color:#eaf2ee;margin-bottom:6px}
 pre{margin:0;white-space:pre-wrap;font-size:11px;max-height:260px;overflow:auto}
 .row{display:flex;flex-wrap:wrap}
 .tag{display:inline-block;padding:2px 7px;border-radius:99px;background:#1d2a26;
      margin:2px;font-size:11px}
</style></head><body>
<header><h1>Cozmo · Digital Twin</h1><div id="stat">…</div></header>
<div class="wrap">
  <div>
    <div class="card"><video id="v" autoplay muted playsinline></video></div>
    <div class="card"><h3 style="margin:4px 0">Eventi</h3><pre id="ev">connessione…</pre></div>
  </div>
  <div>
    <div class="card">
      <h3 style="margin:4px 0">Controllo</h3>
      <div class="row">
        <button onclick="cmd('pickup')">Prendi</button>
        <button onclick="cmd('release')">Rilascia</button>
        <button onclick="cmd('cliff')">Soffio del tavolo</button>
        <button onclick="cmd('face_happy')">Faccia felice</button>
        <button onclick="cmd('face_question')">Faccia curiosa</button>
        <button onclick="cmd('speak_hello')">Voce</button>
      </div>
    </div>
    <div class="card">
      <h3 style="margin:4px 0">Chiedi a Cozmo</h3>
      <input id="q" placeholder="es. gira verso la tazza">
      <button onclick="ask()">Chiedi</button>
      <div id="ans" style="font-size:12px;margin-top:6px"></div>
    </div>
    <div class="card"><h3 style="margin:4px 0">Stato</h3><pre id="state">…</pre></div>
  </div>
</div>
<script>
const ev=document.getElementById('ev'), st=document.getElementById('state');
const es=new EventSource('/events');
es.onmessage=e=>{const lines=ev.textContent.split('\\n');lines.push(e.data);ev.textContent=lines.slice(-60).join('\\n');};
async function poll(){const j=await (await fetch('/api/state')).json();
 st.textContent=JSON.stringify(j,null,1);
 document.getElementById('stat').innerHTML='<span class="tag">t '+j.t.toFixed(1)+'s</span><span class="tag">L1 '+j.l1_ticks+' tick</span><span class="tag">umore '+j.mood+'</span><span class="tag">'+(j.estop?'ESTOP':'attivo')+'</span>';
 setTimeout(poll,500);}
poll();
async function cmd(a){await fetch('/api/cmd',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:a})});}
async function ask(){const q=document.getElementById('q').value;const r=await (await fetch('/api/ask',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({text:q})})).json();document.getElementById('ans').textContent=JSON.stringify(r);}
// stream MJPEG
const v=document.getElementById('v');
fetch('/stream.mjpg').then(async r=>{const reader=r.body.getReader();const ct=new MediaSource();
 // fallback: uso <img> se MediaSource non gestisce multipart
}).catch(()=>{v.outerHTML='<img id="v" src="/stream.mjpg">';});
</script>
</body></html>"""


def make_handler(robot, loop):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *a):  # silenzioso: gli eventi stanno nel bus
            pass

        # ---------------------------------------------------------- helper
        def _json(self, obj, code=200):
            body = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _text(self, s, code=200, ctype="text/plain; charset=utf-8"):
            body = s.encode()
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        # ------------------------------------------------------------ GET
        def do_GET(self):
            u = urlparse(self.path)
            if u.path == "/":
                return self._text(PAGE, ctype="text/html; charset=utf-8")
            if u.path == "/api/state":
                return self._json(self.state_snapshot())
            if u.path == "/api/events":
                return self._json(robot.events.snapshot())
            if u.path == "/events":
                # SSE: thread-sync queue ponted notificata dal loop asyncio.
                # Il queue sincrona e' il confine fra i due mondi: il worker
                # asyncio chiama put_nowait, il thread HTTP attende con timeout
                # per poter notare la disconnessione del client (un `await`
                # qui dentro non compilerebbe: do_GET e' sincrona).
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Cache-Control", "no-cache")
                self.end_headers()
                q: "queue.Queue" = queue.Queue(maxsize=200)

                def _pump():
                    try:
                        ev_q = robot.events.subscribe()
                    except Exception:
                        return
                    while not self._sse_dead.is_set():
                        got = robot.events.drain_once(ev_q)
                        for item in got:
                            try:
                                q.put_nowait(item)
                            except queue.Full:
                                break
                        if not got:
                            time.sleep(0.2)

                self._sse_dead = threading.Event()
                pump = threading.Thread(target=_pump, daemon=True)
                pump.start()
                try:
                    while not self._sse_dead.is_set():
                        try:
                            ev = q.get(timeout=1.0)
                        except queue.Empty:
                            continue
                        payload = json.dumps(ev)
                        self.wfile.write(f"data: {payload}\n\n".encode())
                        self.wfile.flush()
                        if q.empty():
                            self.wfile.write(b": ping\n\n")
                            self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError, OSError):
                    pass
                finally:
                    self._sse_dead.set()
                return
            if u.path == "/stream.mjpg":
                self.send_response(200)
                self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
                self.end_headers()
                try:
                    while True:
                        frame = robot.twin.camera_frame()
                        # JPEG minimale: PPM raw -> il browser non lo legge;
                        # per il twin i consumer usano /api/frame (PNG)
                        png = _png_bytes(frame)
                        self.wfile.write(b"--frame\r\nContent-Type: image/png\r\n"
                                         b"Content-Length: " + str(len(png)).encode() +
                                         b"\r\n\r\n" + png + b"\r\n")
                        self.wfile.flush()
                        time.sleep(0.1)
                except (BrokenPipeError, ConnectionResetError):
                    return
            if u.path == "/api/frame":
                return self._text("", 404)
            return self._text("not found", 404)

        # ----------------------------------------------------------- POST
        def do_POST(self):
            u = urlparse(self.path)
            n = int(self.headers.get("Content-Length", "0") or 0)
            raw = self.rfile.read(n) if n else b"{}"
            try:
                data = json.loads(raw or b"{}")
            except json.JSONDecodeError:
                return self._json({"ok": False, "error": "json non valido"}, 400)
            action = data.get("action", "")
            if action == "ask":
                # L3 corteccia: il testo dell'utente -> piano + grounding.
                # La risposta e' sincrona lato server ma il cervello gira nel
                # loop asyncio: si inoltra e si attende il risultato.
                text = str(data.get("text", "")).strip()
                if not text:
                    return self._json({"ok": False, "error": "testo vuoto"}, 400)
                try:
                    reply = self._run_async(robot.ask(text))
                except Exception as e:
                    return self._json({"ok": False, "error": str(e)}, 500)
                return self._json({"ok": True, "answer": reply})
            if action == "pickup":
                robot.pickup(True)
            elif action == "release":
                robot.pickup(False)
            elif action == "cliff":
                robot.drop_off_table()
            elif action.startswith("face_"):
                robot.face_name(action[5:])
            elif action.startswith("speak_"):
                robot.speak(action[6:])
            elif action == "move":
                robot.twin.drive(float(data.get("vl", 0)), float(data.get("vr", 0)))
            else:
                return self._json({"ok": False, "error": f"azione sconosciuta: {action}"}, 400)
            return self._json({"ok": True, "action": action})

        # --------------------------------------------------------- helpers
        def _run_async(self, coro):
            """Esegue una coroutine sul loop asyncio del robot dal thread HTTP."""
            fut = asyncio.run_coroutine_threadsafe(coro, loop)
            return fut.result(timeout=60.0)
        def state_snapshot(self):
            st = robot.twin.state()
            return {
                "t": time.time(),
                "l1_ticks": robot.l1_ticks,
                "pose": {"x": st.x, "y": st.y, "theta": st.theta},
                "head": st.head, "lift": st.lift,
                "battery_mv": st.battery_mv,
                "cliff": st.cliff, "picked_up": st.picked_up,
                "mood": robot.limbic.mood.label(),
                "estop": robot.safety.stopped,
                "estop_reason": robot.safety.reason,
                "dbg": getattr(robot, "_dbg_last", {}),
                "objects": [{"kind": o.kind, "x": o.x, "y": o.y} for o in robot.twin.objects],
            }

    return Handler


def _png_bytes(gray: np.ndarray) -> bytes:
    """PNG minimale (8-bit grayscale) senza dipendenze: zlib + crc."""
    import struct
    import zlib

    h, w = gray.shape
    raw = b"".join(b"\x00" + gray[y].tobytes() for y in range(h))

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data +
                struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 0, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 6))
            + chunk(b"IEND", b""))


def serve(robot, port: int = WEB_PORT):
    """Avvia il server HTTP in un thread (le API chiamano il loop asyncio)."""
    loop = asyncio.get_event_loop()
    handler = make_handler(robot, loop)
    httpd = ThreadingHTTPServer(("127.0.0.1", port), handler)
    t = threading.Thread(target=httpd.serve_forever, daemon=True)
    t.start()
    return httpd
