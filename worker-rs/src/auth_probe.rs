//! claude CLI login probe (Java `ClaudeAuthProbe`). Never calls a model; the first
//! source that answers wins:
//!
//! 1. env `CLAUDE_CODE_OAUTH_TOKEN` (setup-token) -> `ENV_TOKEN`, expiry unknown
//! 2. `{CLAUDE_CONFIG_DIR or <home>/.claude}/.credentials.json` `claudeAiOauth` -> `CREDENTIALS`
//! 3. `claude auth status --json` `loggedIn` -> `CLI`
//!
//! Token strings are only checked for being blank; they never reach a log or a result.

use crate::claude::child_env;
use crate::log;
use crate::proc;
use serde_json::Value;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const CLI_TIMEOUT: Duration = Duration::from_secs(30);
const ENV_TOKEN: &str = "CLAUDE_CODE_OAUTH_TOKEN";

/// All `None` = could not tell. `expires_at` is ISO-8601 UTC (`2026-12-31T00:00:00Z`).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct AuthState {
    pub ok: Option<bool>,
    pub method: Option<String>,
    pub expires_at: Option<String>,
}

fn state(ok: bool, method: &str, expires_at: Option<String>) -> AuthState {
    AuthState {
        ok: Some(ok),
        method: Some(method.to_string()),
        expires_at,
    }
}

/// Env lookup that ignores the case of the name (Windows env is case-insensitive).
fn env_value<'a>(env: &'a HashMap<String, String>, name: &str) -> Option<&'a String> {
    env.get(name).or_else(|| {
        env.iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v)
    })
}

fn non_blank(v: Option<&Value>) -> bool {
    matches!(v, Some(Value::String(s)) if !s.trim().is_empty())
}

/// Jackson `isNumber() && asLong() > 0`, as epoch milliseconds.
fn epoch_millis(v: Option<&Value>) -> Option<i64> {
    let n = match v {
        Some(Value::Number(n)) => n,
        _ => return None,
    };
    let ms = n.as_i64().or_else(|| n.as_f64().map(|f| f as i64))?;
    (ms > 0).then_some(ms)
}

fn iso_utc(ms: i64) -> Option<String> {
    let t = time::OffsetDateTime::from_unix_timestamp_nanos(i128::from(ms) * 1_000_000).ok()?;
    t.format(&time::format_description::well_known::Rfc3339)
        .ok()
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn claude_config_dir(env: &HashMap<String, String>, home: &Path) -> PathBuf {
    match env_value(env, "CLAUDE_CONFIG_DIR") {
        Some(dir) if !dir.trim().is_empty() => PathBuf::from(dir),
        _ => home.join(".claude"),
    }
}

/// `None` when the file cannot decide (absent, unreadable, broken, no `claudeAiOauth`
/// object) - the CLI is asked then.
fn from_credentials(file: &Path, now: i64) -> Option<AuthState> {
    if !file.is_file() {
        return None;
    }
    let text = std::fs::read_to_string(file).ok()?;
    let root = serde_json::Deserializer::from_str(&text)
        .into_iter::<Value>()
        .next()?
        .ok()?;
    let oauth = root.get("claudeAiOauth").filter(|v| v.is_object())?;
    let has_refresh = non_blank(oauth.get("refreshToken"));
    let has_access = non_blank(oauth.get("accessToken"));
    // no refresh token but an access token: the access token's own expiry is the end
    let key = if !has_refresh && has_access {
        "expiresAt"
    } else {
        "refreshTokenExpiresAt"
    };
    let expires = epoch_millis(oauth.get(key));
    let expires_at = expires.and_then(iso_utc);
    if !has_refresh && !has_access {
        return Some(state(false, "CREDENTIALS", expires_at));
    }
    let ok = expires.is_none_or(|e| e > now);
    Some(state(ok, "CREDENTIALS", expires_at))
}

/// First `{` to last `}` read as JSON, so stderr noise around it is tolerated.
fn parse_logged_in(output: &str) -> Option<bool> {
    let start = output.find('{')?;
    let end = output.rfind('}')?;
    if end < start {
        return None;
    }
    let v: Value = serde_json::from_str(&output[start..=end]).ok()?;
    v.get("loggedIn")?.as_bool()
}

/// `claude auth status --json` (exit code 1 when logged out, so only the JSON counts).
/// The child gets `env` as its whole environment, secrets stripped.
fn cli_logged_in(env: &HashMap<String, String>, home: &Path, claude: &Path) -> Option<bool> {
    if claude.as_os_str().is_empty() {
        return None;
    }
    let mut cmd = Command::new(claude);
    cmd.args(["auth", "status", "--json"])
        .current_dir(home)
        .env_clear()
        .envs(child_env(env.iter().map(|(k, v)| (k.clone(), v.clone()))));
    match proc::run(&mut cmd, None, CLI_TIMEOUT) {
        Ok(out) if !out.timed_out => {
            let parsed = parse_logged_in(&out.stdout);
            if parsed.is_none() {
                log::warn(&format!(
                    "could not parse `claude auth status` output (exit={})",
                    out.exit_code
                ));
            }
            parsed
        }
        Ok(_) => {
            log::warn("`claude auth status` timed out");
            None
        }
        Err(e) => {
            log::warn(&format!("`claude auth status` could not run: {}", e.kind()));
            None
        }
    }
}

/// Decides the login state. `env` is the worker's environment (also the CLI child's),
/// `home` the user's home directory, `claude` the CLI path.
pub fn probe(env: &HashMap<String, String>, home: &Path, claude: &Path) -> AuthState {
    if env_value(env, ENV_TOKEN).is_some_and(|t| !t.trim().is_empty()) {
        return state(true, "ENV_TOKEN", None);
    }
    let creds = claude_config_dir(env, home).join(".credentials.json");
    if let Some(s) = from_credentials(&creds, now_ms()) {
        return s;
    }
    match cli_logged_in(env, home, claude) {
        Some(ok) => state(ok, "CLI", None),
        None => AuthState::default(),
    }
}
