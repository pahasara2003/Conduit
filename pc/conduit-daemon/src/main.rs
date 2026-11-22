mod sessions;
mod sys;

use axum::extract::{Path, Request};
use axum::http::{header, StatusCode};
use axum::middleware::{self, Next};
use axum::response::sse::{Event, Sse};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::{json, Value};
use std::convert::Infallible;
use std::time::Duration;
use sessions::PromptReq;

const PC_PORT: u16 = 8765;
const PHONE_PORT: u16 = 8766;
const DISCOVERY_PORT: u16 = 8767;
const APK: &str = "/home/pahasara/AndroidProjects/Conduit/app/build/outputs/apk/debug/app-debug.apk";

fn err(code: StatusCode, msg: impl ToString) -> Response {
    (code, Json(json!({"status": "error", "error": msg.to_string()}))).into_response()
}

fn parse(body: &str) -> Value {
    serde_json::from_str(body).unwrap_or(Value::Null)
}

fn opt(v: &Value, k: &str) -> Option<String> {
    v[k].as_str().map(String::from)
}

// Same rule as the Python daemon: enforce X-Token only when CLIPSYNC_TOKEN is set.
async fn auth(req: Request, next: Next) -> Response {
    let token = std::env::var("CLIPSYNC_TOKEN").unwrap_or_default();
    if !token.is_empty() && req.headers().get("x-token").and_then(|v| v.to_str().ok()) != Some(token.as_str()) {
        return err(StatusCode::FORBIDDEN, "Invalid X-Token");
    }
    next.run(req).await
}

// ---------- sessions ----------
async fn sessions_list() -> Json<Value> {
    Json(json!({"sessions": sessions::list()}))
}

async fn session_new(body: String) -> Response {
    let r = parse(&body);
    let s = sessions::create(r["model"].as_str(), r["model_label"].as_str(), r["title"].as_str(), r["cwd"].as_str()).await;
    Json(json!({"status": "ok", "session": s})).into_response()
}

async fn session_get(Path(id): Path<String>) -> Response {
    match sessions::get(&id) {
        Some(s) => Json(json!({"status": "ok", "session": s})).into_response(),
        None => err(StatusCode::NOT_FOUND, "session not found"),
    }
}

async fn set_status(id: &str, status: &str) -> Response {
    if status == "closed" { sessions::close_warm(id).await; }
    match sessions::set_status(id, status) {
        None => err(StatusCode::NOT_FOUND, "session not found"),
        Some(s) if status == "closed" => Json(json!({"status": "ok", "session": s.summary()})).into_response(),
        Some(s) => Json(json!({"status": "ok", "session": s})).into_response(),
    }
}

async fn session_close(Path(id): Path<String>) -> Response { set_status(&id, "closed").await }
async fn session_resume(Path(id): Path<String>) -> Response { set_status(&id, "open").await }
async fn session_close_body(body: String) -> Response { set_status(parse(&body)["session_id"].as_str().unwrap_or(""), "closed").await }
async fn session_resume_body(body: String) -> Response { set_status(parse(&body)["session_id"].as_str().unwrap_or(""), "open").await }

async fn session_delete(Path(id): Path<String>) -> Response {
    if sessions::delete(&id).await { Json(json!({"status": "ok"})).into_response() } else { err(StatusCode::NOT_FOUND, "session not found") }
}

/// `"stream": true` in the body switches the reply to SSE: `{"delta":..}` events, then `{"done":true,..}`.
async fn session_prompt(body: String) -> Response {
    let r = parse(&body);
    let Some(prompt) = opt(&r, "prompt") else { return err(StatusCode::INTERNAL_SERVER_ERROR, "missing prompt") };
    let req = PromptReq { prompt, model: opt(&r, "model"), session_id: opt(&r, "session_id"), conversation_id: opt(&r, "conversation_id"), cwd: opt(&r, "cwd") };
    if r["stream"] == true {
        let (tx, rx) = tokio::sync::mpsc::channel::<Value>(64);
        tokio::spawn(async move {
            let last = match sessions::prompt(req, Some(tx.clone())).await {
                Ok(mut v) => { v["done"] = json!(true); v }
                Err(e) => json!({"done": true, "status": "error", "error": e}),
            };
            let _ = tx.send(last).await;
        });
        let stream = tokio_stream::wrappers::ReceiverStream::new(rx).map(|v| Ok::<_, Infallible>(Event::default().data(v.to_string())));
        return Sse::new(stream).into_response();
    }
    match sessions::prompt(req, None).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => err(StatusCode::INTERNAL_SERVER_ERROR, e),
    }
}
use tokio_stream::StreamExt;

// ---------- system ----------
async fn stats() -> Json<Value> { Json(sys::system_stats().await) }

async fn screenshot() -> Response {
    match sys::screenshot().await {
        Some(b) => ([(header::CONTENT_TYPE, "image/png")], b).into_response(),
        None => err(StatusCode::INTERNAL_SERVER_ERROR, "no grim/scrot/import available"),
    }
}

async fn terminal(body: String) -> Response {
    match sys::terminal(&parse(&body)).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => err(StatusCode::BAD_REQUEST, e),
    }
}

// ---------- clipboard / misc ----------
async fn clip_get() -> String { sys::get_clipboard().await }

async fn clip_post(body: String) -> &'static str {
    if !body.is_empty() {
        sys::set_clipboard(&body).await;
        sys::notify("Received from Phone".into(), body.replace('\n', " ").chars().take(60).collect(), None);
    }
    "OK"
}

fn state_path() -> std::path::PathBuf { sys::home().join(".config/conduit/state.json") }

fn save_state(patch: Value) {
    let mut cur = std::fs::read_to_string(state_path()).ok().and_then(|s| serde_json::from_str::<Value>(&s).ok()).unwrap_or(json!({}));
    if let (Some(c), Some(p)) = (cur.as_object_mut(), patch.as_object()) {
        for (k, v) in p { c.insert(k.clone(), v.clone()); }
    }
    let _ = std::fs::create_dir_all(state_path().parent().unwrap());
    let _ = std::fs::write(state_path(), serde_json::to_string_pretty(&cur).unwrap_or_default());
}

async fn register(body: String) -> &'static str {
    let r = parse(&body);
    if let Some(ip) = r["ip"].as_str().filter(|s| !s.is_empty()) {
        save_state(json!({"phone_ip": ip, "phone_port": r["port"].as_u64().unwrap_or(PHONE_PORT as u64)}));
    }
    "OK"
}

async fn notification(body: String) -> &'static str {
    let r = parse(&body);
    let (app, title, text) = (r["app_name"].as_str().unwrap_or(""), r["title"].as_str().unwrap_or(""), r["text"].as_str().unwrap_or(""));
    let nt = if !app.is_empty() && !title.is_empty() { format!("📱 {app}: {title}") }
        else { format!("📱 {}", [app, title].into_iter().find(|s| !s.is_empty()).unwrap_or("Phone Notification")) };
    // base64 decode without a crate dependency: shell out is overkill, so decode inline
    let img = r["image_base64"].as_str().and_then(b64_decode);
    sys::notify(nt, text.into(), img);
    "OK"
}

fn b64_decode(s: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(s.len() * 3 / 4);
    let (mut buf, mut bits) = (0u32, 0);
    for c in s.bytes().filter(|c| !c.is_ascii_whitespace() && *c != b'=') {
        let v = match c { b'A'..=b'Z' => c - b'A', b'a'..=b'z' => c - b'a' + 26, b'0'..=b'9' => c - b'0' + 52, b'+' | b'-' => 62, b'/' | b'_' => 63, _ => return None };
        buf = (buf << 6) | v as u32;
        bits += 6;
        if bits >= 8 { bits -= 8; out.push((buf >> bits) as u8); buf &= (1 << bits) - 1; }
    }
    Some(out)
}

async fn download_apk() -> Response {
    match std::fs::read(APK) {
        Ok(b) => ([(header::CONTENT_TYPE, "application/vnd.android.package-archive"),
                   (header::CONTENT_DISPOSITION, "attachment; filename=\"Conduit-AI.apk\"")], b).into_response(),
        Err(_) => (StatusCode::NOT_FOUND, "APK not found").into_response(),
    }
}

async fn ssh_info() -> Json<Value> {
    let user = std::env::var("USER").unwrap_or_default();
    Json(json!({"user": user, "port": 22, "ip": sys::lan_ip(), "active": sys::ssh_active().await}))
}

async fn ssh_authorize(body: String) -> Response {
    if sys::authorize_key(&body) { "OK".into_response() } else { (StatusCode::BAD_REQUEST, "Invalid public key").into_response() }
}

// ---------- background loops ----------
async fn discovery_loop() {
    let sock = match tokio::net::UdpSocket::bind(("0.0.0.0", DISCOVERY_PORT)).await {
        Ok(s) => s,
        Err(e) => { eprintln!("discovery bind failed: {e}"); return; }
    };
    let mut buf = [0u8; 512];
    loop {
        let Ok((n, addr)) = sock.recv_from(&mut buf).await else { tokio::time::sleep(Duration::from_secs(1)).await; continue };
        if String::from_utf8_lossy(&buf[..n]).trim() == "CLIPSYNC_DISCOVER" {
            let _ = sock.send_to(format!("CLIPSYNC_PC:{PC_PORT}").as_bytes(), addr).await;
            if !addr.ip().is_loopback() {
                save_state(json!({"phone_ip": addr.ip().to_string(), "phone_port": PHONE_PORT}));
            }
        }
    }
}

async fn adb_keeper_loop() {
    loop {
        if let Some(o) = sys::run("adb", &["get-state"], 2, None, None).await {
            if o.code == 0 && String::from_utf8_lossy(&o.stdout).trim() == "device" {
                let p = format!("tcp:{PC_PORT}");
                sys::run("adb", &["reverse", &p, &p], 2, None, None).await;
            }
        }
        tokio::time::sleep(Duration::from_secs(15)).await;
    }
}

#[tokio::main]
async fn main() {
    tokio::spawn(discovery_loop());
    tokio::spawn(adb_keeper_loop());
    tokio::spawn(async { loop { tokio::time::sleep(Duration::from_secs(60)).await; sessions::reap_idle().await; } });

    // Both the short /api/sessions/* routes and the /api/ai/* routes the Android app already uses.
    let mut api = Router::new()
        .route("/api/ai/models", get(|| async { Json(json!({"models": sessions::models().await})) }))
        .route("/api/ai/prompt", post(session_prompt))
        .route("/api/sessions/prompt", post(session_prompt))
        .route("/api/sessions/new", post(session_new))
        .route("/api/sessions/close", post(session_close_body))
        .route("/api/sessions/resume", post(session_resume_body))
        .route("/api/sessions/:id", get(session_get).delete(session_delete))
        .route("/api/ai/sessions/:id", get(session_get))
        .route("/api/ai/sessions/:id/close", post(session_close))
        .route("/api/ai/sessions/:id/resume", post(session_resume))
        .route("/api/ai/sessions/:id/delete", post(session_delete))
        .route("/api/system/stats", get(stats))
        .route("/api/system/screenshot", get(screenshot))
        .route("/api/terminal/run", post(terminal));
    api = api.route("/api/sessions", get(sessions_list)).route("/api/ai/sessions", get(sessions_list).post(session_new));

    let app = api.layer(middleware::from_fn(auth))
        .route("/ping", get(|| async { "conduit-pc" }))
        .route("/clip", get(clip_get).post(clip_post))
        .route("/register", post(register))
        .route("/notification", post(notification))
        .route("/download/apk", get(download_apk))
        .route("/ssh/info", get(ssh_info))
        .route("/ssh/authorize", post(ssh_authorize))
        .layer(axum::extract::DefaultBodyLimit::max(2 * 1024 * 1024));

    let port: u16 = std::env::var("CLIPSYNC_PORT").ok().and_then(|p| p.parse().ok()).unwrap_or(PC_PORT);
    let listener = tokio::net::TcpListener::bind(("0.0.0.0", port)).await.expect("bind :8765 (is the Python daemon still running?)");
    println!("conduit-daemon listening on 0.0.0.0:{port}");
    axum::serve(listener, app).await.unwrap();
}
