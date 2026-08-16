"""Outbound-only PC connector for the Volumind Fusion add-in.

The bridge receives commands from the phone and forwards them to a loopback API
owned by the Fusion add-in. It never exposes Ollama or Fusion to the internet.
"""
import json, os, secrets, threading, urllib.request
from websocket import WebSocketApp

RELAY_URL = os.environ["VOLUMIND_RELAY_URL"]
DEVICE_SECRET = os.environ.get("VOLUMIND_DEVICE_SECRET") or secrets.token_urlsafe(32)
PAIRING_CODE = os.environ.get("VOLUMIND_PAIRING_CODE") or f"{secrets.randbelow(1_000_000):06d}"
FUSION_LOCAL_URL = os.environ.get("VOLUMIND_FUSION_URL", "http://127.0.0.1:8765")

def local_post(path, body):
    data = json.dumps(body).encode()
    request = urllib.request.Request(FUSION_LOCAL_URL + path, data=data, headers={"Content-Type":"application/json"})
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read())

def on_open(ws):
    ws.send(json.dumps({"type":"authenticate","role":"desktop","pairingCode":PAIRING_CODE,"deviceSecret":DEVICE_SECRET}))
    print(f"Pair Volumind Remote with code: {PAIRING_CODE}", flush=True)
    threading.Thread(target=forward_fusion_events, args=(ws,), daemon=True).start()

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
    if message.get("type") == "chat.command":
        threading.Thread(target=lambda: local_post("/command", message), daemon=True).start()
    elif message.get("type") == "build.stop":
        threading.Thread(target=lambda: local_post("/stop", message), daemon=True).start()
    elif message.get("type") == "questionnaire.answer":
        threading.Thread(target=lambda: local_post("/answers", message), daemon=True).start()

def main():
    WebSocketApp(RELAY_URL, on_open=on_open, on_message=on_message).run_forever(ping_interval=20, ping_timeout=10)

if __name__ == "__main__": main()
