"""Outbound-only PC connector for the Volumind Fusion add-in.

The bridge receives commands from the phone and forwards them to a loopback API
owned by the Fusion add-in. It never exposes Ollama or Fusion to the internet.
"""
import json, os, secrets, threading, time, urllib.request
from websocket import WebSocketApp

RELAY_URL = os.environ["VOLUMIND_RELAY_URL"]
FUSION_LOCAL_URL = os.environ.get("VOLUMIND_FUSION_URL", "http://127.0.0.1:8765")

def load_device_secret():
    configured = os.environ.get("VOLUMIND_DEVICE_SECRET")
    if configured: return configured
    root = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
    folder = os.path.join(root, "VolumindAI")
    path = os.path.join(folder, "device_secret.txt")
    try:
        with open(path, "r", encoding="utf-8") as source:
            saved = source.read().strip()
        if len(saved) >= 32: return saved
    except Exception: pass
    saved = secrets.token_urlsafe(32)
    os.makedirs(folder, exist_ok=True)
    with open(path, "w", encoding="utf-8") as destination: destination.write(saved)
    return saved

DEVICE_SECRET = load_device_secret()

def local_get(path):
    with urllib.request.urlopen(FUSION_LOCAL_URL + path, timeout=5) as response:
        return json.loads(response.read())

def local_post(path, body):
    data = json.dumps(body).encode()
    request = urllib.request.Request(FUSION_LOCAL_URL + path, data=data, headers={"Content-Type":"application/json"})
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read())

def report_bridge_status(registered, message):
    try: local_post("/bridge-status", {"registered":registered,"message":message})
    except Exception: pass

def on_open(ws):
    pairing_code = local_get("/pairing")["pairingCode"]
    ws.pairing_code = pairing_code
    ws.forwarder_started = False
    ws.send(json.dumps({"type":"authenticate","role":"desktop","pairingCode":pairing_code,"deviceSecret":DEVICE_SECRET}))
    report_bridge_status(False, "ממתין לאישור קוד הצימוד מהשרת")
    print("Registering Fusion pairing code with relay...", flush=True)

def forward_fusion_events(ws):
    """Long-poll newline-delimited events emitted by the local Fusion add-in."""
    while ws.sock and ws.sock.connected:
        try:
            with urllib.request.urlopen(FUSION_LOCAL_URL + "/events", timeout=35) as response:
                for line in response:
                    if line.strip():
                        event = json.loads(line)
                        if event.get("type") != "heartbeat": ws.send(json.dumps(event))
        except Exception:
            threading.Event().wait(2)

def on_message(ws, raw):
    message = json.loads(raw)
    if message.get("type") == "authenticated" and message.get("role") == "desktop":
        report_bridge_status(True, "מחבר Windows מחובר לשרת · הקוד פעיל")
        print(f"Pairing code ACTIVE: {ws.pairing_code}", flush=True)
        if not ws.forwarder_started:
            ws.forwarder_started = True
            threading.Thread(target=forward_fusion_events, args=(ws,), daemon=True).start()
    elif message.get("type") == "chat.command":
        threading.Thread(target=lambda: local_post("/command", message), daemon=True).start()
    elif message.get("type") == "build.stop":
        threading.Thread(target=lambda: local_post("/stop", message), daemon=True).start()
    elif message.get("type") == "questionnaire.answer":
        threading.Thread(target=lambda: local_post("/answers", message), daemon=True).start()
    elif message.get("type") == "presence":
        threading.Thread(target=lambda: local_post("/presence", message), daemon=True).start()
    elif message.get("type") == "error":
        reason = str(message.get("message", "Relay rejected the connection"))
        report_bridge_status(False, "השרת דחה את הקוד: " + reason)
        print(f"Relay rejected pairing: {reason}", flush=True)

def on_error(_ws, error):
    report_bridge_status(False, "שגיאת חיבור לשרת: " + str(error))
    print(f"Connection error: {error}", flush=True)

def on_close(_ws, code, reason):
    report_bridge_status(False, "מחבר Windows נותק מהשרת")
    print(f"Disconnected from relay ({code}): {reason}", flush=True)

def main():
    while True:
        print(f"Connecting to {RELAY_URL}...", flush=True)
        WebSocketApp(RELAY_URL, on_open=on_open, on_message=on_message, on_error=on_error, on_close=on_close).run_forever(ping_interval=20, ping_timeout=10)
        print("Retrying in 3 seconds...", flush=True)
        time.sleep(3)

if __name__ == "__main__": main()
