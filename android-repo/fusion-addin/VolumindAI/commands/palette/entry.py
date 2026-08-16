"""Volumind AI: guided, staged Ollama modeling for Autodesk Fusion."""
import ast
import base64
import json
import math
import os
import re
import tempfile
import threading
import urllib.error
import urllib.request
import uuid

import adsk.core
import adsk.fusion

from ... import config
from ... import remote_api
from ...lib import fusionAddInUtils as futil

app = adsk.core.Application.get()
ui = app.userInterface

CMD_ID = "VolumindAI_Open"
CMD_NAME = "Volumind AI"
CMD_DESCRIPTION = "עוזר מקומי לבניית מודלים פרמטריים בשלבים"
PALETTE_ID = config.PALETTE_ID
CUSTOM_EVENT_ID = config.CUSTOM_EVENT_ID
HTML = os.path.join(os.path.dirname(__file__), "resources", "html", "index.html").replace("\\", "/")
ICONS = os.path.join(os.path.dirname(__file__), "resources", "")
WORKSPACE_ID = "FusionSolidEnvironment"
PANEL_ID = "SolidScriptsAddinsPanel"

handlers = []
_serial = 0
_build_env = None
_build_session = None
_remote_ui_queue = []

COMMON_RULES = """You are Volumind AI inside Autodesk Fusion.
The user wants a real editable parametric model, not code as the visible result.
The runtime already provides app, ui, design, rootComp, adsk and math.
Fusion internal length units are centimeters: 1 mm = 0.1 cm.
Never import modules, access files/network/OS, show dialogs, call adsk.doEvents,
use eval/exec/open/__import__, or delete/modify pre-existing user geometry.
Use named components, sketches and features. Capture every add() return value.
Prefer robust sketches, constraints, extrude, revolve, sweep, loft, hole, shell,
fillet and chamfer. Make missing engineering assumptions explicit and realistic.
The model is built one small step at a time and a real viewport screenshot is
captured after every successful step."""

ANALYZE_PROMPT = COMMON_RULES + """

Decide whether essential information is missing before modeling. Ask only questions
whose answers materially change geometry. Return strict JSON only:
{"needs_questions":true,"questions":[{"id":"short_id","text":"question in the user's language","options":["choice 1","choice 2","Other"]}]}
or {"needs_questions":false,"questions":[]}.
Default to no questions when the request already states a recognizable object.
Never ask a generic "circle, square, or triangle" / "עגול, מרובע או משולש"
question unless the user explicitly asks to choose a cross-section or profile shape.
Ask 1-3 questions maximum, each with 2-4 short mutually exclusive choices."""

PLAN_PROMPT = COMMON_RULES + """

Create a compact build plan. Return strict JSON only:
{"steps":[{"title":"human-readable Fusion operation","goal":"one precise geometric result"}]}
Use 2-8 steps. Each step must be independently executable and visible in the Fusion
Timeline. The first step creates one uniquely named top-level component. Later steps
add geometry below it. Do not output code."""

STEP_PROMPT = COMMON_RULES + """

Generate Python for ONLY the current build step. Return one Python fenced code block
and nothing else. Variables created by earlier steps remain available, so reuse them.
Do not repeat earlier geometry. Keep this step small and deterministic. Rename every
new sketch and feature immediately. Wrap the step in try/except and raise RuntimeError
with the step name if it fails.
Use the real Fusion Python API exactly: create extrudes through
component.features.extrudeFeatures.createInput(...) and
component.features.extrudeFeatures.add(input). There is NO `features.extrude` method.
Likewise use named feature collections such as `filletFeatures`, `holeFeatures` and
`chamferFeatures`; never invent convenience methods."""

INSPECT_PROMPT = """You are Volumind's visual CAD inspector. Inspect the actual Autodesk
Fusion viewport screenshot taken immediately after one build step. Decide whether visible
geometry plausibly matches the named step and whether there are obvious failures such as
missing geometry, empty viewport, extreme scale, disconnected parts or visibly malformed
features. Return strict JSON only:
{"ok":true,"summary":"short Hebrew summary","issues":[]}
or {"ok":false,"summary":"short Hebrew summary","issues":["specific issue"]}.
Do not claim dimensional accuracy from a screenshot."""


def _palette():
    return ui.palettes.itemById(PALETTE_ID)


def _send(action, payload):
    palette = _palette()
    if palette:
        palette.sendInfoToHTML(action, json.dumps(payload, ensure_ascii=False))
    _publish_remote(action, payload)


def _publish_remote(action, payload):
    if action == "aiResult" and not payload.get("ok"):
        remote_api.publish({"type": "error", "message": str(payload.get("error", "שגיאה"))})
    elif action == "aiResult" and payload.get("operation") == "analyze" and payload.get("questions"):
        remote_api.publish({"type": "questionnaire", "questions": payload["questions"]})
    elif action == "aiResult" and payload.get("operation") == "plan":
        remote_api.publish({"type": "build.plan", "steps": "|".join(x.get("title", "") for x in payload.get("steps", []))})
    elif action == "stepExecuted":
        remote_api.publish({"type": "build.step", "index": payload.get("step_index", 0), "status": "running", "title": payload.get("title", "")})
        remote_api.publish({"type": "fusion.screenshot", "url": payload.get("screenshot", ""), "caption": payload.get("title", "צילום מ־Fusion")})
    elif action == "stepResult":
        remote_api.publish({"type": "build.step", "index": payload.get("step_index", 0), "status": "done" if payload.get("ok") else "error", "title": payload.get("title", "")})
        remote_api.publish({"type": "fusion.screenshot", "url": payload.get("screenshot", ""), "caption": payload.get("summary", "צילום מאומת")})
        remote_api.publish({"type": "chat.message", "id": uuid.uuid4().hex, "text": payload.get("summary", "השלב הושלם")})


def start():
    old = ui.commandDefinitions.itemById(CMD_ID)
    if old:
        old.deleteMe()
    definition = ui.commandDefinitions.addButtonDefinition(CMD_ID, CMD_NAME, CMD_DESCRIPTION, ICONS)
    futil.add_handler(definition.commandCreated, command_created, local_handlers=handlers)
    workspace = ui.workspaces.itemById(WORKSPACE_ID)
    panel = workspace.toolbarPanels.itemById(PANEL_ID) if workspace else None
    if panel and not panel.controls.itemById(CMD_ID):
        control = panel.controls.addCommand(definition)
        control.isPromotedByDefault = True
        control.isPromoted = True
    try:
        app.unregisterCustomEvent(CUSTOM_EVENT_ID)
    except Exception:
        pass
    futil.add_handler(app.registerCustomEvent(CUSTOM_EVENT_ID), worker_result, local_handlers=handlers)
    try:
        app.unregisterCustomEvent(remote_api.EVENT_ID)
    except Exception:
        pass
    futil.add_handler(app.registerCustomEvent(remote_api.EVENT_ID), remote_command, local_handlers=handlers)
    remote_api.start(app)


def stop():
    global _serial, _build_env, _build_session
    _serial += 1
    _build_env = None
    _build_session = None
    palette = _palette()
    if palette:
        palette.deleteMe()
    workspace = ui.workspaces.itemById(WORKSPACE_ID)
    panel = workspace.toolbarPanels.itemById(PANEL_ID) if workspace else None
    control = panel.controls.itemById(CMD_ID) if panel else None
    if control:
        control.deleteMe()
    definition = ui.commandDefinitions.itemById(CMD_ID)
    if definition:
        definition.deleteMe()
    try:
        app.unregisterCustomEvent(CUSTOM_EVENT_ID)
    except Exception:
        pass
    try:
        app.unregisterCustomEvent(remote_api.EVENT_ID)
    except Exception:
        pass
    remote_api.stop()
    handlers.clear()


def command_created(args):
    futil.add_handler(args.command.execute, command_execute, local_handlers=handlers)


def command_execute(args):
    palette = _palette()
    if palette is None:
        palette = ui.palettes.add(PALETTE_ID, CMD_NAME, HTML, True, True, True, 460, 760, True)
        futil.add_handler(palette.incomingFromHTML, palette_incoming, local_handlers=handlers)
    palette.dockingState = adsk.core.PaletteDockingStates.PaletteDockStateRight
    palette.isVisible = True
    # A toolbar shortcut (recommended: Shift+Q) executes this command. Once the
    # palette is visible, move keyboard focus straight to the chat composer.
    _send("focusComposer", {"ok": True})


def _show_palette():
    palette = _palette()
    if palette is None:
        palette = ui.palettes.add(PALETTE_ID, CMD_NAME, HTML, True, True, True, 460, 760, True)
        futil.add_handler(palette.incomingFromHTML, palette_incoming, local_handlers=handlers)
    palette.dockingState = adsk.core.PaletteDockingStates.PaletteDockStateRight
    palette.isVisible = True
    return palette


def remote_command(_args):
    global _serial, _build_env, _build_session
    while True:
        item = remote_api.next_command()
        if not item:
            return
        kind = item.get("kind")
        data = item.get("data", {})
        if kind == "command":
            text = str(data.get("text", "")).strip()[:4000]
            if text:
                _show_palette()
                _remote_ui_queue.append({
                    "kind": "command",
                    "text": text,
                    "attachments": list(data.get("attachments", []))[:6],
                })
        elif kind == "answers":
            _remote_ui_queue.append({"kind": "answers", "answers": data.get("answers", {})})
        elif kind == "start":
            _remote_ui_queue.append({"kind": "start"})
        elif kind == "stop":
            _serial += 1
            _build_env = None
            _build_session = None
            _remote_ui_queue.append({"kind": "stop"})
            remote_api.publish({"type": "chat.message", "id": uuid.uuid4().hex, "text": "הבנייה נעצרה מהטלפון"})
        elif kind == "presence":
            remote_api.set_mobile_connected(data.get("mobileConnected", False))
            _send("remotePresence", {"connected": remote_api.mobile_connected()})
        elif kind == "bridge_status":
            remote_api.set_bridge_status(data.get("registered", False), data.get("message", ""))
            _send("remoteBridgeStatus", remote_api.bridge_status())
        if len(_remote_ui_queue) > 32:
            del _remote_ui_queue[:-32]


def _clean_code(text):
    match = re.search(r"```(?:python)?\s*(.*?)```", text, re.S | re.I)
    return (match.group(1) if match else text).strip()


def _extract_json(text):
    cleaned = re.sub(r"^```(?:json)?\s*|\s*```$", "", text.strip(), flags=re.I | re.S)
    try:
        return json.loads(cleaned)
    except Exception:
        start_at = cleaned.find("{")
        end_at = cleaned.rfind("}")
        if start_at >= 0 and end_at > start_at:
            return json.loads(cleaned[start_at:end_at + 1])
        raise ValueError("המודל לא החזיר JSON תקין")


def _is_generic_shape_question(question, request):
    combined = (str(question.get("text", "")) + " " + " ".join(map(str, question.get("options", [])))).lower()
    shape_words = ("circle", "square", "triangle", "round", "עגול", "מרובע", "משולש")
    asks_shape = any(word in combined for word in shape_words) and (
        "shape" in combined or "צורה" in combined or sum(word in combined for word in shape_words) >= 2
    )
    requested = str(request or "").lower()
    explicit = any(word in requested for word in ("shape", "profile", "cross-section", "צורה", "פרופיל", "חתך", "עגול", "מרובע", "משולש"))
    return asks_shape and not explicit


def _validate(code):
    if not code or len(code) > 70000:
        raise ValueError("הקוד ריק או ארוך מדי")
    tree = ast.parse(code, mode="exec")
    banned_names = {"eval", "exec", "compile", "open", "input", "exit", "quit", "__import__"}
    banned_roots = {"os", "sys", "subprocess", "socket", "requests", "urllib", "pathlib", "shutil"}
    banned_attrs = {"deleteMe", "save", "saveAs", "close", "terminate", "executeTextCommand"}
    for node in ast.walk(tree):
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            raise ValueError("ייבוא מודולים חסום")
        if isinstance(node, ast.While):
            raise ValueError("לולאת while חסומה")
        if isinstance(node, ast.Name) and node.id in banned_names:
            raise ValueError("פעולה מסוכנת חסומה: " + node.id)
        if isinstance(node, ast.Attribute):
            if node.attr.startswith("__") or node.attr in banned_attrs:
                raise ValueError("גישה מסוכנת חסומה: " + node.attr)
            root = node
            while isinstance(root, ast.Attribute):
                root = root.value
            if isinstance(root, ast.Name) and root.id in banned_roots:
                raise ValueError("גישה למערכת או לרשת חסומה")
    return tree


def _attachments_text(items):
    parts = []
    images = []
    total_text = 0
    for item in items or []:
        name = str(item.get("name", "attachment"))[:160]
        mime = str(item.get("type", ""))[:100]
        data = str(item.get("data", ""))
        if mime.startswith("image/") and data:
            images.append(data.split(",", 1)[-1])
            parts.append("[Image attachment: " + name + "]")
        else:
            content = str(item.get("text", ""))
            room = max(0, 120000 - total_text)
            content = content[:room]
            total_text += len(content)
            parts.append("[File: " + name + "]\n" + content)
    return "\n\n".join(parts), images[:4]


def _ollama(serial, operation, payload):
    try:
        model = str(payload.get("model", config.DEFAULT_MODEL)).strip() or config.DEFAULT_MODEL
        ctx = max(2048, min(int(payload.get("num_ctx", 4096)), 8192))
        attachment_text, images = _attachments_text(payload.get("attachments", []))
        request_text = str(payload.get("request", "")).strip()
        if images and operation in ("analyze", "plan"):
            model = str(payload.get("vision_model", config.DEFAULT_VISION_MODEL)).strip() or config.DEFAULT_VISION_MODEL
        if operation == "analyze":
            system = ANALYZE_PROMPT
            user = "USER REQUEST:\n" + request_text
            if attachment_text:
                user += "\n\nATTACHMENTS:\n" + attachment_text
            predict = 500
        elif operation == "plan":
            system = PLAN_PROMPT
            user = "USER REQUEST:\n" + request_text + "\n\nANSWERS:\n" + json.dumps(payload.get("answers", {}), ensure_ascii=False)
            if attachment_text:
                user += "\n\nATTACHMENTS:\n" + attachment_text
            if payload.get("plan_retry"):
                user += ("\n\nYOUR PREVIOUS RESPONSE WAS INVALID OR HAD FEWER THAN TWO COMPLETE STEPS:\n" +
                         str(payload.get("invalid_plan", ""))[:2000] +
                         "\nReturn ONLY the required JSON object. Include 3-6 steps, and every step MUST contain non-empty title and goal strings.")
            predict = 750
        elif operation == "step":
            system = STEP_PROMPT
            user = ("ORIGINAL REQUEST:\n" + request_text +
                    "\n\nANSWERS:\n" + json.dumps(payload.get("answers", {}), ensure_ascii=False) +
                    "\n\nFULL PLAN:\n" + json.dumps(payload.get("plan", []), ensure_ascii=False) +
                    "\n\nCOMPLETED STEPS:\n" + json.dumps(payload.get("completed", []), ensure_ascii=False) +
                    "\n\nCURRENT STEP:\n" + json.dumps(payload.get("step", {}), ensure_ascii=False))
            if payload.get("step_retry"):
                user += ("\n\nTHE PREVIOUS FUSION CODE FAILED WITH THIS ERROR:\n" +
                         str(payload.get("repair_error", ""))[:1200] +
                         "\nGenerate a corrected replacement for this step only. Use exact Fusion API collection names.")
            predict = 1600
            images = []
        elif operation == "inspect":
            system = INSPECT_PROMPT
            user = "STEP TO VERIFY:\n" + str(payload.get("title", "")) + "\nInspect the attached viewport screenshot."
            images = [str(payload.get("screenshot", "")).split(",", 1)[-1]]
            model = str(payload.get("vision_model", config.DEFAULT_VISION_MODEL)).strip() or config.DEFAULT_VISION_MODEL
            ctx = 2048
            predict = 280
        else:
            raise ValueError("פעולת AI לא מוכרת")
        user_message = {"role": "user", "content": user}
        if images:
            user_message["images"] = images
        body = json.dumps({
            "model": model,
            "stream": False,
            "keep_alive": "3m",
            "messages": [{"role": "system", "content": system}, user_message],
            "options": {"temperature": 0.1, "num_ctx": ctx, "num_predict": predict},
        }).encode("utf-8")
        request = urllib.request.Request(config.OLLAMA_URL, data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=240) as response:
            result = json.loads(response.read().decode("utf-8"))
        content = result.get("message", {}).get("content", "")
        if not content:
            raise RuntimeError("Ollama החזיר תשובה ריקה")
        output = {"serial": serial, "ok": True, "operation": operation, "content": content, "payload": payload}
    except urllib.error.URLError as exc:
        output = {"serial": serial, "ok": False, "operation": operation, "error": "לא ניתן להתחבר ל-Ollama: " + str(exc.reason)}
    except Exception as exc:
        output = {"serial": serial, "ok": False, "operation": operation, "error": str(exc)}
    app.fireCustomEvent(CUSTOM_EVENT_ID, json.dumps(output, ensure_ascii=False))


def _start_job(operation, payload):
    global _serial
    _serial += 1
    serial = _serial
    threading.Thread(target=_ollama, args=(serial, operation, payload), daemon=True).start()
    return serial


def _new_build_env():
    design = adsk.fusion.Design.cast(app.activeProduct)
    if not design:
        raise RuntimeError("פתח מסמך Design ב-Fusion לפני תחילת הבנייה")
    builtins = {
        "range": range, "len": len, "min": min, "max": max, "abs": abs, "round": round,
        "sum": sum, "enumerate": enumerate, "zip": zip, "int": int, "float": float,
        "str": str, "list": list, "tuple": tuple, "dict": dict, "set": set, "bool": bool,
        "sorted": sorted, "any": any, "all": all, "RuntimeError": RuntimeError, "Exception": Exception,
    }
    return {
        "__builtins__": builtins, "app": app, "ui": ui, "design": design,
        "rootComp": design.rootComponent, "adsk": __import__("adsk"), "math": math,
    }


def _capture(step_index):
    viewport = app.activeViewport
    if not viewport:
        raise RuntimeError("לא נמצא viewport פעיל לצילום")
    viewport.fit()
    viewport.refresh()
    path = os.path.join(tempfile.gettempdir(), "volumind_{}_{}.png".format(_build_session, step_index))
    if not viewport.saveAsImageFile(path, 720, 520):
        raise RuntimeError("Fusion לא הצליח לשמור צילום viewport")
    try:
        with open(path, "rb") as image_file:
            encoded = base64.b64encode(image_file.read()).decode("ascii")
        return "data:image/png;base64," + encoded
    finally:
        try:
            os.remove(path)
        except Exception:
            pass


def palette_incoming(args):
    global _serial, _build_env, _build_session
    try:
        data = json.loads(args.data or "{}")
        if args.action in ("analyze", "plan"):
            if len(str(data.get("request", "")).strip()) < 3:
                raise ValueError("יש להזין תיאור של המודל")
            serial = _start_job(args.action, data)
            args.returnData = json.dumps({"ok": True, "serial": serial})
            return
        if args.action == "beginBuild":
            _build_env = _new_build_env()
            _build_session = uuid.uuid4().hex[:10]
            args.returnData = json.dumps({"ok": True, "session": _build_session})
            return
        if args.action == "step":
            if not _build_env or not _build_session or data.get("session") != _build_session:
                raise RuntimeError("סשן הבנייה אינו פעיל")
            serial = _start_job("step", data)
            args.returnData = json.dumps({"ok": True, "serial": serial})
            return
        if args.action == "cancel":
            _serial += 1
            _build_env = None
            _build_session = None
            args.returnData = json.dumps({"ok": True})
            return
        if args.action == "exportPrompts":
            dialog = ui.createFileDialog()
            dialog.isMultiSelectEnabled = False
            dialog.title = "שמור מדריך ופרומפטים של Volumind AI"
            dialog.filter = "Text files (*.txt)"
            dialog.initialFilename = "Volumind_AI_Complete_Guide_and_Prompts.txt"
            if dialog.showSave() == adsk.core.DialogResults.DialogOK:
                source = os.path.join(os.path.dirname(__file__), "resources", "html", "Volumind_AI_Complete_Guide_and_Prompts.txt")
                with open(source, "r", encoding="utf-8") as source_file:
                    content = source_file.read()
                with open(dialog.filename, "w", encoding="utf-8-sig") as destination:
                    destination.write(content)
                args.returnData = json.dumps({"ok": True, "saved": True}, ensure_ascii=False)
            else:
                args.returnData = json.dumps({"ok": True, "saved": False}, ensure_ascii=False)
            return
        if args.action == "remoteStatus":
            bridge = remote_api.bridge_status()
            args.returnData = json.dumps({
                "ok": True,
                "pairing_code": remote_api.pairing_code(),
                "local_api": "127.0.0.1:8765",
                "mobile_connected": remote_api.mobile_connected(),
                "relay_registered": bridge["registered"],
                "relay_message": bridge["message"],
            }, ensure_ascii=False)
            return
        if args.action == "remotePoll":
            item = _remote_ui_queue.pop(0) if _remote_ui_queue else None
            args.returnData = json.dumps({"ok": True, "item": item}, ensure_ascii=False)
            return
        args.returnData = json.dumps({"ok": False, "error": "פעולה לא מוכרת"}, ensure_ascii=False)
    except Exception as exc:
        futil.log("Volumind error: " + str(exc), adsk.core.LogLevels.ErrorLogLevel)
        args.returnData = json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False)


def worker_result(args):
    try:
        result = json.loads(args.additionalInfo)
        if result.get("serial") != _serial:
            return
        if not result.get("ok"):
            _send("aiResult", result)
            return
        operation = result.get("operation")
        if operation == "analyze":
            parsed = _extract_json(result.get("content", ""))
            questions = parsed.get("questions", [])[:3] if parsed.get("needs_questions") else []
            normalized = []
            for index, question in enumerate(questions):
                if not isinstance(question, dict) or _is_generic_shape_question(question, result.get("payload", {}).get("request", "")):
                    continue
                options = [str(x)[:70] for x in question.get("options", [])[:4]]
                if len(options) >= 2:
                    normalized.append({
                        "id": str(question.get("id", "q{}".format(index + 1)))[:40],
                        "text": str(question.get("text", ""))[:240],
                        "options": options,
                    })
            _send("aiResult", {"ok": True, "operation": "analyze", "questions": normalized})
        elif operation == "plan":
            payload = dict(result.get("payload", {}))
            try:
                parsed = _extract_json(result.get("content", ""))
            except Exception:
                if int(payload.get("plan_retry", 0)) < 1:
                    payload["plan_retry"] = 1
                    payload["invalid_plan"] = str(result.get("content", ""))[:2000]
                    _start_job("plan", payload)
                    return
                parsed = {}
            steps = []
            raw_steps = parsed if isinstance(parsed, list) else (
                parsed.get("steps") or parsed.get("plan") or parsed.get("שלבים") or []
            )
            for step in raw_steps[:8]:
                if isinstance(step, str):
                    title = step.strip()[:120]
                    goal = title[:320]
                elif isinstance(step, dict):
                    title = str(step.get("title") or step.get("name") or step.get("step") or step.get("operation") or "").strip()[:120]
                    goal = str(step.get("goal") or step.get("description") or step.get("details") or title).strip()[:320]
                else:
                    continue
                if title and goal:
                    steps.append({"title": title, "goal": goal})
            if len(steps) < 2:
                if int(payload.get("plan_retry", 0)) < 1:
                    payload["plan_retry"] = 1
                    payload["invalid_plan"] = str(result.get("content", ""))[:2000]
                    _start_job("plan", payload)
                    return
                steps = [
                    {"title": "יצירת רכיב וסקיצת בסיס", "goal": "צור רכיב חדש וסקיצה פרמטרית שמגדירה את המידות והצורה הראשית."},
                    {"title": "בניית הגוף והמאפיינים העיקריים", "goal": "הפוך את הסקיצה לגוף תלת־ממדי והוסף את המאפיינים המרכזיים של הבקשה."},
                    {"title": "פרטים, גימור ובדיקה", "goal": "הוסף פתחים וגימורי קצוות נדרשים, התאם תצוגה ובדוק את המודל הסופי."},
                ]
            _send("aiResult", {"ok": True, "operation": "plan", "steps": steps})
        elif operation == "step":
            if not _build_env:
                raise RuntimeError("סשן הבנייה בוטל")
            code = _clean_code(result.get("content", ""))
            payload = dict(result.get("payload", {}))
            try:
                tree = _validate(code)
                exec(compile(tree, "<Volumind step>", "exec"), _build_env, _build_env)
            except Exception as exc:
                if int(payload.get("step_retry", 0)) < 1:
                    payload["step_retry"] = 1
                    payload["repair_error"] = str(exc)
                    _start_job("step", payload)
                    return
                raise RuntimeError("Fusion לא הצליח לבצע את השלב גם לאחר תיקון אוטומטי: " + str(exc))
            step_index = int(payload.get("step_index", 0))
            screenshot = _capture(step_index)
            inspect_payload = {
                "step_index": step_index,
                "title": str(payload.get("step", {}).get("title", "שלב הושלם")),
                "screenshot": screenshot,
                "code": code,
                "vision_model": payload.get("vision_model", config.DEFAULT_VISION_MODEL),
            }
            _send("stepExecuted", {"ok": True, "step_index": step_index, "title": inspect_payload["title"], "screenshot": screenshot})
            if payload.get("fast_mode", True):
                _send("stepResult", {
                    "ok": True,
                    "step_index": step_index,
                    "title": inspect_payload["title"],
                    "screenshot": screenshot,
                    "code": code,
                    "summary": "השלב נבנה וצולם במצב מהיר",
                    "issues": [],
                })
            else:
                _start_job("inspect", inspect_payload)
        elif operation == "inspect":
            parsed = _extract_json(result.get("content", ""))
            payload = result.get("payload", {})
            visual_ok = bool(parsed.get("ok"))
            _send("stepResult", {
                "ok": visual_ok,
                "step_index": int(payload.get("step_index", 0)),
                "title": str(payload.get("title", "שלב הושלם")),
                "screenshot": payload.get("screenshot", ""),
                "code": payload.get("code", ""),
                "summary": str(parsed.get("summary", ""))[:500],
                "issues": [str(x)[:300] for x in parsed.get("issues", [])[:5]],
            })
    except Exception as exc:
        futil.log("Volumind result error: " + str(exc), adsk.core.LogLevels.ErrorLogLevel)
        _send("aiResult", {"ok": False, "operation": "execution", "error": str(exc)})
