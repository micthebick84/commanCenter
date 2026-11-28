//! `claude` module tests: pure helpers always, process-driving tests need `--features test-bins`.

mod common;

use netis_worker_rs::claude::{build_args, child_env, iso_duration, parse_envelope, tail_utf16};
use netis_worker_rs::dto::Usage;
use std::time::Duration;

fn s(v: &[&str]) -> Vec<String> {
    v.iter().map(|x| x.to_string()).collect()
}

#[test]
fn args_minimal() {
    assert_eq!(
        build_args(None, None, &[]),
        s(&[
            "-p",
            "--output-format",
            "json",
            "--setting-sources",
            "project"
        ])
    );
}

#[test]
fn args_with_model_effort_and_mcp_in_java_order() {
    let mcp = s(&[
        "--mcp-config",
        "/x/mcp.json",
        "--strict-mcp-config",
        "--allowedTools",
        "mcp__a",
    ]);
    assert_eq!(
        build_args(Some("sonnet"), Some("high"), &mcp),
        s(&[
            "-p",
            "--output-format",
            "json",
            "--setting-sources",
            "project",
            "--model",
            "sonnet",
            "--effort",
            "high",
            "--mcp-config",
            "/x/mcp.json",
            "--strict-mcp-config",
            "--allowedTools",
            "mcp__a",
        ])
    );
}

#[test]
fn blank_model_is_omitted() {
    let args = build_args(Some("  "), Some(""), &[]);
    assert_eq!(args, build_args(None, None, &[]));
    let args = build_args(Some(" "), Some("low"), &[]);
    assert!(!args.contains(&"--model".to_string()));
    assert!(args.windows(2).any(|w| w == ["--effort", "low"]));
}

#[test]
fn envelope_result_and_model_usage_sum() {
    let raw = r#"{"type":"result","result":"hello","total_cost_usd":0.25,
        "usage":{"input_tokens":1,"output_tokens":1,"cache_creation_input_tokens":1,"cache_read_input_tokens":1},
        "modelUsage":{
          "m1":{"inputTokens":10,"outputTokens":5,"cacheCreationInputTokens":3,"cacheReadInputTokens":2},
          "m2":{"inputTokens":20,"outputTokens":7,"cacheCreationInputTokens":4,"cacheReadInputTokens":1}}}"#;
    let (text, usage) = parse_envelope(raw);
    assert_eq!(text, "hello");
    assert_eq!(
        usage,
        Some(Usage {
            cost_usd: 0.25,
            input_tokens: 30,
            output_tokens: 12,
            cache_creation_tokens: 7,
            cache_read_tokens: 3,
        })
    );
}

#[test]
fn envelope_falls_back_to_usage_fields() {
    let raw = r#"{"result":"ok","usage":{"input_tokens":11,"output_tokens":22,
        "cache_creation_input_tokens":33,"cache_read_input_tokens":44},"modelUsage":{}}"#;
    let (text, usage) = parse_envelope(raw);
    assert_eq!(text, "ok");
    assert_eq!(
        usage,
        Some(Usage {
            cost_usd: 0.0,
            input_tokens: 11,
            output_tokens: 22,
            cache_creation_tokens: 33,
            cache_read_tokens: 44,
        })
    );
}

#[test]
fn envelope_without_cost_or_usage_is_raw() {
    let raw = r#"{"result":"only text"}"#;
    assert_eq!(parse_envelope(raw), (raw.to_string(), None));
}

#[test]
fn envelope_result_not_string_is_raw() {
    let raw = r#"{"result":{"a":1},"total_cost_usd":1.5}"#;
    let (text, usage) = parse_envelope(raw);
    assert_eq!(text, raw);
    assert_eq!(usage.map(|u| u.cost_usd), Some(1.5));
}

#[test]
fn non_json_is_raw_without_usage() {
    assert_eq!(
        parse_envelope("plain text\n"),
        ("plain text\n".to_string(), None)
    );
    assert_eq!(parse_envelope("[1,2]"), ("[1,2]".to_string(), None));
    assert_eq!(parse_envelope("  \n"), ("  \n".to_string(), None));
    assert_eq!(parse_envelope(""), (String::new(), None));
}

#[test]
fn envelope_ignores_trailing_tokens_like_jackson() {
    let (text, usage) = parse_envelope("{\"result\":\"x\",\"total_cost_usd\":2}\ngarbage");
    assert_eq!(text, "x");
    assert!(usage.is_some());
}

#[test]
fn child_env_drops_secret_names_case_insensitively_and_keeps_claude_vars() {
    let parent = vec![
        ("WORKER_API_KEY".to_string(), "k".to_string()),
        ("gitlab_token".to_string(), "t".to_string()),
        ("Pgpassword".to_string(), "p".to_string()),
        ("CLAUDE_CODE_OAUTH_TOKEN".to_string(), "c".to_string()),
        ("CLAUDE_CONFIG_DIR".to_string(), "/c".to_string()),
        ("PATH".to_string(), "/bin".to_string()),
    ];
    let kept: Vec<String> = child_env(parent.into_iter())
        .into_iter()
        .map(|(k, _)| k)
        .collect();
    assert_eq!(
        kept,
        s(&["CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CONFIG_DIR", "PATH"])
    );
}

#[test]
#[allow(non_snake_case)]
fn iso_duration_10min_is_PT10M() {
    assert_eq!(iso_duration(Duration::from_secs(600)), "PT10M");
    assert_eq!(iso_duration(Duration::from_secs(30)), "PT30S");
    assert_eq!(iso_duration(Duration::from_secs(3600)), "PT1H");
    assert_eq!(iso_duration(Duration::from_secs(5400)), "PT1H30M");
    assert_eq!(iso_duration(Duration::from_secs(90061)), "PT25H1M1S");
    assert_eq!(iso_duration(Duration::ZERO), "PT0S");
    assert_eq!(iso_duration(Duration::from_millis(1500)), "PT1.5S");
}

#[test]
fn tail_utf16_counts_units_and_never_splits_surrogates() {
    assert_eq!(tail_utf16("abc", 5), ("abc".to_string(), false));
    assert_eq!(tail_utf16("abcdef", 3), ("def".to_string(), true));
    // each emoji is 2 units: 5 units cannot hold 3 emoji, and must not split one
    assert_eq!(tail_utf16("😀😀😀", 5), ("😀😀".to_string(), true));
    assert_eq!(tail_utf16("한글😀", 3), ("글😀".to_string(), true));
    assert_eq!(tail_utf16("한글😀", 2), ("😀".to_string(), true));
    assert_eq!(tail_utf16("😀", 1), (String::new(), true));
    assert_eq!(tail_utf16("😀", 2), ("😀".to_string(), false));
}

#[test]
fn claude_exec_debug_never_prints_mcp_secrets() {
    use netis_worker_rs::claude::ClaudeExec;
    use netis_worker_rs::mcp::McpSupport;
    let dir = common::TempDir::new("claude-debug");
    let cj = dir.path().join("claude.json");
    std::fs::write(
        &cj,
        r#"{"mcpServers": {"alpha": {"type": "http", "url": "https://example.test/a",
            "headers": {"Authorization": "Bearer exec-debug-header-secret"}},
            "beta": {"command": "x", "env": {"TOKEN": "exec-debug-env-secret"}}}}"#,
    )
    .unwrap();
    let exec = ClaudeExec {
        cli: "claude".into(),
        mcp: McpSupport::init(&cj, &dir.path().join("out.json")),
    };
    for text in [format!("{exec:?}"), format!("{exec:#?}")] {
        assert!(!text.contains("exec-debug-header-secret"), "{text}");
        assert!(!text.contains("exec-debug-env-secret"), "{text}");
        assert!(text.contains("alpha"), "{text}");
    }
}

#[cfg(feature = "test-bins")]
mod integration {
    use super::common::TempDir;
    use netis_worker_rs::claude::{ClaudeExec, build_args, tail_utf16};
    use netis_worker_rs::dto::McpSpec;
    use netis_worker_rs::mcp::McpSupport;
    use std::path::Path;
    use std::process::Command;
    use std::time::{Duration, Instant};

    fn base_env(extra: &[(&str, &str)]) -> Vec<(String, String)> {
        let mut env: Vec<(String, String)> = Vec::new();
        for key in ["SystemRoot", "PATH"] {
            if let Ok(v) = std::env::var(key) {
                env.push((key.to_string(), v));
            }
        }
        for (k, v) in extra {
            env.push((k.to_string(), v.to_string()));
        }
        env
    }

    fn exec_for(dir: &TempDir) -> ClaudeExec {
        // no ~/.claude.json: an empty McpSupport, never the real home
        let mcp = McpSupport::init(
            &dir.path().join("absent.claude.json"),
            &dir.path().join("worker-mcp.json"),
        );
        ClaudeExec {
            cli: env!("CARGO_BIN_EXE_fake-claude").into(),
            mcp,
        }
    }

    fn read_record(path: &Path) -> serde_json::Value {
        serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
    }

    fn strings(v: &serde_json::Value) -> Vec<String> {
        v.as_array()
            .unwrap()
            .iter()
            .map(|x| x.as_str().unwrap().to_string())
            .collect()
    }

    #[test]
    fn prompt_goes_to_stdin_utf8() {
        let dir = TempDir::new("claude-stdin");
        let work = dir.path().join("work");
        std::fs::create_dir_all(&work).unwrap();
        let record = dir.path().join("rec.json");
        let exec = exec_for(&dir);
        let prompt = "안녕하세요 프롬프트 😀\n둘째 줄";
        let res = exec
            .exec_with_env(
                prompt,
                &work,
                Duration::from_secs(30),
                &[],
                Some("sonnet"),
                Some("  "),
                base_env(&[("FAKE_CLAUDE_RECORD", record.to_str().unwrap())]).into_iter(),
            )
            .unwrap();
        assert_eq!(res.exit_code, 0);
        let rec = read_record(&record);
        assert_eq!(rec["stdin"], prompt);
        let cwd = Path::new(rec["cwd"].as_str().unwrap())
            .canonicalize()
            .unwrap();
        assert_eq!(cwd, work.canonicalize().unwrap());
        assert_eq!(
            strings(&rec["args"]),
            build_args(Some("sonnet"), Some("  "), &[])
        );
    }

    #[test]
    fn secret_env_not_visible_to_child() {
        let dir = TempDir::new("claude-env");
        let record = dir.path().join("rec.json");
        let exec = exec_for(&dir);
        exec.exec_with_env(
            "p",
            dir.path(),
            Duration::from_secs(30),
            &[],
            None,
            None,
            base_env(&[
                ("FAKE_CLAUDE_RECORD", record.to_str().unwrap()),
                ("WORKER_API_KEY", "super-secret"),
                ("gitlab_token", "glpat-x"),
                ("CLAUDE_CODE_OAUTH_TOKEN", "keep-me"),
            ])
            .into_iter(),
        )
        .unwrap();
        let keys: Vec<String> = strings(&read_record(&record)["envKeys"])
            .into_iter()
            .map(|k| k.to_ascii_lowercase())
            .collect();
        assert!(!keys.contains(&"worker_api_key".to_string()), "{keys:?}");
        assert!(!keys.contains(&"gitlab_token".to_string()), "{keys:?}");
        assert!(
            keys.contains(&"claude_code_oauth_token".to_string()),
            "{keys:?}"
        );
    }

    #[test]
    fn nonzero_exit_appends_stderr_tail() {
        let dir = TempDir::new("claude-stderr");
        let exec = exec_for(&dir);
        let long = format!("{}😀", "한".repeat(3000));
        let res = exec
            .exec_with_env(
                "p",
                dir.path(),
                Duration::from_secs(30),
                &[],
                None,
                None,
                base_env(&[("FAKE_CLAUDE_EXIT", "3"), ("FAKE_CLAUDE_STDERR", &long)]).into_iter(),
            )
            .unwrap();
        assert_eq!(res.exit_code, 3);
        // Java reads stderr line by line and appends '\n' to every line, so the tail is
        // the last 2000 UTF-16 units of "<stderr>\n": 1997 Hangul + emoji (2) + '\n' (1).
        let tail = format!("{}😀\n", "한".repeat(1997));
        assert_eq!(res.stdout, format!("\n[stderr]\n…{tail}"));
        assert_eq!(tail_utf16(&format!("{long}\n"), 2000), (tail, true));

        // short stderr: no ellipsis; exit 0 never appends
        let res = exec
            .exec_with_env(
                "p",
                dir.path(),
                Duration::from_secs(30),
                &[],
                None,
                None,
                base_env(&[("FAKE_CLAUDE_EXIT", "2"), ("FAKE_CLAUDE_STDERR", "boom")]).into_iter(),
            )
            .unwrap();
        assert_eq!(res.stdout, "\n[stderr]\nboom\n");
        let res = exec
            .exec_with_env(
                "p",
                dir.path(),
                Duration::from_secs(30),
                &[],
                None,
                None,
                base_env(&[("FAKE_CLAUDE_STDERR", "warn only")]).into_iter(),
            )
            .unwrap();
        assert_eq!(res.stdout, "");
    }

    #[test]
    fn envelope_on_stdout_is_unwrapped() {
        let dir = TempDir::new("claude-envelope");
        let out = dir.path().join("out.json");
        std::fs::write(
            &out,
            r#"{"result":"분석 끝","total_cost_usd":0.5,"usage":{}}"#,
        )
        .unwrap();
        let exec = exec_for(&dir);
        let res = exec
            .exec_with_env(
                "p",
                dir.path(),
                Duration::from_secs(30),
                &[],
                None,
                None,
                base_env(&[("FAKE_CLAUDE_STDOUT_FILE", out.to_str().unwrap())]).into_iter(),
            )
            .unwrap();
        assert_eq!(res.stdout, "분석 끝");
        assert_eq!(res.usage.unwrap().cost_usd, 0.5);
    }

    fn pid_alive(pid: u32) -> bool {
        if cfg!(windows) {
            let out = Command::new("tasklist")
                .args(["/FI", &format!("PID eq {pid}"), "/NH"])
                .output()
                .expect("tasklist");
            String::from_utf8_lossy(&out.stdout).contains(&pid.to_string())
        } else {
            Command::new("kill")
                .args(["-0", &pid.to_string()])
                .status()
                .map(|s| s.success())
                .unwrap_or(false)
        }
    }

    fn force_kill(pid: u32) {
        if cfg!(windows) {
            let _ = Command::new("taskkill")
                .args(["/PID", &pid.to_string(), "/F"])
                .output();
        } else {
            let _ = Command::new("kill")
                .args(["-KILL", &pid.to_string()])
                .status();
        }
    }

    #[test]
    fn timeout_returns_err_and_kills_grandchild() {
        let dir = TempDir::new("claude-timeout");
        let pid_file = dir.path().join("child.pid");
        let exec = exec_for(&dir);
        let started = Instant::now();
        let err = exec
            .exec_with_env(
                "p",
                dir.path(),
                Duration::from_secs(3),
                &[],
                None,
                None,
                base_env(&[
                    ("FAKE_CLAUDE_SPAWN_CHILD", "1"),
                    ("FAKE_CLAUDE_CHILD_PID_FILE", pid_file.to_str().unwrap()),
                    ("FAKE_CLAUDE_SLEEP_MS", "120000"),
                    ("FAKE_CLAUDE_CHILD_SLEEP_MS", "120000"),
                ])
                .into_iter(),
            )
            .unwrap_err();
        let elapsed = started.elapsed();
        let pid: u32 = std::fs::read_to_string(&pid_file)
            .expect("grandchild pid file")
            .trim()
            .parse()
            .unwrap();
        std::thread::sleep(Duration::from_millis(300));
        let alive = pid_alive(pid);
        if alive {
            force_kill(pid);
        }
        assert_eq!(err, "claude timeout after PT3S");
        assert!(!alive, "grandchild {pid} survived the timeout");
        assert!(elapsed < Duration::from_secs(15), "took {elapsed:?}");
    }

    #[test]
    fn extras_temp_file_is_deleted_after_exec() {
        let dir = TempDir::new("claude-extras");
        let tmp_dir = dir.path().join("tmp");
        std::fs::create_dir_all(&tmp_dir).unwrap();
        let record = dir.path().join("rec.json");
        let mut exec = exec_for(&dir);
        exec.mcp = exec.mcp.with_temp_dir(&tmp_dir);
        let extras = vec![McpSpec {
            name: "db".into(),
            url: "https://example.com/mcp".into(),
            transport: "http".into(),
            headers: None,
        }];
        let res = exec
            .exec_with_env(
                "p",
                dir.path(),
                Duration::from_secs(30),
                &extras,
                None,
                None,
                base_env(&[("FAKE_CLAUDE_RECORD", record.to_str().unwrap())]).into_iter(),
            )
            .unwrap();
        assert_eq!(res.exit_code, 0);
        let args = strings(&read_record(&record)["args"]);
        let pos = args
            .iter()
            .position(|a| a == "--mcp-config")
            .expect("--mcp-config");
        assert!(
            args[pos + 1].starts_with(tmp_dir.to_str().unwrap()),
            "{args:?}"
        );
        assert_eq!(args.last().unwrap(), "mcp__db");
        assert_eq!(
            std::fs::read_dir(&tmp_dir).unwrap().count(),
            0,
            "temp file left behind"
        );

        // also deleted when the run fails (timeout)
        let err = exec
            .exec_with_env(
                "p",
                dir.path(),
                Duration::from_millis(500),
                &extras,
                None,
                None,
                base_env(&[("FAKE_CLAUDE_SLEEP_MS", "60000")]).into_iter(),
            )
            .unwrap_err();
        assert!(err.starts_with("claude timeout after"), "{err}");
        assert_eq!(
            std::fs::read_dir(&tmp_dir).unwrap().count(),
            0,
            "temp file left after timeout"
        );
    }
}
