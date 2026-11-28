//! `mcp` module tests; every file lives in a temp directory the test created.

mod common;

use common::TempDir;
use netis_worker_rs::dto::McpSpec;
use netis_worker_rs::mcp::McpSupport;
use serde_json::{Value, json};
use std::collections::BTreeMap;

fn write_json(dir: &TempDir, name: &str, v: &Value) -> std::path::PathBuf {
    let p = dir.path().join(name);
    std::fs::write(&p, v.to_string()).unwrap();
    p
}

fn names(m: &McpSupport) -> Vec<&str> {
    m.server_names().iter().map(String::as_str).collect()
}

#[test]
fn merges_top_level_and_project_servers_last_wins() {
    let dir = TempDir::new("mcp-merge");
    // written as text: the order of keys in the file is what the merge follows
    let cj = dir.path().join("claude.json");
    std::fs::write(
        &cj,
        r#"{
          "mcpServers": {"zeta": {"type":"http","url":"https://top/zeta"}, "beta": {"command":"x"}},
          "projects": {
            "/p1": {"mcpServers": {"gamma": {"url":"https://p1/gamma"}, "zeta": {"url":"https://p1/zeta"}}},
            "/p2": {"other": 1},
            "/p3": {"mcpServers": {"zeta": {"url":"https://p3/zeta"}}},
            "/p4": 5
          }
        }"#,
    )
    .unwrap();
    let out = dir.path().join("sub").join("worker-mcp-w1.json");
    let m = McpSupport::init(&cj, &out);
    // first-seen order (not alphabetical), later definitions override earlier ones
    assert_eq!(names(&m), ["zeta", "beta", "gamma"]);
    let written: Value = serde_json::from_str(&std::fs::read_to_string(&out).unwrap()).unwrap();
    assert_eq!(written["mcpServers"]["zeta"]["url"], "https://p3/zeta");
    assert_eq!(written["mcpServers"]["gamma"]["url"], "https://p1/gamma");
    assert_eq!(written["mcpServers"]["beta"]["command"], "x");
}

#[test]
fn no_servers_means_no_args() {
    let dir = TempDir::new("mcp-none");
    let cj = write_json(
        &dir,
        "claude.json",
        &json!({"mcpServers": {}, "projects": {"/p": {}}}),
    );
    let out = dir.path().join("worker-mcp.json");
    let m = McpSupport::init(&cj, &out);
    assert!(m.server_names().is_empty());
    assert!(!out.exists(), "no file when there are no servers");
    let a = m.args_for(&[]).unwrap();
    assert!(a.args.is_empty());
    assert!(a.temp_file.is_none());
}

#[test]
fn base_only_args() {
    let dir = TempDir::new("mcp-base");
    let cj = dir.path().join("claude.json");
    std::fs::write(
        &cj,
        r#"{"mcpServers": {"a": {"url":"u"}, "b": {"url":"v"}}}"#,
    )
    .unwrap();
    let out = dir.path().join("worker-mcp.json");
    let m = McpSupport::init(&cj, &out);
    let a = m.args_for(&[]).unwrap();
    assert_eq!(
        a.args,
        vec![
            "--mcp-config".to_string(),
            out.to_string_lossy().into_owned(),
            "--strict-mcp-config".to_string(),
            "--allowedTools".to_string(),
            "mcp__a,mcp__b".to_string(),
        ]
    );
    assert!(a.temp_file.is_none());
}

#[test]
fn extras_overlay_uses_transport_as_type_and_optional_headers() {
    let dir = TempDir::new("mcp-extras");
    let cj = dir.path().join("claude.json");
    std::fs::write(
        &cj,
        r#"{"mcpServers": {"a": {"type":"stdio","command":"x"}, "b": {"url":"v"}}}"#,
    )
    .unwrap();
    let out = dir.path().join("worker-mcp.json");
    let m = McpSupport::init(&cj, &out).with_temp_dir(dir.path());
    let mut headers = BTreeMap::new();
    headers.insert("Authorization".to_string(), "Bearer t".to_string());
    let extras = vec![
        McpSpec {
            name: "a".into(),
            url: "https://x/a".into(),
            transport: "sse".into(),
            headers: Some(headers),
        },
        McpSpec {
            name: "c".into(),
            url: "https://x/c".into(),
            transport: "http".into(),
            headers: Some(BTreeMap::new()),
        },
    ];
    let a = m.args_for(&extras).unwrap();
    let tmp = a.temp_file.clone().expect("temp file");
    assert_ne!(tmp, out);
    assert_eq!(a.args[0], "--mcp-config");
    assert_eq!(a.args[1], tmp.to_string_lossy());
    assert_eq!(a.args[2], "--strict-mcp-config");
    assert_eq!(a.args[4], "mcp__a,mcp__b,mcp__c"); // base order, extras appended, deduped
    let v: Value = serde_json::from_str(&std::fs::read_to_string(&tmp).unwrap()).unwrap();
    let s = &v["mcpServers"];
    assert_eq!(
        s["a"],
        json!({"type":"sse","url":"https://x/a","headers":{"Authorization":"Bearer t"}})
    );
    assert_eq!(s["b"], json!({"url":"v"}));
    assert_eq!(s["c"], json!({"type":"http","url":"https://x/c"})); // empty headers omitted
    // base file untouched
    let base: Value = serde_json::from_str(&std::fs::read_to_string(&out).unwrap()).unwrap();
    assert_eq!(base["mcpServers"]["a"]["type"], "stdio");
    std::fs::remove_file(tmp).unwrap();
}

#[test]
fn extras_without_base_servers_still_get_args() {
    let dir = TempDir::new("mcp-extras-only");
    let m = McpSupport::init(&dir.path().join("none.json"), &dir.path().join("o.json"))
        .with_temp_dir(dir.path());
    let extras = vec![McpSpec {
        name: "only".into(),
        url: "https://x".into(),
        transport: "http".into(),
        headers: None,
    }];
    let a = m.args_for(&extras).unwrap();
    assert_eq!(a.args[4], "mcp__only");
    std::fs::remove_file(a.temp_file.unwrap()).unwrap();
}

#[test]
fn missing_claude_json_is_empty_not_error() {
    let dir = TempDir::new("mcp-missing");
    let out = dir.path().join("worker-mcp.json");
    let m = McpSupport::init(&dir.path().join("absent.json"), &out);
    assert!(m.server_names().is_empty());
    assert!(m.args_for(&[]).unwrap().args.is_empty());
    assert!(!out.exists());
}

#[test]
fn unreadable_claude_json_is_empty_not_error() {
    let dir = TempDir::new("mcp-broken");
    let cj = dir.path().join("claude.json");
    std::fs::write(&cj, "{ not json").unwrap();
    let m = McpSupport::init(&cj, &dir.path().join("o.json"));
    assert!(m.server_names().is_empty());
}

#[cfg(unix)]
#[test]
fn temp_and_base_files_are_owner_only() {
    use std::os::unix::fs::PermissionsExt;
    let dir = TempDir::new("mcp-mode");
    let cj = write_json(
        &dir,
        "claude.json",
        &json!({"mcpServers": {"a": {"url":"u"}}}),
    );
    let out = dir.path().join("worker-mcp.json");
    // a pre-existing, too-open base file is tightened as well
    std::fs::write(&out, "{}").unwrap();
    std::fs::set_permissions(&out, std::fs::Permissions::from_mode(0o644)).unwrap();
    let m = McpSupport::init(&cj, &out).with_temp_dir(dir.path());
    let mode = |p: &std::path::Path| std::fs::metadata(p).unwrap().permissions().mode() & 0o777;
    assert_eq!(mode(&out), 0o600);
    let extras = vec![McpSpec {
        name: "x".into(),
        url: "https://x".into(),
        transport: "http".into(),
        headers: None,
    }];
    let a = m.args_for(&extras).unwrap();
    assert_eq!(mode(a.temp_file.as_ref().unwrap()), 0o600);
    std::fs::remove_file(a.temp_file.unwrap()).unwrap();
}
