#!/usr/bin/env python3
import argparse
import base64
import hashlib
import html
import getpass
import json
import os
import re
import shutil
import tempfile
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PC_PORT = 8765
PHONE_PORT = 8766
DISCOVERY_PORT = 8767
STATE_DIR = Path.home() / ".config" / "conduit"
STATE_FILE = STATE_DIR / "state.json"
SESSIONS_FILE = STATE_DIR / "sessions.json"
AVATAR_DIR = Path.home() / ".cache" / "conduit" / "avatars"
DEFAULT_AI_MODEL = "gemini-3.8-flash-low"
DEFAULT_AI_CWD = str(Path.home() / "AndroidProjects" / "Conduit")
_SESSIONS_LOCK = threading.Lock()
_MODELS_CACHE = {"ts": 0.0, "models": []}


def load_state() -> dict:
    try:
        if STATE_FILE.exists():
            return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except Exception:
        pass
    return {}


def save_state(data: dict) -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    current = load_state()
    current.update(data)
    STATE_FILE.write_text(json.dumps(current, indent=2), encoding="utf-8")


def notify(title: str, body: str, image_data: bytes = None) -> None:
    cmd = ["notify-send", "-a", "Conduit"]
    if image_data:
        try:
            AVATAR_DIR.mkdir(parents=True, exist_ok=True)
            avatar = AVATAR_DIR / f"{hashlib.sha256(image_data).hexdigest()[:16]}.png"
            avatar.write_bytes(image_data)
            cmd += ["-i", str(avatar)]
        except Exception:
            pass
    try:
        subprocess.Popen(cmd + [title, html.escape(body)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        pass


def get_pc_clipboard() -> str:
    try:
        res = subprocess.run(
            ["wl-paste", "--no-newline"],
            capture_output=True,
            timeout=2,
        )
        if res.returncode == 0:
            return res.stdout.decode("utf-8", errors="replace")
    except FileNotFoundError:
        pass
    except Exception:
        pass

    try:
        res = subprocess.run(
            ["xclip", "-o", "-selection", "clipboard"],
            capture_output=True,
            timeout=2,
        )
        if res.returncode == 0:
            return res.stdout.decode("utf-8", errors="replace")
    except Exception:
        pass
    return ""


def set_pc_clipboard(text: str) -> None:
    try:
        subprocess.run(
            ["wl-copy"],
            input=text.encode("utf-8"),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=2,
            check=True,
        )
        return
    except FileNotFoundError:
        pass
    except Exception:
        pass

    try:
        subprocess.run(
            ["xclip", "-i", "-selection", "clipboard"],
            input=text.encode("utf-8"),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=2,
        )
    except Exception:
        pass


def lan_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))  # no packet sent; picks the outbound interface
            return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"


def ssh_active() -> bool:
    try:
        socket.create_connection(("127.0.0.1", 22), timeout=0.5).close()
        return True
    except OSError:
        return False


def authorize_key(key: str) -> bool:
    key = key.strip()
    if "\n" in key or "\r" in key or not key.startswith(("ssh-", "ecdsa-", "sk-")):
        return False
    d = Path.home() / ".ssh"
    d.mkdir(mode=0o700, exist_ok=True)
    f = d / "authorized_keys"
    existing = f.read_text().splitlines() if f.exists() else []
    if key not in existing:
        with open(f, "a") as fh:
            fh.write(("\n" if existing and not f.read_text().endswith("\n") else "") + key + "\n")
    os.chmod(f, 0o600)
    return True


def _run(cmd, timeout=5, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, **kw)


def _first_line(cmd) -> str:
    try:
        return _run(cmd).stdout.strip()
    except Exception:
        return ""


def system_stats() -> dict:
    def cpu_times():
        v = list(map(int, open("/proc/stat").readline().split()[1:]))
        return v[3] + v[4], sum(v)  # idle+iowait, total
    i1, t1 = cpu_times()
    time.sleep(0.2)
    i2, t2 = cpu_times()
    mem = {k: int(v.split()[0]) for k, v in (l.split(":") for l in open("/proc/meminfo"))}
    bats = sorted(Path("/sys/class/power_supply").glob("BAT*/capacity"))
    return {
        "cpu_percent": round(100 * (1 - (i2 - i1) / max(t2 - t1, 1)), 1),
        "ram_used_mb": (mem["MemTotal"] - mem["MemAvailable"]) // 1024,
        "ram_total_mb": mem["MemTotal"] // 1024,
        "window": (_first_line(["sh", "-c", "hyprctl activewindow -j 2>/dev/null | jq -r .title"])
                   or _first_line(["xdotool", "getactivewindow", "getwindowname"])),
        "battery": int(bats[0].read_text()) if bats else None,
        "tailscale_ip": _first_line(["tailscale", "ip", "-4"]),
    }


def take_screenshot():
    """Returns (bytes, mime) or None."""
    fd, path = tempfile.mkstemp(suffix=".png")
    os.close(fd)
    try:
        for tool, cmd in (("grim", ["grim", path]), ("scrot", ["scrot", "-o", path]),
                          ("import", ["import", "-window", "root", path])):
            if shutil.which(tool) and _run(cmd, timeout=15).returncode == 0:
                return Path(path).read_bytes(), "image/png"
        return None
    finally:
        os.unlink(path)


FALLBACK_AI_MODELS = [
    {"id": "gemini-3.8-flash-low", "label": "Gemini 3.8 Flash (Low)"},
    {"id": "gemini-3.8-flash-medium", "label": "Gemini 3.8 Flash (Medium)"},
    {"id": "gemini-3.8-flash-high", "label": "Gemini 3.8 Flash (High)"},
    {"id": "gemini-4-neon-low", "label": "Gemini 4 Neon (Low)"},
    {"id": "gemini-4-neon-medium", "label": "Gemini 4 Neon (Medium)"},
    {"id": "gemini-4-neon-high", "label": "Gemini 4 Neon (High)"},
    {"id": "gemini-4-argon-medium", "label": "Gemini 4 Argon (Medium)"},
    {"id": "claude-sonnet-5-5-medium", "label": "Claude Sonnet 5.5 (Medium)"},
    {"id": "claude-opus-5-5-medium", "label": "Claude Opus 5.5 (Medium)"},
    {"id": "gpt-oss-120b-medium", "label": "GPT-OSS 120B (Medium)"},
]


def _load_sessions_unlocked() -> list:
    try:
        if SESSIONS_FILE.exists():
            data = json.loads(SESSIONS_FILE.read_text(encoding="utf-8"))
            if isinstance(data, list):
                return data
    except Exception:
        pass
    return []


def _save_sessions_unlocked(sessions: list) -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    SESSIONS_FILE.write_text(json.dumps(sessions, indent=2), encoding="utf-8")


def _session_summary(s: dict) -> dict:
    return {
        "id": s["id"],
        "title": s.get("title", "New Session"),
        "model": s.get("model", DEFAULT_AI_MODEL),
        "model_label": s.get("model_label", s.get("model", DEFAULT_AI_MODEL)),
        "conversation_id": s.get("conversation_id", ""),
        "cwd": s.get("cwd", DEFAULT_AI_CWD),
        "status": s.get("status", "open"),
        "created_at": s.get("created_at", 0),
        "updated_at": s.get("updated_at", 0),
        "message_count": len(s.get("messages", [])),
    }


def list_ai_models(force_refresh: bool = False) -> list:
    now = time.time()
    if not force_refresh and _MODELS_CACHE["models"] and (now - _MODELS_CACHE["ts"] < 300):
        return _MODELS_CACHE["models"]
    models = []
    try:
        r = _run(["agy", "models"], timeout=15)
        if r.returncode == 0:
            for line in r.stdout.splitlines():
                line = line.strip()
                if not line or line.startswith("Fetching"):
                    continue
                if "\t" in line:
                    mid, label = line.split("\t", 1)
                    models.append({"id": mid.strip(), "label": label.strip()})
                elif "  " in line:
                    parts = re.split(r"\s{2,}", line, maxsplit=1)
                    if len(parts) == 2:
                        models.append({"id": parts[0].strip(), "label": parts[1].strip()})
    except Exception:
        pass
    if not models:
        models = list(FALLBACK_AI_MODELS)
    _MODELS_CACHE["ts"] = now
    _MODELS_CACHE["models"] = models
    return models


def _label_for_model(model_id: str) -> str:
    for m in list_ai_models():
        if m["id"] == model_id:
            return m["label"]
    return model_id


def list_ai_sessions() -> list:
    with _SESSIONS_LOCK:
        sessions = _load_sessions_unlocked()
        sessions.sort(key=lambda s: s.get("updated_at", 0), reverse=True)
        return [_session_summary(s) for s in sessions]


def get_ai_session(session_id: str):
    with _SESSIONS_LOCK:
        for s in _load_sessions_unlocked():
            if s.get("id") == session_id:
                return s
    return None


def create_ai_session(model: str = None, model_label: str = None, title: str = None, cwd: str = None) -> dict:
    model_id = (model or DEFAULT_AI_MODEL).strip() or DEFAULT_AI_MODEL
    label = (model_label or "").strip() or _label_for_model(model_id)
    now = int(time.time())
    session = {
        "id": uuid.uuid4().hex[:12],
        "title": (title or "New Session").strip() or "New Session",
        "model": model_id,
        "model_label": label,
        "conversation_id": "",
        "cwd": (cwd or DEFAULT_AI_CWD).strip() or DEFAULT_AI_CWD,
        "status": "open",
        "created_at": now,
        "updated_at": now,
        "messages": [],
    }
    with _SESSIONS_LOCK:
        sessions = _load_sessions_unlocked()
        sessions.insert(0, session)
        _save_sessions_unlocked(sessions)
    return session


def set_ai_session_status(session_id: str, status: str):
    with _SESSIONS_LOCK:
        sessions = _load_sessions_unlocked()
        for s in sessions:
            if s.get("id") == session_id:
                s["status"] = status
                s["updated_at"] = int(time.time())
                _save_sessions_unlocked(sessions)
                return s
    return None


def delete_ai_session(session_id: str) -> bool:
    with _SESSIONS_LOCK:
        sessions = _load_sessions_unlocked()
        new_sessions = [s for s in sessions if s.get("id") != session_id]
        if len(new_sessions) == len(sessions):
            return False
        _save_sessions_unlocked(new_sessions)
        return True


def ai_prompt(prompt: str, model: str = None, session_id: str = None, conversation_id: str = None, cwd: str = None) -> dict:
    """Run agy directly in non-interactive print mode (no herdr) with persistent session resume."""
    session = None
    if session_id:
        session = get_ai_session(session_id)
    if session is None:
        session = create_ai_session(model=model, cwd=cwd)

    active_model = (model or session.get("model") or DEFAULT_AI_MODEL).strip()
    active_conv = (conversation_id or session.get("conversation_id") or "").strip()
    active_cwd = (cwd or session.get("cwd") or DEFAULT_AI_CWD).strip()
    if not os.path.isdir(active_cwd):
        active_cwd = str(Path.home())

    use_claude = active_model == "claude"
    if active_model == "gemini":
        active_model = DEFAULT_AI_MODEL
    if use_claude:
        # claude needs a client-chosen UUID on turn 1, then --resume on later turns
        cmd = ["claude", "-p", prompt, "--output-format", "json", "--dangerously-skip-permissions"]
        cmd += ["--resume", active_conv] if active_conv else ["--session-id", str(uuid.uuid4())]
    else:
        cmd = ["agy", "--print", prompt, "--model", active_model,
               "--output-format", "json", "--dangerously-skip-permissions"]
        if active_conv:
            cmd.extend(["--conversation", active_conv])

    r = _run(cmd, timeout=180, cwd=active_cwd)
    raw_out = (r.stdout or "").strip()
    raw_err = (r.stderr or "").strip()
    if r.returncode != 0 and not raw_out:
        raise RuntimeError(raw_err or f"agy exited with code {r.returncode}")

    reply_text = raw_out
    new_conv_id = active_conv
    try:
        # Handle any leading non-JSON log lines before the final JSON object
        json_line = raw_out
        if not json_line.startswith("{"):
            idx = raw_out.rfind("{")
            if idx != -1:
                json_line = raw_out[idx:]
        parsed = json.loads(json_line)
        reply_text = str(parsed.get("result" if use_claude else "response", "")).strip()
        new_id = parsed.get("session_id" if use_claude else "conversation_id")
        if new_id:
            new_conv_id = str(new_id)
    except Exception:
        pass

    ts_label = datetime.now().strftime("%H:%M")
    with _SESSIONS_LOCK:
        sessions = _load_sessions_unlocked()
        target = None
        for s in sessions:
            if s.get("id") == session["id"]:
                target = s
                break
        if target is None:
            target = session
            sessions.insert(0, target)
        target["model"] = "claude" if use_claude else active_model
        target["model_label"] = "Claude" if use_claude else _label_for_model(active_model)
        target["conversation_id"] = new_conv_id
        target["status"] = "open"
        target["updated_at"] = int(time.time())
        if target.get("title") in ("", "New Session") and prompt.strip():
            clean_title = prompt.strip().splitlines()[0][:44]
            target["title"] = clean_title
        msgs = target.setdefault("messages", [])
        msgs.append({"role": "user", "text": prompt, "timestamp": ts_label})
        msgs.append({"role": "assistant", "text": reply_text, "timestamp": ts_label})
        _save_sessions_unlocked(sessions)
        updated_summary = _session_summary(target)

    return {
        "status": "ok",
        "reply": reply_text,
        "model": active_model,
        "model_label": updated_summary["model_label"],
        "session_id": updated_summary["id"],
        "conversation_id": new_conv_id,
        "session": updated_summary,
    }


API_TOKEN = os.environ.get("CLIPSYNC_TOKEN", "")


class ConduitHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def _send_text(self, code: int, text: str) -> None:
        data = text.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _send_json(self, code: int, obj) -> None:
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _api_ok(self) -> bool:
        # If CLIPSYNC_TOKEN is set, enforce authentication; otherwise allow on private network
        if not API_TOKEN:
            return True
        import hmac
        if hmac.compare_digest(self.headers.get("X-Token", ""), API_TOKEN):
            return True
        self._send_json(403, {"status": "error", "error": "Invalid X-Token"})
        return False

    def _alias(self, body: str = "") -> None:
        """Map the short /api/sessions/* routes onto the /api/ai/* ones."""
        p = self.path
        if p == "/api/sessions":
            self.path = "/api/ai/sessions"
        elif p == "/api/sessions/new":
            self.path = "/api/ai/sessions"
        elif p == "/api/sessions/prompt":
            self.path = "/api/ai/prompt"
        elif p in ("/api/sessions/close", "/api/sessions/resume"):
            try:
                sid = json.loads(body)["session_id"]
            except Exception:
                sid = ""
            self.path = f"/api/ai/sessions/{sid}/{p.rsplit('/', 1)[1]}"
        elif p.startswith("/api/sessions/"):
            self.path = "/api/ai/sessions/" + p.removeprefix("/api/sessions/")

    def do_DELETE(self):
        if not self.path.startswith("/api/sessions/"):
            self._send_text(404, "Not Found")
            return
        if not self._api_ok():
            return
        sid = self.path.removeprefix("/api/sessions/").strip("/")
        ok = delete_ai_session(sid)
        self._send_json(200 if ok else 404, {"status": "ok"} if ok else {"status": "error", "error": "session not found"})

    def do_GET(self):
        self._alias()
        if self.path.startswith("/api/") and not self._api_ok():
            return
        if self.path == "/api/ai/models":
            self._send_json(200, {"models": list_ai_models()})
            return
        if self.path == "/api/ai/sessions":
            self._send_json(200, {"sessions": list_ai_sessions()})
            return
        if self.path.startswith("/api/ai/sessions/"):
            sid = self.path.removeprefix("/api/ai/sessions/").strip("/")
            sess = get_ai_session(sid)
            if sess is None:
                self._send_json(404, {"status": "error", "error": "session not found"})
            else:
                self._send_json(200, {"status": "ok", "session": sess})
            return
        if self.path == "/api/system/stats":
            self._send_json(200, system_stats())
            return
        if self.path == "/api/system/screenshot":
            shot = take_screenshot()
            if not shot:
                self._send_json(500, {"status": "error", "error": "no grim/scrot/import available"})
                return
            self.send_response(200)
            self.send_header("Content-Type", shot[1])
            self.send_header("Content-Length", str(len(shot[0])))
            self.end_headers()
            self.wfile.write(shot[0])
            return

        if self.path == "/download/apk":
            apk_path = Path("/home/pahasara/AndroidProjects/Conduit/app/build/outputs/apk/debug/app-debug.apk")
            if not apk_path.exists():
                self._send_text(404, "APK not found")
                return
            data = apk_path.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", 'attachment; filename="Conduit-AI.apk"')
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return

        if self.path == "/ping":
            self._send_text(200, "conduit-pc")
            return
        if self.path == "/clip":
            clip = get_pc_clipboard()
            self._send_text(200, clip)
            return
        if self.path == "/ssh/info":
            data = json.dumps({"user": getpass.getuser(), "port": 22, "ip": lan_ip(), "active": ssh_active()}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        self._send_text(404, "Not Found")

    def do_POST(self):
        if self.path.startswith("/api/") and not self._api_ok():
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(min(length, 2 * 1024 * 1024)).decode("utf-8", errors="replace")
        self._alias(body)

        if self.path == "/clip":
            if body:
                set_pc_clipboard(body)
                preview = body.replace("\n", " ")[:60]
                notify("Received from Phone", preview)
            self._send_text(200, "OK")
            return

        if self.path == "/api/ai/sessions":
            try:
                req = json.loads(body) if body.strip() else {}
                sess = create_ai_session(
                    model=req.get("model"),
                    model_label=req.get("model_label"),
                    title=req.get("title"),
                    cwd=req.get("cwd"),
                )
                self._send_json(200, {"status": "ok", "session": sess})
            except Exception as e:
                self._send_json(400, {"status": "error", "error": str(e)})
            return

        if self.path.startswith("/api/ai/sessions/") and self.path.endswith("/close"):
            sid = self.path.removeprefix("/api/ai/sessions/").removesuffix("/close").strip("/")
            sess = set_ai_session_status(sid, "closed")
            if sess is None:
                self._send_json(404, {"status": "error", "error": "session not found"})
            else:
                self._send_json(200, {"status": "ok", "session": _session_summary(sess)})
            return

        if self.path.startswith("/api/ai/sessions/") and self.path.endswith("/resume"):
            sid = self.path.removeprefix("/api/ai/sessions/").removesuffix("/resume").strip("/")
            sess = set_ai_session_status(sid, "open")
            if sess is None:
                self._send_json(404, {"status": "error", "error": "session not found"})
            else:
                self._send_json(200, {"status": "ok", "session": sess})
            return

        if self.path.startswith("/api/ai/sessions/") and self.path.endswith("/delete"):
            sid = self.path.removeprefix("/api/ai/sessions/").removesuffix("/delete").strip("/")
            if delete_ai_session(sid):
                self._send_json(200, {"status": "ok"})
            else:
                self._send_json(404, {"status": "error", "error": "session not found"})
            return

        if self.path == "/api/ai/prompt":
            try:
                req = json.loads(body)
                res = ai_prompt(
                    prompt=str(req["prompt"]),
                    model=req.get("model"),
                    session_id=req.get("session_id"),
                    conversation_id=req.get("conversation_id"),
                    cwd=req.get("cwd"),
                )
                self._send_json(200, res)
            except Exception as e:
                self._send_json(500, {"status": "error", "error": str(e)})
            return

        if self.path == "/api/terminal/run":
            try:
                req = json.loads(body)
                r = _run(["bash", "-c", req["command"]], timeout=int(req.get("timeout", 30)),
                         cwd=req.get("cwd") or None)
                self._send_json(200, {"exit_code": r.returncode, "stdout": r.stdout, "stderr": r.stderr})
            except subprocess.TimeoutExpired:
                self._send_json(200, {"exit_code": -1, "stdout": "", "stderr": "timeout"})
            except Exception as e:
                self._send_json(400, {"status": "error", "error": str(e)})
            return

        if self.path == "/ssh/authorize":
            ok = authorize_key(body)
            self._send_text(200 if ok else 400, "OK" if ok else "Invalid public key")
            return

        if self.path == "/register":
            try:
                info = json.loads(body)
                ip = info.get("ip", "")
                port = int(info.get("port", PHONE_PORT))
                if ip:
                    save_state({"phone_ip": ip, "phone_port": port})
            except Exception:
                pass
            self._send_text(200, "OK")
            return

        if self.path == "/notification":
            try:
                info = json.loads(body)
                app_name = info.get("app_name", "")
                title = info.get("title", "")
                text = info.get("text", "")
                notify_title = (
                    f"📱 {app_name}: {title}"
                    if app_name and title
                    else f"📱 {app_name or title or 'Phone Notification'}"
                )
                image_data = None
                if info.get("image_base64"):
                    try:
                        image_data = base64.b64decode(info["image_base64"])
                    except Exception:
                        pass
                notify(notify_title, text, image_data)
                print(f"[Notification] {app_name} | {title}: {text}")
            except Exception:
                pass
            self._send_text(200, "OK")
            return

        self._send_text(404, "Not Found")


def discovery_loop():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", DISCOVERY_PORT))
    while True:
        try:
            data, addr = sock.recvfrom(512)
            msg = data.decode("utf-8", errors="ignore").strip()
            if msg == "CLIPSYNC_DISCOVER":
                sock.sendto(f"CLIPSYNC_PC:{PC_PORT}".encode("utf-8"), addr)
                if addr[0] and addr[0] != "127.0.0.1":
                    save_state({"phone_ip": addr[0], "phone_port": PHONE_PORT})
        except Exception:
            time.sleep(1)


def adb_keeper_loop():
    while True:
        try:
            state = subprocess.run(
                ["adb", "get-state"],
                capture_output=True,
                text=True,
                timeout=2,
            )
            if state.returncode == 0 and state.stdout.strip() == "device":
                subprocess.run(
                    ["adb", "reverse", f"tcp:{PC_PORT}", f"tcp:{PC_PORT}"],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    timeout=2,
                )
        except Exception:
            pass
        time.sleep(15)


def phone_token() -> str:
    """Shared secret the phone's listener requires (env, else ~/.config/conduit/token.env)."""
    tok = os.environ.get("CLIPSYNC_TOKEN", "")
    if not tok:
        try:
            for line in (Path.home() / ".config/conduit/token.env").read_text().splitlines():
                if line.startswith("CLIPSYNC_TOKEN="):
                    tok = line.split("=", 1)[1].strip()
        except OSError:
            pass
    return tok


def send_clipboard_to_phone() -> int:
    text = get_pc_clipboard()
    if not text:
        notify("Conduit", "PC clipboard is empty")
        print("PC clipboard is empty")
        return 1

    state = load_state()
    targets = []
    phone_ip = state.get("phone_ip")
    phone_port = int(state.get("phone_port", PHONE_PORT))
    if phone_ip:
        targets.append((phone_ip, phone_port, "Wi-Fi"))

    def post_to(host: str, port: int) -> bool:
        req = urllib.request.Request(
            f"http://{host}:{port}/clip",
            data=text.encode("utf-8"),
            headers={"Content-Type": "text/plain; charset=utf-8", "X-Token": phone_token()},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=1.3) as resp:
            return 200 <= resp.status < 300

    for host, port, label in targets:
        try:
            if post_to(host, port):
                preview = text.replace("\n", " ")[:60]
                notify(f"Sent to Phone ({label}) ✓", preview)
                print(f"Sent via {label}")
                return 0
        except Exception:
            pass

    # Fallback: USB / ADB forward
    try:
        subprocess.run(
            ["adb", "forward", f"tcp:{PHONE_PORT}", f"tcp:{PHONE_PORT}"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=2,
        )
        if post_to("127.0.0.1", PHONE_PORT):
            preview = text.replace("\n", " ")[:60]
            notify("Sent to Phone (USB/ADB) ✓", preview)
            print("Sent via USB/ADB")
            return 0
    except Exception as e:
        notify("Conduit Error", f"Phone unreachable: {e}")
        print(f"Failed: {e}")
        return 1

    notify("Conduit Error", "Phone unreachable (check Wi-Fi or USB)")
    return 1


def run_server():
    threading.Thread(target=discovery_loop, daemon=True).start()
    threading.Thread(target=adb_keeper_loop, daemon=True).start()
    server = ThreadingHTTPServer(("0.0.0.0", PC_PORT), ConduitHandler)
    print(f"Conduit PC server listening on 0.0.0.0:{PC_PORT}")
    server.serve_forever()


def main():
    parser = argparse.ArgumentParser(description="Conduit PC Companion")
    parser.add_argument("--send", action="store_true", help="Send PC clipboard to phone")
    args = parser.parse_args()

    if args.send:
        raise SystemExit(send_clipboard_to_phone())
    run_server()


if __name__ == "__main__":
    main()
