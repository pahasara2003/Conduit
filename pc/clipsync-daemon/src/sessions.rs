//! Session store (sessions.json, same schema as the Python daemon) and the warm process pool.
//! Each open session keeps one long-lived `claude`/`agy` child speaking NDJSON over stdin/stdout,
//! so follow-up turns skip process start-up.
use crate::sys::{home, run};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::process::Stdio;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader, Lines};
use tokio::process::{Child, ChildStdin, ChildStdout, Command};
use tokio::sync::mpsc::Sender;
use tokio::time::timeout;

const DEFAULT_MODEL: &str = "gemini-3.8-flash-low";
const TURN_TIMEOUT: Duration = Duration::from_secs(300);
const IDLE_REAP: Duration = Duration::from_secs(900);

#[derive(Serialize, Deserialize, Clone, Default)]
pub struct Msg {
    pub role: String,
    pub text: String,
    #[serde(default)]
    pub timestamp: String,
}

#[derive(Serialize, Deserialize, Clone, Default)]
#[serde(default)]
pub struct Session {
    pub id: String,
    pub title: String,
    pub model: String,
    pub model_label: String,
    pub conversation_id: String,
    pub cwd: String,
    pub status: String,
    pub created_at: u64,
    pub updated_at: u64,
    pub messages: Vec<Msg>,
}

impl Session {
    pub fn summary(&self) -> Value {
        json!({"id": self.id, "title": self.title, "model": self.model, "model_label": self.model_label,
               "conversation_id": self.conversation_id, "cwd": self.cwd, "status": self.status,
               "created_at": self.created_at, "updated_at": self.updated_at, "message_count": self.messages.len()})
    }
}

fn now() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

fn uuid() -> String {
    std::fs::read_to_string("/proc/sys/kernel/random/uuid").unwrap_or_default().trim().to_string()
}

fn default_cwd() -> String {
    home().join("AndroidProjects/ClipSync").to_string_lossy().into()
}

// ---------- store ----------
static STORE: Mutex<()> = Mutex::new(());

fn file() -> std::path::PathBuf {
    home().join(".config/clipsync/sessions.json")
}

/// Load, mutate, save under one lock.
pub fn with_store<R>(f: impl FnOnce(&mut Vec<Session>) -> R) -> R {
    let _g = STORE.lock().unwrap_or_else(|e| e.into_inner());
    let mut v: Vec<Session> = std::fs::read_to_string(file()).ok().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
    let r = f(&mut v);
    let _ = std::fs::create_dir_all(file().parent().unwrap());
    let _ = std::fs::write(file(), serde_json::to_string_pretty(&v).unwrap_or_default());
    r
}

pub fn list() -> Vec<Value> {
    with_store(|v| {
        v.sort_by_key(|s| std::cmp::Reverse(s.updated_at));
        v.iter().map(|s| s.summary()).collect()
    })
}

pub fn get(id: &str) -> Option<Session> {
    with_store(|v| v.iter().find(|s| s.id == id).cloned())
}

pub fn set_status(id: &str, status: &str) -> Option<Session> {
    with_store(|v| {
        let s = v.iter_mut().find(|s| s.id == id)?;
        s.status = status.into();
        s.updated_at = now();
        Some(s.clone())
    })
}

pub async fn create(model: Option<&str>, label: Option<&str>, title: Option<&str>, cwd: Option<&str>) -> Session {
    let model = model.map(str::trim).filter(|m| !m.is_empty()).unwrap_or(DEFAULT_MODEL).to_string();
    let label = match label.map(str::trim).filter(|l| !l.is_empty()) {
        Some(l) => l.to_string(),
        None => label_for(&model).await,
    };
    let t = now();
    let s = Session {
        id: uuid().replace('-', "")[..12].to_string(),
        title: title.map(str::trim).filter(|x| !x.is_empty()).unwrap_or("New Session").into(),
        model, model_label: label,
        cwd: cwd.map(str::trim).filter(|x| !x.is_empty()).map(String::from).unwrap_or_else(default_cwd),
        status: "open".into(), created_at: t, updated_at: t, ..Default::default()
    };
    with_store(|v| v.insert(0, s.clone()));
    s
}

/// Remove from disk and stop its warm process.
pub async fn delete(id: &str) -> bool {
    close_warm(id).await;
    with_store(|v| { let n = v.len(); v.retain(|s| s.id != id); v.len() != n })
}

// ---------- models ----------
const FALLBACK: &[(&str, &str)] = &[
    ("gemini-3.8-flash-low", "Gemini 3.8 Flash (Low)"), ("gemini-3.8-flash-medium", "Gemini 3.8 Flash (Medium)"),
    ("gemini-3.8-flash-high", "Gemini 3.8 Flash (High)"), ("gemini-4-neon-low", "Gemini 4 Neon (Low)"),
    ("gemini-4-neon-medium", "Gemini 4 Neon (Medium)"), ("gemini-4-neon-high", "Gemini 4 Neon (High)"),
    ("gemini-4-argon-medium", "Gemini 4 Argon (Medium)"), ("claude-sonnet-5-5-medium", "Claude Sonnet 5.5 (Medium)"),
    ("claude-opus-5-5-medium", "Claude Opus 5.5 (Medium)"), ("gpt-oss-120b-medium", "GPT-OSS 120B (Medium)"),
];
static MODELS: Mutex<Option<(Instant, Vec<Value>)>> = Mutex::new(None);

pub async fn models() -> Vec<Value> {
    if let Some((t, m)) = MODELS.lock().unwrap().as_ref() {
        if t.elapsed() < Duration::from_secs(300) { return m.clone(); }
    }
    let mut out = vec![];
    if let Some(o) = run("agy", &["models"], 15, None, None).await.filter(|o| o.code == 0) {
        for line in String::from_utf8_lossy(&o.stdout).lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with("Fetching") { continue; }
            let parts = line.split_once('\t').or_else(|| line.split_once("  "));
            if let Some((id, label)) = parts {
                out.push(json!({"id": id.trim(), "label": label.trim()}));
            }
        }
    }
    if out.is_empty() {
        out = FALLBACK.iter().map(|(i, l)| json!({"id": i, "label": l})).collect();
    }
    *MODELS.lock().unwrap() = Some((Instant::now(), out.clone()));
    out
}

async fn label_for(model: &str) -> String {
    if model == "claude" { return "Claude".into(); }
    models().await.iter().find(|m| m["id"] == model).and_then(|m| m["label"].as_str()).unwrap_or(model).to_string()
}

// ---------- warm pool ----------
struct Warm {
    _child: Child, // kill_on_drop
    stdin: ChildStdin,
    lines: Lines<BufReader<ChildStdout>>,
    claude: bool,
    model: String,
    cwd: String,
    conv: String,
    last: Instant,
}

type Slot = Arc<tokio::sync::Mutex<Option<Warm>>>;
static POOL: OnceLock<Mutex<HashMap<String, Slot>>> = OnceLock::new();

fn pool() -> &'static Mutex<HashMap<String, Slot>> {
    POOL.get_or_init(Default::default)
}

fn slot(id: &str) -> Slot {
    pool().lock().unwrap().entry(id.into()).or_default().clone()
}

pub async fn close_warm(id: &str) {
    let s = pool().lock().unwrap().remove(id);
    if let Some(s) = s { s.lock().await.take(); }
}

/// Kill warm processes idle for too long (called periodically).
pub async fn reap_idle() {
    let slots: Vec<Slot> = pool().lock().unwrap().values().cloned().collect();
    for s in slots {
        if let Ok(mut g) = s.try_lock() {
            if g.as_ref().is_some_and(|w| w.last.elapsed() > IDLE_REAP) { g.take(); }
        }
    }
}

fn spawn(claude: bool, model: &str, cwd: &str, conv: &str) -> Result<Warm, String> {
    let mut c;
    let mut conv_out = conv.to_string();
    if claude {
        c = Command::new("claude");
        c.args(["-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                "--include-partial-messages", "--dangerously-skip-permissions"]);
        if conv.is_empty() {
            conv_out = uuid();
            c.args(["--session-id", &conv_out]);
        } else {
            c.args(["--resume", conv]);
        }
    } else {
        c = Command::new("agy");
        c.args(["--input-format", "stream-json", "--output-format", "stream-json", "--dangerously-skip-permissions", "--model", model]);
        if !conv.is_empty() { c.args(["--conversation", conv]); }
        c.arg("-p="); // print mode with the prompts arriving on stdin
    }
    let mut child = c.current_dir(cwd).kill_on_drop(true)
        .stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::null())
        .spawn().map_err(|e| format!("spawn failed: {e}"))?;
    let stdin = child.stdin.take().ok_or("no stdin")?;
    let lines = BufReader::new(child.stdout.take().ok_or("no stdout")?).lines();
    Ok(Warm { _child: child, stdin, lines, claude, model: model.into(), cwd: cwd.into(), conv: conv_out, last: Instant::now() })
}

/// One turn on a warm process. Returns (reply, conversation_id).
async fn turn(w: &mut Warm, prompt: &str, tx: &Option<Sender<Value>>) -> Result<(String, String), String> {
    let msg = if w.claude {
        json!({"type": "user", "message": {"role": "user", "content": prompt}})
    } else {
        json!({"event": "user", "message": {"content": prompt}})
    };
    w.stdin.write_all(format!("{msg}\n").as_bytes()).await.map_err(|e| format!("process gone: {e}"))?;
    w.stdin.flush().await.map_err(|e| format!("process gone: {e}"))?;
    let claude = w.claude;
    let lines = &mut w.lines;
    let read = async {
        loop {
            let line = lines.next_line().await.map_err(|e| e.to_string())?.ok_or("process exited")?;
            let Ok(v) = serde_json::from_str::<Value>(&line) else { continue };
            let (delta, done) = if claude {
                let d = (v["type"] == "stream_event" && v["event"]["delta"]["type"] == "text_delta")
                    .then(|| v["event"]["delta"]["text"].as_str().unwrap_or("").to_string());
                let done = (v["type"] == "result").then(|| {
                    if v["is_error"] == true { Err(v["result"].as_str().unwrap_or("claude error").to_string()) }
                    else { Ok((v["result"].as_str().unwrap_or("").to_string(), v["session_id"].as_str().unwrap_or("").to_string())) }
                });
                (d, done)
            } else {
                let su = &v["step_update"];
                let d = (v["event"] == "step_update" && su["step_type"] == "agent_response" && su["state"] == "ACTIVE")
                    .then(|| su["text_delta"].as_str().unwrap_or("").to_string());
                let done = (v["event"] == "result").then(|| {
                    let r = &v["result"];
                    if r["status"] == "SUCCESS" { Ok((r["response"].as_str().unwrap_or("").to_string(), r["conversation_id"].as_str().unwrap_or("").to_string())) }
                    else { Err(r["error"].as_str().unwrap_or("agy error").to_string()) }
                });
                (d, done)
            };
            if let (Some(d), Some(tx)) = (delta, tx) {
                if !d.is_empty() { let _ = tx.send(json!({"delta": d})).await; }
            }
            if let Some(r) = done { return r; }
        }
    };
    timeout(TURN_TIMEOUT, read).await.map_err(|_| "turn timed out".to_string())?
}

pub struct PromptReq {
    pub prompt: String,
    pub model: Option<String>,
    pub session_id: Option<String>,
    pub conversation_id: Option<String>,
    pub cwd: Option<String>,
}

pub async fn prompt(req: PromptReq, tx: Option<Sender<Value>>) -> Result<Value, String> {
    let existing = match &req.session_id { Some(id) => get(id), None => None };
    let session = match existing {
        Some(s) => s,
        None => create(req.model.as_deref(), None, None, req.cwd.as_deref()).await,
    };
    let mut model = req.model.clone().filter(|m| !m.trim().is_empty()).unwrap_or_else(|| if session.model.is_empty() { DEFAULT_MODEL.into() } else { session.model.clone() });
    let claude = model == "claude";
    if model == "gemini" { model = DEFAULT_MODEL.into(); }
    let conv = req.conversation_id.clone().filter(|c| !c.trim().is_empty()).unwrap_or_else(|| session.conversation_id.clone());
    let mut cwd = req.cwd.clone().filter(|c| !c.trim().is_empty()).unwrap_or_else(|| if session.cwd.is_empty() { default_cwd() } else { session.cwd.clone() });
    if !std::path::Path::new(&cwd).is_dir() { cwd = home().to_string_lossy().into(); }

    let slot = slot(&session.id);
    let mut guard = slot.lock().await; // one turn at a time per session
    let mut result = Err("unreachable".to_string());
    for attempt in 0..2 {
        // reuse the warm process unless model/cwd changed
        if guard.as_ref().is_some_and(|w| w.claude != claude || w.model != model || w.cwd != cwd) { guard.take(); }
        let reused = guard.is_some();
        if guard.is_none() {
            let resume = guard.as_ref().map(|w| w.conv.clone()).unwrap_or_else(|| conv.clone());
            *guard = Some(spawn(claude, &model, &cwd, &resume)?);
        }
        let w = guard.as_mut().unwrap();
        result = turn(w, &req.prompt, &tx).await;
        w.last = Instant::now();
        match &result {
            Ok((_, c)) if !c.is_empty() => w.conv = c.clone(),
            Err(_) => { guard.take(); } // a failed process is never reused
            _ => {}
        }
        // a stale warm process (died between turns) gets one fresh respawn
        if result.is_err() && reused && attempt == 0 { continue; }
        break;
    }
    let (reply, new_conv) = result?;
    let new_conv = if new_conv.is_empty() { conv } else { new_conv };
    let reply = reply.trim().to_string();
    let label = label_for(&if claude { "claude".to_string() } else { model.clone() }).await;
    let stamp = chrono::Local::now().format("%H:%M").to_string();
    let summary = with_store(|v| {
        if !v.iter().any(|s| s.id == session.id) { v.insert(0, session.clone()); }
        let t = v.iter_mut().find(|s| s.id == session.id).unwrap();
        t.model = if claude { "claude".into() } else { model.clone() };
        t.model_label = label;
        t.conversation_id = new_conv.clone();
        t.status = "open".into();
        t.updated_at = now();
        if (t.title.is_empty() || t.title == "New Session") && !req.prompt.trim().is_empty() {
            t.title = req.prompt.trim().lines().next().unwrap_or("").chars().take(44).collect();
        }
        t.messages.push(Msg { role: "user".into(), text: req.prompt.clone(), timestamp: stamp.clone() });
        t.messages.push(Msg { role: "assistant".into(), text: reply.clone(), timestamp: stamp });
        t.summary()
    });
    Ok(json!({"status": "ok", "reply": reply, "model": summary["model"], "model_label": summary["model_label"],
              "session_id": summary["id"], "conversation_id": new_conv, "session": summary}))
}
