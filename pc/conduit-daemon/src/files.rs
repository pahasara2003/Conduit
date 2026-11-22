//! Hand files to the phone: find file paths in AI replies, serve single files or a zip.
use crate::sys::{home, run};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};

const MAX_FILES: usize = 20;

/// A path is served only if it is a regular file under $HOME with no hidden (dot) component,
/// which keeps ~/.ssh, ~/.config, ~/.gnupg etc. out of reach.
pub fn allowed(p: &Path) -> Option<PathBuf> {
    let c = std::fs::canonicalize(p).ok()?;
    let rel = c.strip_prefix(home()).ok()?;
    if rel.components().any(|x| x.as_os_str().to_string_lossy().starts_with('.')) { return None; }
    c.is_file().then_some(c)
}

pub fn mime(name: &str) -> &'static str {
    match name.rsplit('.').next().unwrap_or("").to_lowercase().as_str() {
        "png" => "image/png", "jpg" | "jpeg" => "image/jpeg", "gif" => "image/gif", "webp" => "image/webp",
        "svg" => "image/svg+xml", "pdf" => "application/pdf", "txt" | "md" | "log" => "text/plain",
        "csv" => "text/csv", "json" => "application/json", "html" => "text/html", "zip" => "application/zip",
        "docx" => "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" => "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" => "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "mp4" => "video/mp4", "mp3" => "audio/mpeg", "apk" => "application/vnd.android.package-archive",
        _ => "application/octet-stream",
    }
}

/// Existing, allowed files mentioned in `text` (absolute, ~/ or cwd-relative paths).
pub fn extract(text: &str, cwd: &str) -> Vec<Value> {
    let mut seen: Vec<PathBuf> = vec![];
    let sep = |c: char| c.is_whitespace() || "`\"'()<>[]{}|,;*".contains(c);
    for tok in text.split(sep) {
        let tok = tok.trim_end_matches(['.', ':', '!', '?']);
        if tok.len() < 3 || !tok.rsplit('/').next().unwrap_or("").contains('.') { continue; }
        let p = if let Some(r) = tok.strip_prefix("~/") { home().join(r) }
            else if tok.starts_with('/') { PathBuf::from(tok) }
            else { Path::new(cwd).join(tok) };
        if let Some(c) = allowed(&p) {
            if !seen.contains(&c) { seen.push(c); }
        }
        if seen.len() >= MAX_FILES { break; }
    }
    seen.iter().map(|p| {
        let name = p.file_name().unwrap_or_default().to_string_lossy().to_string();
        json!({"path": p.to_string_lossy(), "mime": mime(&name), "name": name,
               "size": std::fs::metadata(p).map(|m| m.len()).unwrap_or(0)})
    }).collect()
}

/// Session JSON with a `files` list injected into each assistant message.
pub fn session_json(s: &crate::sessions::Session) -> Value {
    let mut v = serde_json::to_value(s).unwrap_or(Value::Null);
    if let Some(msgs) = v["messages"].as_array_mut() {
        for m in msgs.iter_mut().filter(|m| m["role"] == "assistant") {
            let f = extract(m["text"].as_str().unwrap_or(""), &s.cwd);
            m["files"] = Value::Array(f);
        }
    }
    v
}

pub async fn zip(paths: &[PathBuf]) -> Option<Vec<u8>> {
    let args: Vec<&str> = ["-q", "-j", "-"].into_iter().chain(paths.iter().filter_map(|p| p.to_str())).collect();
    run("zip", &args, 60, None, None).await.filter(|o| o.code == 0).map(|o| o.stdout)
}
