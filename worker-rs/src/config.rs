//! Worker settings from the environment. `Config::from_env` takes the environment and the
//! home directory as arguments so tests never touch the real ones.

use crate::contract::{IMPLEMENTED_KINDS, known_kinds};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::Duration;

const MAX_ID_LEN: usize = 50;
const DEFAULT_VERSION: &str = "0.1.0";

#[derive(Clone, PartialEq)]
pub struct Config {
    pub worker_id: String,
    pub api_base_url: String,
    pub api_key: String,
    pub kinds: Vec<String>,
    pub version: String,
    pub repos_dir: PathBuf,
    pub dead_letter_dir: PathBuf,
    pub claude_cli: PathBuf,
    pub github_pat: Option<String>,
    pub gitlab_token: Option<String>,
    pub auth_check_interval: Duration,
    pub home: PathBuf,
}

/// Hand-written so the API key and repo tokens are never printed (`***` when set).
impl std::fmt::Debug for Config {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let hidden = |v: &Option<String>| v.as_ref().map(|_| "***");
        f.debug_struct("Config")
            .field("worker_id", &self.worker_id)
            .field("api_base_url", &crate::mask::mask(&self.api_base_url))
            .field("api_key", &"***")
            .field("kinds", &self.kinds)
            .field("version", &self.version)
            .field("repos_dir", &self.repos_dir)
            .field("dead_letter_dir", &self.dead_letter_dir)
            .field("claude_cli", &self.claude_cli)
            .field("github_pat", &hidden(&self.github_pat))
            .field("gitlab_token", &hidden(&self.gitlab_token))
            .field("auth_check_interval", &self.auth_check_interval)
            .field("home", &self.home)
            .finish()
    }
}

/// Value of `key`, trimmed; `None` when missing or blank.
fn get<'a>(env: &'a HashMap<String, String>, key: &str) -> Option<&'a str> {
    env.get(key).map(|v| v.trim()).filter(|v| !v.is_empty())
}

/// Length in UTF-16 code units (the unit the Java side counts in).
fn utf16_len(s: &str) -> usize {
    s.encode_utf16().count()
}

fn required<'a>(env: &'a HashMap<String, String>, key: &str) -> Result<&'a str, String> {
    get(env, key).ok_or_else(|| format!("{key} is required"))
}

fn parse_kinds(env: &HashMap<String, String>) -> Result<Vec<String>, String> {
    let mut kinds: Vec<String> = Vec::new();
    if let Some(raw) = get(env, "WORKER_KINDS") {
        for part in raw.split(',') {
            let kind = part.trim().to_uppercase();
            if kind.is_empty() {
                continue;
            }
            if !known_kinds().contains(&kind) {
                return Err(format!("WORKER_KINDS has an unknown kind: {kind}"));
            }
            if !IMPLEMENTED_KINDS.contains(&kind.as_str()) {
                return Err(format!(
                    "WORKER_KINDS has a kind this worker does not implement: {kind}"
                ));
            }
            if !kinds.contains(&kind) {
                kinds.push(kind);
            }
        }
    }
    if kinds.is_empty() {
        kinds = IMPLEMENTED_KINDS.iter().map(|k| k.to_string()).collect();
    }
    Ok(kinds)
}

/// `CLAUDE_CLI` if it is an existing file, else `claude(.exe)` on `PATH`, else `~/.local/bin`.
fn find_claude(env: &HashMap<String, String>, home: &Path) -> Result<PathBuf, String> {
    if let Some(explicit) = get(env, "CLAUDE_CLI") {
        let p = PathBuf::from(explicit);
        if p.is_file() {
            return Ok(p);
        }
    }
    let names = ["claude.exe", "claude"];
    if let Some(path) = env.get("PATH").or_else(|| env.get("Path")) {
        for dir in std::env::split_paths(path) {
            for name in names {
                let p = dir.join(name);
                if p.is_file() {
                    return Ok(p);
                }
            }
        }
    }
    for name in names {
        let p = home.join(".local").join("bin").join(name);
        if p.is_file() {
            return Ok(p);
        }
    }
    Err(
        "claude executable not found (set CLAUDE_CLI, or put claude on PATH or in ~/.local/bin)"
            .to_string(),
    )
}

impl Config {
    pub fn from_env(env: &HashMap<String, String>, home: &Path) -> Result<Config, String> {
        let worker_id = required(env, "WORKER_ID")?.to_string();
        let api_base_url = required(env, "API_BASE_URL")?.to_string();
        let api_key = required(env, "WORKER_API_KEY")?.to_string();
        if utf16_len(&worker_id) > MAX_ID_LEN {
            return Err(format!("WORKER_ID is longer than {MAX_ID_LEN} characters"));
        }
        let version = get(env, "WORKER_VERSION")
            .unwrap_or(DEFAULT_VERSION)
            .to_string();
        if utf16_len(&version) > MAX_ID_LEN {
            return Err(format!(
                "WORKER_VERSION is longer than {MAX_ID_LEN} characters"
            ));
        }
        if !api_base_url.starts_with("http://") {
            return Err("API_BASE_URL must start with http://".to_string());
        }
        let kinds = parse_kinds(env)?;
        let claude_cli = find_claude(env, home)?;
        let base = home.join("netis-maker");
        let dir = |key: &str, default: &str| {
            get(env, key)
                .map(PathBuf::from)
                .unwrap_or_else(|| base.join(default))
        };
        Ok(Config {
            worker_id,
            api_base_url,
            api_key,
            kinds,
            version,
            repos_dir: dir("REPOS_DIR", "repos"),
            dead_letter_dir: dir("DEAD_LETTER_DIR", "dead-letter"),
            claude_cli,
            github_pat: get(env, "GITHUB_PAT").map(str::to_string),
            gitlab_token: get(env, "GITLAB_TOKEN").map(str::to_string),
            auth_check_interval: Duration::from_secs(300),
            home: home.to_path_buf(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    struct Fixture {
        home: PathBuf,
        claude: PathBuf,
    }

    impl Fixture {
        fn new(tag: &str) -> Fixture {
            let home = std::env::temp_dir().join(format!(
                "netis-worker-rs-config-{}-{tag}",
                std::process::id()
            ));
            let _ = fs::remove_dir_all(&home);
            fs::create_dir_all(&home).unwrap();
            let claude = home.join("fake-claude");
            fs::write(&claude, "").unwrap();
            Fixture { home, claude }
        }

        fn env(&self) -> HashMap<String, String> {
            let mut m = HashMap::new();
            m.insert("WORKER_ID".into(), "w-1".into());
            m.insert("API_BASE_URL".into(), "http://localhost:8090".into());
            m.insert("WORKER_API_KEY".into(), "k".into());
            m.insert("CLAUDE_CLI".into(), self.claude.to_string_lossy().into());
            m
        }

        fn load(&self, env: &HashMap<String, String>) -> Result<Config, String> {
            Config::from_env(env, &self.home)
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.home);
        }
    }

    #[test]
    fn debug_never_prints_secrets() {
        let f = Fixture::new("debug-redacted");
        let mut env = f.env();
        env.insert("WORKER_API_KEY".into(), "api-key-secret-1".into());
        env.insert("GITHUB_PAT".into(), "github-pat-secret-2".into());
        env.insert("GITLAB_TOKEN".into(), "gitlab-token-secret-3".into());
        let cfg = f.load(&env).unwrap();
        for text in [format!("{cfg:?}"), format!("{cfg:#?}")] {
            for secret in [
                "api-key-secret-1",
                "github-pat-secret-2",
                "gitlab-token-secret-3",
            ] {
                assert!(!text.contains(secret), "{secret} leaked: {text}");
            }
            assert!(text.contains("w-1"), "{text}");
        }
    }

    #[test]
    fn empty_kinds_default_to_implemented_set() {
        let f = Fixture::new("kinds-default");
        let mut env = f.env();
        assert_eq!(f.load(&env).unwrap().kinds, ["ANALYSIS"]);
        env.insert("WORKER_KINDS".into(), "".into());
        assert_eq!(f.load(&env).unwrap().kinds, ["ANALYSIS"]);
    }

    #[test]
    fn unknown_kind_is_fatal() {
        let f = Fixture::new("kinds-unknown");
        let mut env = f.env();
        env.insert("WORKER_KINDS".into(), "ANALYSIS,BOGUS".into());
        assert!(f.load(&env).unwrap_err().contains("BOGUS"));
    }

    #[test]
    fn unimplemented_kind_is_fatal() {
        let f = Fixture::new("kinds-unimpl");
        let mut env = f.env();
        env.insert("WORKER_KINDS".into(), "DEPLOY".into());
        assert!(f.load(&env).unwrap_err().contains("DEPLOY"));
    }

    #[test]
    fn kinds_are_trimmed_and_uppercased() {
        let f = Fixture::new("kinds-trim");
        let mut env = f.env();
        env.insert("WORKER_KINDS".into(), " analysis ".into());
        assert_eq!(f.load(&env).unwrap().kinds, ["ANALYSIS"]);
    }

    #[test]
    fn https_base_url_is_fatal() {
        let f = Fixture::new("https");
        let mut env = f.env();
        env.insert("API_BASE_URL".into(), "https://example.com".into());
        assert!(f.load(&env).unwrap_err().contains("http://"));
    }

    #[test]
    fn missing_required_is_fatal() {
        let f = Fixture::new("missing");
        for key in ["WORKER_ID", "API_BASE_URL", "WORKER_API_KEY"] {
            let mut env = f.env();
            env.remove(key);
            assert!(f.load(&env).unwrap_err().contains(key), "{key}");
            env.insert(key.into(), "  ".into());
            assert!(f.load(&env).unwrap_err().contains(key), "blank {key}");
        }
    }

    #[test]
    fn worker_id_over_50_is_fatal() {
        let f = Fixture::new("id-len");
        let mut env = f.env();
        env.insert("WORKER_ID".into(), "a".repeat(51));
        assert!(f.load(&env).unwrap_err().contains("WORKER_ID"));
        env.insert("WORKER_ID".into(), "a".repeat(50));
        assert!(f.load(&env).is_ok());
        // 25 emoji are 50 UTF-16 units; 26 are 52.
        env.insert("WORKER_ID".into(), "😀".repeat(25));
        assert!(f.load(&env).is_ok());
        env.insert("WORKER_ID".into(), "😀".repeat(26));
        assert!(f.load(&env).is_err());
    }

    #[test]
    fn version_over_50_is_fatal() {
        let f = Fixture::new("ver-len");
        let mut env = f.env();
        env.insert("WORKER_VERSION".into(), "v".repeat(51));
        assert!(f.load(&env).unwrap_err().contains("WORKER_VERSION"));
    }

    #[test]
    fn claude_cli_missing_is_fatal() {
        let f = Fixture::new("claude-missing");
        let mut env = f.env();
        env.insert(
            "CLAUDE_CLI".into(),
            f.home.join("nope").to_string_lossy().into(),
        );
        assert!(f.load(&env).unwrap_err().contains("claude"));
    }

    #[test]
    fn claude_cli_resolution() {
        let f = Fixture::new("claude-resolve");
        let mut env = f.env();
        assert_eq!(f.load(&env).unwrap().claude_cli, f.claude);

        env.remove("CLAUDE_CLI");
        let bin = f.home.join(".local").join("bin");
        fs::create_dir_all(&bin).unwrap();
        fs::write(bin.join("claude"), "").unwrap();
        assert_eq!(f.load(&env).unwrap().claude_cli, bin.join("claude"));

        fs::write(bin.join("claude.exe"), "").unwrap();
        assert_eq!(f.load(&env).unwrap().claude_cli, bin.join("claude.exe"));

        // a CLAUDE_CLI that does not exist falls through to the search
        env.insert(
            "CLAUDE_CLI".into(),
            f.home.join("nope").to_string_lossy().into(),
        );
        assert_eq!(f.load(&env).unwrap().claude_cli, bin.join("claude.exe"));
    }

    #[test]
    fn defaults() {
        let f = Fixture::new("defaults");
        let cfg = f.load(&f.env()).unwrap();
        assert_eq!(cfg.repos_dir, f.home.join("netis-maker").join("repos"));
        assert_eq!(
            cfg.dead_letter_dir,
            f.home.join("netis-maker").join("dead-letter")
        );
        assert_eq!(cfg.version, "0.1.0");
        assert_eq!(cfg.auth_check_interval, Duration::from_secs(300));
        assert_eq!(cfg.home, f.home);
        assert_eq!(cfg.github_pat, None);
        assert_eq!(cfg.gitlab_token, None);
    }

    #[test]
    fn overrides_and_tokens() {
        let f = Fixture::new("overrides");
        let mut env = f.env();
        env.insert("REPOS_DIR".into(), "/x/repos".into());
        env.insert("DEAD_LETTER_DIR".into(), "/x/dead".into());
        env.insert("GITHUB_PAT".into(), "p".into());
        env.insert("GITLAB_TOKEN".into(), "g".into());
        env.insert("WORKER_VERSION".into(), "rs-abc1234".into());
        let cfg = f.load(&env).unwrap();
        assert_eq!(cfg.repos_dir, PathBuf::from("/x/repos"));
        assert_eq!(cfg.dead_letter_dir, PathBuf::from("/x/dead"));
        assert_eq!(cfg.github_pat.as_deref(), Some("p"));
        assert_eq!(cfg.gitlab_token.as_deref(), Some("g"));
        assert_eq!(cfg.version, "rs-abc1234");
    }
}
