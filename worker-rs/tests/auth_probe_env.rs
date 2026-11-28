//! Secrets in the REAL process environment must not reach `claude auth status`.
//! Single test in its own binary: it mutates this process's env, so nothing can race.
#![cfg(feature = "test-bins")]

mod common;

use common::TempDir;
use netis_worker_rs::auth_probe::probe;
use std::collections::HashMap;
use std::path::Path;

#[test]
fn real_env_secrets_are_stripped_but_other_vars_inherited() {
    let dir = TempDir::new("auth-real-env");
    let record = dir.path().join("rec.json");
    // SAFETY: the only test in this binary, so no other thread reads or writes the env.
    unsafe {
        std::env::set_var("NETIS_TEST_INHERIT_MARKER", "1");
        std::env::set_var("GITLAB_TOKEN", "glpat-secret");
        std::env::set_var("gh_token", "ghp-secret"); // differently-cased spelling
        std::env::set_var("Worker_Api_Key", "k");
    }
    let mut env = HashMap::new();
    env.insert(
        "FAKE_CLAUDE_AUTH_JSON".to_string(),
        r#"{"loggedIn":true}"#.to_string(),
    );
    env.insert(
        "FAKE_CLAUDE_RECORD".to_string(),
        record.to_string_lossy().into_owned(),
    );
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
    assert!(
        keys.contains(&"netis_test_inherit_marker".to_string()),
        "{keys:?}"
    );
    for secret in ["gitlab_token", "gh_token", "worker_api_key"] {
        assert!(
            !keys.contains(&secret.to_string()),
            "{secret} leaked: {keys:?}"
        );
    }
}
