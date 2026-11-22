//! Small helpers: subprocess runner, system stats, screenshot, terminal, clipboard, ssh.
use serde_json::{json, Value};
use std::path::PathBuf;
use std::process::Stdio;
use std::time::Duration;
use tokio::io::AsyncWriteExt;
use tokio::process::Command;
use tokio::time::timeout;

pub fn home() -> PathBuf {
    PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| "/".into()))
}

pub struct Out {
    pub code: i32,
    pub stdout: Vec<u8>,
    pub stderr: Vec<u8>,
}

/// Run a command with a timeout; None if it can't spawn or times out.
pub async fn run(cmd: &str, args: &[&str], secs: u64, cwd: Option<&str>, stdin: Option<&[u8]>) -> Option<Out> {
    let mut c = Command::new(cmd);
    c.args(args).kill_on_drop(true).stdout(Stdio::piped()).stderr(Stdio::piped());
    c.stdin(if stdin.is_some() { Stdio::piped() } else { Stdio::null() });
    if let Some(d) = cwd {
        c.current_dir(d);
    }
    let mut child = c.spawn().ok()?;
    if let (Some(data), Some(mut si)) = (stdin, child.stdin.take()) {
        let _ = si.write_all(data).await;
    }
    let o = timeout(Duration::from_secs(secs), child.wait_with_output()).await.ok()?.ok()?;
    Some(Out { code: o.status.code().unwrap_or(-1), stdout: o.stdout, stderr: o.stderr })
}

async fn first_line(cmd: &str, args: &[&str]) -> String {
    match run(cmd, args, 5, None, None).await {
        Some(o) if o.code == 0 => String::from_utf8_lossy(&o.stdout).trim().to_string(),
        _ => String::new(),
    }
}

fn cpu_times() -> (u64, u64) {
    let s = std::fs::read_to_string("/proc/stat").unwrap_or_default();
    let v: Vec<u64> = s.lines().next().unwrap_or("").split_whitespace().skip(1).filter_map(|x| x.parse().ok()).collect();
    if v.len() < 5 { return (0, 1); }
    (v[3] + v[4], v.iter().sum())
}

pub async fn system_stats() -> Value {
    let (i1, t1) = cpu_times();
    tokio::time::sleep(Duration::from_millis(200)).await;
    let (i2, t2) = cpu_times();
    let cpu = 100.0 * (1.0 - (i2 - i1) as f64 / (t2 - t1).max(1) as f64);
    let mem = std::fs::read_to_string("/proc/meminfo").unwrap_or_default();
    let kb = |k: &str| -> u64 {
        mem.lines().find(|l| l.starts_with(k)).and_then(|l| l.split_whitespace().nth(1)).and_then(|x| x.parse().ok()).unwrap_or(0)
    };
    let battery = std::fs::read_dir("/sys/class/power_supply").ok().and_then(|d| {
        let mut names: Vec<_> = d.flatten().map(|e| e.path()).filter(|p| p.file_name().is_some_and(|n| n.to_string_lossy().starts_with("BAT"))).collect();
        names.sort();
        names.first().and_then(|p| std::fs::read_to_string(p.join("capacity")).ok()).and_then(|s| s.trim().parse::<i64>().ok())
    });
    let mut window = String::new();
    if let Some(o) = run("hyprctl", &["activewindow", "-j"], 3, None, None).await {
        if let Ok(v) = serde_json::from_slice::<Value>(&o.stdout) {
            window = v["title"].as_str().unwrap_or("").to_string();
        }
    }
    if window.is_empty() {
        window = first_line("xdotool", &["getactivewindow", "getwindowname"]).await;
    }
    json!({
        "cpu_percent": (cpu * 10.0).round() / 10.0,
        "ram_used_mb": (kb("MemTotal:") - kb("MemAvailable:")) / 1024,
        "ram_total_mb": kb("MemTotal:") / 1024,
        "window": window,
        "battery": battery,
        "tailscale_ip": first_line("tailscale", &["ip", "-4"]).await,
    })
}

pub async fn screenshot() -> Option<Vec<u8>> {
    let path = std::env::temp_dir().join(format!("conduit-shot-{}.png", std::process::id()));
    let p = path.to_string_lossy().to_string();
    let mut result = None;
    for (tool, args) in [("grim", vec![p.as_str()]), ("scrot", vec!["-o", p.as_str()]), ("import", vec!["-window", "root", p.as_str()])] {
        if let Some(o) = run(tool, &args, 15, None, None).await {
            if o.code == 0 {
                result = std::fs::read(&path).ok();
                break;
            }
        }
    }
    let _ = std::fs::remove_file(&path);
    result
}

pub async fn terminal(req: &Value) -> Result<Value, String> {
    let cmd = req["command"].as_str().ok_or("missing command")?;
    let secs = req["timeout"].as_u64().unwrap_or(30);
    let cwd = req["cwd"].as_str().filter(|s| !s.is_empty());
    Ok(match run("bash", &["-c", cmd], secs, cwd, None).await {
        Some(o) => json!({"exit_code": o.code, "stdout": String::from_utf8_lossy(&o.stdout), "stderr": String::from_utf8_lossy(&o.stderr)}),
        None => json!({"exit_code": -1, "stdout": "", "stderr": "timeout"}),
    })
}

pub async fn get_clipboard() -> String {
    for (c, a) in [("wl-paste", vec!["--no-newline"]), ("xclip", vec!["-o", "-selection", "clipboard"])] {
        if let Some(o) = run(c, &a, 2, None, None).await {
            if o.code == 0 {
                return String::from_utf8_lossy(&o.stdout).into_owned();
            }
        }
    }
    String::new()
}

pub async fn set_clipboard(text: &str) {
    if let Some(o) = run("wl-copy", &[], 2, None, Some(text.as_bytes())).await {
        if o.code == 0 { return; }
    }
    run("xclip", &["-i", "-selection", "clipboard"], 2, None, Some(text.as_bytes())).await;
}

pub fn notify(title: String, body: String, image: Option<Vec<u8>>) {
    tokio::spawn(async move {
        let mut args: Vec<String> = vec!["-a".into(), "Conduit".into()];
        if let Some(img) = image {
            let dir = home().join(".cache/conduit/avatars");
            let _ = std::fs::create_dir_all(&dir);
            use std::hash::{Hash, Hasher};
            let mut h = std::collections::hash_map::DefaultHasher::new();
            img.hash(&mut h);
            let f = dir.join(format!("{:016x}.png", h.finish()));
            if std::fs::write(&f, &img).is_ok() {
                args.push("-i".into());
                args.push(f.to_string_lossy().into());
            }
        }
        args.push(title);
        args.push(body.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;"));
        let a: Vec<&str> = args.iter().map(|s| s.as_str()).collect();
        run("notify-send", &a, 5, None, None).await;
    });
}

pub fn lan_ip() -> String {
    // connect() on UDP sends nothing; it just picks the outbound interface
    std::net::UdpSocket::bind("0.0.0.0:0")
        .and_then(|s| s.connect("10.255.255.255:1").map(|_| s))
        .and_then(|s| s.local_addr())
        .map(|a| a.ip().to_string())
        .unwrap_or_else(|_| "127.0.0.1".into())
}

pub async fn ssh_active() -> bool {
    timeout(Duration::from_millis(500), tokio::net::TcpStream::connect("127.0.0.1:22")).await.is_ok_and(|r| r.is_ok())
}

pub fn authorize_key(key: &str) -> bool {
    use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
    let key = key.trim();
    if key.contains('\n') || key.contains('\r') || !["ssh-", "ecdsa-", "sk-"].iter().any(|p| key.starts_with(p)) {
        return false;
    }
    let dir = home().join(".ssh");
    if std::fs::create_dir_all(&dir).is_err() { return false; }
    let _ = std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o700));
    let f = dir.join("authorized_keys");
    let existing = std::fs::read_to_string(&f).unwrap_or_default();
    if !existing.lines().any(|l| l == key) {
        use std::io::Write;
        let Ok(mut fh) = std::fs::OpenOptions::new().append(true).create(true).mode(0o600).open(&f) else { return false };
        let nl = if !existing.is_empty() && !existing.ends_with('\n') { "\n" } else { "" };
        if write!(fh, "{nl}{key}\n").is_err() { return false; }
    }
    let _ = std::fs::set_permissions(&f, std::fs::Permissions::from_mode(0o600));
    true
}
