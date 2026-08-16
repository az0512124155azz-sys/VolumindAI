"""Loopback-only bridge API used by Volumind Remote.

All Fusion API work stays on Fusion's main thread. HTTP handler threads only
queue a small command and fire a custom event.
"""
import json
import os
import queue
import secrets
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
PORT = 8765
EVENT_ID = "VolumindAI_RemoteCommand"

_commands = queue.Queue(maxsize=32)
_events = queue.Queue(maxsize=64)
_server = None
_thread = None
_app = None
_mobile_connected = False
_relay_registered = False
_relay_message = "מחבר Windows לא מחובר"


def _settings_path():
    root = os.environ.get("APPDATA") or os.path.join(os.path.expanduser("~"), ".volumind")
    folder = os.path.join(root, "VolumindAI")
    os.makedirs(folder, exist_ok=True)
    return os.path.join(folder, "remote.json")


def _load_pairing_code():
    path = _settings_path()
    try:
        with open(path, "r", encoding="utf-8") as source:
            code = str(json.load(source).get("pairingCode", ""))
        if len(code) == 6 and code.isdigit():
            return code
    except Exception:
        pass
    code = f"{secrets.randbelow(1_000_000):06d}"
    try:
        with open(path, "w", encoding="utf-8") as destination:
            json.dump({"pairingCode": code}, destination)
    except Exception:
        pass
    return code


_pairing_code = _load_pairing_code()


def _json(handler, status, payload):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


class Handler(BaseHTTPRequestHandler):
    server_version = "VolumindLoopback/1.0"

    def log_message(self, _format, *_args):
        return

    def do_GET(self):
        if self.path == "/health":
            return _json(self, 200, {"ok": True, "service": "Volumind Fusion"})
        if self.path == "/pairing":
            return _json(self, 200, {"ok": True, "pairingCode": _pairing_code})
        if self.path == "/events":
            try:
                event = _events.get(timeout=25)
            except queue.Empty:
                event = {"type": "heartbeat"}
            return _json(self, 200, event)
        return _json(self, 404, {"ok": False, "error": "not found"})

    def do_POST(self):
        if self.path not in ("/command", "/stop", "/answers", "/presence", "/bridge-status"):
            return _json(self, 404, {"ok": False, "error": "not found"})
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size > 8 * 1024 * 1024:
                return _json(self, 413, {"ok": False, "error": "payload too large"})
            data = json.loads(self.rfile.read(size) or b"{}")
            kind = {
                "/command": "command", "/stop": "stop", "/answers": "answers",
                "/presence": "presence", "/bridge-status": "bridge_status",
            }[self.path]
            _commands.put_nowait({"kind": kind, "data": data})
            _app.fireCustomEvent(EVENT_ID, "{}")
            return _json(self, 202, {"ok": True, "accepted": True})
        except queue.Full:
            return _json(self, 429, {"ok": False, "error": "command queue full"})
        except Exception as exc:
            return _json(self, 400, {"ok": False, "error": str(exc)})


def start(app):
    global _server, _thread, _app
    if _server:
        return
    _app = app
    _server = ThreadingHTTPServer((HOST, PORT), Handler)
    _thread = threading.Thread(target=_server.serve_forever, name="VolumindLoopback", daemon=True)
    _thread.start()


def stop():
    global _server, _thread, _app
    if _server:
        _server.shutdown()
        _server.server_close()
    _server = None
    _thread = None
    _app = None


def next_command():
    try:
        return _commands.get_nowait()
    except queue.Empty:
        return None


def publish(payload):
    try:
        _events.put_nowait(payload)
    except queue.Full:
        try:
            _events.get_nowait()
            _events.put_nowait(payload)
        except queue.Empty:
            pass


def pairing_code():
    return _pairing_code


def set_mobile_connected(connected):
    global _mobile_connected
    _mobile_connected = bool(connected)


def mobile_connected():
    return _mobile_connected


def set_bridge_status(registered, message=""):
    global _relay_registered, _relay_message
    _relay_registered = bool(registered)
    _relay_message = str(message or ("מחבר Windows מחובר לשרת" if registered else "מחבר Windows לא מחובר"))[:240]


def bridge_status():
    return {"registered": _relay_registered, "message": _relay_message}
