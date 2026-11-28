//! `auth_probe` tests; `home` and every file are temp paths the tests created.

mod common;

use common::TempDir;
use netis_worker_rs::auth_probe::{AuthState, probe};
use std::collections::HashMap;
use std::path::Path;

fn creds(dir: &TempDir, body: &str) {
    let claude_dir = dir.path().join(".claude");
    std::fs::create_dir_all(&claude_dir).unwrap();
    std::fs::write(claude_dir.join(".credentials.json"), body).unwrap();
}

const NO_CLAUDE: &str = "this-claude-does-not-exist-anywhere";

#[test]
fn env_token_wins() {
    let dir = TempDir::new("auth-env");
    creds(
        &dir,
        r#"{"claudeAiOauth":{"accessToken":"","refreshToken":""}}"#,
    );
    let mut env = HashMap::new();
    env.insert("claude_code_oauth_token".to_string(), "tok".to_string());
    let st = probe(&env, dir.path(), Path::new(NO_CLAUDE));
    assert_eq!(
        st,
        AuthState {
            ok: Some(true),
            method: Some("ENV_TOKEN".into()),
            expires_at: None
        }
    );
    // a blank token does not count
    env.insert("claude_code_oauth_token".to_string(), "  ".to_string());
    assert_ne!(
        probe(&env, dir.path(), Path::new(NO_CLAUDE))
            .method
            .as_deref(),
        Some("ENV_TOKEN")
    );
}

#[test]
fn credentials_file_gives_expiry_iso() {
    let dir = TempDir::new("auth-cred");
    // 2099-01-01T00:00:00Z
    creds(
        &dir,
        r#"{"claudeAiOauth":{"accessToken":"a","refreshToken":"r","expiresAt":1,"refreshTokenExpiresAt":4070908800000}}"#,
    );
    let st = probe(&HashMap::new(), dir.path(), Path::new(NO_CLAUDE));
    assert_eq!(
        st,
        AuthState {
            ok: Some(true),
            method: Some("CREDENTIALS".into()),
            expires_at: Some("2099-01-01T00:00:00Z".into())
        }
    );
}

#[test]
fn credentials_expired_empty_and_access_only() {
    let dir = TempDir::new("auth-cred2");
    creds(
        &dir,
        r#"{"claudeAiOauth":{"accessToken":"a","refreshToken":"r","refreshTokenExpiresAt":1000}}"#,
    );
    let st = probe(&HashMap::new(), dir.path(), Path::new(NO_CLAUDE));
    assert_eq!(st.ok, Some(false));
    assert_eq!(st.expires_at.as_deref(), Some("1970-01-01T00:00:01Z"));

    // both tokens blank -> NG
    creds(
        &dir,
        r#"{"claudeAiOauth":{"accessToken":" ","refreshToken":""}}"#,
    );
    let st = probe(&HashMap::new(), dir.path(), Path::new(NO_CLAUDE));
    assert_eq!(
        (st.ok, st.method.as_deref()),
        (Some(false), Some("CREDENTIALS"))
    );

    // no refresh token: expiresAt (not refreshTokenExpiresAt) decides
    creds(
        &dir,
        r#"{"claudeAiOauth":{"accessToken":"a","expiresAt":4070908800000,"refreshTokenExpiresAt":1}}"#,
    );
    let st = probe(&HashMap::new(), dir.path(), Path::new(NO_CLAUDE));
    assert_eq!(st.ok, Some(true));
    assert_eq!(st.expires_at.as_deref(), Some("2099-01-01T00:00:00Z"));
}

#[test]
fn claude_config_dir_is_honored_case_insensitively() {
    let dir = TempDir::new("auth-cfgdir");
    let cfg = dir.path().join("elsewhere");
    std::fs::create_dir_all(&cfg).unwrap();
    std::fs::write(
        cfg.join(".credentials.json"),
        r#"{"claudeAiOauth":{"refreshToken":"r"}}"#,
    )
    .unwrap();
    let mut env = HashMap::new();
    env.insert(
        "Claude_Config_Dir".to_string(),
        cfg.to_string_lossy().into_owned(),
    );
    let st = probe(&env, dir.path(), Path::new(NO_CLAUDE));
    assert_eq!(
        (st.ok, st.method.as_deref()),
        (Some(true), Some("CREDENTIALS"))
    );
}

#[test]
fn undetermined_is_all_none() {
    let dir = TempDir::new("auth-none");
    // no credentials file, claude cannot be started
    let st = probe(&HashMap::new(), dir.path(), Path::new(NO_CLAUDE));
    assert_eq!(
        st,
        AuthState {
            ok: None,
            method: None,
            expires_at: None
        }
    );
    // credentials file without claudeAiOauth (macOS keychain case) also falls through
    creds(&dir, r#"{"other":1}"#);
    assert_eq!(
        probe(&HashMap::new(), dir.path(), Path::new(NO_CLAUDE)).ok,
        None
    );
    // so does a broken file
    creds(&dir, "{ nope");
    assert_eq!(
        probe(&HashMap::new(), dir.path(), Path::new(NO_CLAUDE)).ok,
        None
    );
}

#[cfg(feature = "test-bins")]
mod cli {
    use super::*;

    fn fake() -> &'static Path {
        Path::new(env!("CARGO_BIN_EXE_fake-claude"))
    }

    fn env_with(json: &str) -> HashMap<String, String> {
        let mut env = HashMap::new();
        env.insert("FAKE_CLAUDE_AUTH_JSON".to_string(), json.to_string());
        if let Ok(root) = std::env::var("SystemRoot") {
            env.insert("SystemRoot".to_string(), root);
        }
        env
    }

    #[test]
    fn cli_status_logged_in() {
        let dir = TempDir::new("auth-cli");
        let st = probe(
            &env_with(r#"{"loggedIn":true,"authMethod":"x"}"#),
            dir.path(),
            fake(),
        );
        assert_eq!(
            st,
            AuthState {
                ok: Some(true),
                method: Some("CLI".into()),
                expires_at: None
            }
        );
    }

    #[test]
    fn cli_status_logged_out_and_noise_around_json() {
        let dir = TempDir::new("auth-cli2");
        let st = probe(
            &env_with("warning: x\n{\"loggedIn\":false}\ntrailing"),
            dir.path(),
            fake(),
        );
        assert_eq!((st.ok, st.method.as_deref()), (Some(false), Some("CLI")));
    }

    #[test]
    fn cli_status_unparseable_is_unknown() {
        let dir = TempDir::new("auth-cli3");
        assert_eq!(probe(&env_with("not json"), dir.path(), fake()).ok, None);
        assert_eq!(
            probe(&env_with(r#"{"loggedIn":"yes"}"#), dir.path(), fake()).ok,
            None
        );
    }
}

#[cfg(feature = "test-bins")]
mod cli_env {
    use super::*;

    #[test]
    fn child_inherits_process_env_overlays_map_and_strips_secrets() {
        let dir = TempDir::new("auth-cli-env");
        let record = dir.path().join("rec.json");
        let mut env = HashMap::new();
        env.insert(
            "FAKE_CLAUDE_AUTH_JSON".to_string(),
            r#"{"loggedIn":true}"#.to_string(),
        );
        env.insert(
            "FAKE_CLAUDE_RECORD".to_string(),
            record.to_string_lossy().into_owned(),
        );
        env.insert("FAKE_ONLY_IN_MAP".to_string(), "1".to_string());
        env.insert("worker_api_key".to_string(), "secret".to_string());
        let st = probe(
            &env,
            dir.path(),
            Path::new(env!("CARGO_BIN_EXE_fake-claude")),
        );
        assert_eq!(st.ok, Some(true));
        let rec: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(&record).unwrap()).unwrap();
        let keys: Vec<String> = rec["envKeys"]
            .as_array()
            .unwrap()
            .iter()
            .map(|k| k.as_str().unwrap().to_ascii_lowercase())
            .collect();
        assert!(keys.contains(&"fake_only_in_map".to_string()), "{keys:?}");
        assert!(!keys.contains(&"worker_api_key".to_string()), "{keys:?}");
        // the map is only partial, yet the real process env (PATH / SystemRoot) is inherited
        assert!(keys.contains(&"path".to_string()), "{keys:?}");
    }
}
