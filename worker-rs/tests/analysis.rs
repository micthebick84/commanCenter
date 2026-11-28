//! `analysis::process` against a local bare repository and the `fake-claude` helper.
#![cfg(feature = "test-bins")]

mod common;

use common::TempDir;
use common::git::Remote;
use netis_worker_rs::analysis::{self, Deps};
use netis_worker_rs::claude::ClaudeExec;
use netis_worker_rs::dto::{ResultRequest, TaskClaim, Usage};
use netis_worker_rs::mcp::McpSupport;
use netis_worker_rs::repo::GitCache;
use serde_json::json;
use std::path::PathBuf;
use std::time::Duration;

const MARKDOWN: &str = "## 1. 요구사항 요약\n요약 내용\n\n## 2. 영향 범위\n- 수정 예상 파일: a.rs\n\n## 3. 접근 방법\n방법\n\n## 4. Subtask 분해\n- **제목**: 첫째 작업\n  - 대상 파일: `a.rs`\n  - 예상 LoC: 약 10줄\n  - 위험도: L\n  - 설명: 설명\n";

struct Fx {
    root: TempDir,
    remote: Remote,
    cache: GitCache,
    claude: ClaudeExec,
}

impl Fx {
    fn new() -> Fx {
        let root = TempDir::new("analysis");
        let cache = GitCache {
            repos_dir: root.path().join("repos"),
            github_pat: None,
            gitlab_token: None,
            lock_wait: Duration::from_secs(10),
            lock_poll: Duration::from_millis(20),
            git_timeout: Duration::from_secs(60),
        };
        let mcp = McpSupport::init(
            &root.path().join("absent.claude.json"),
            &root.path().join("worker-mcp.json"),
        );
        let claude = ClaudeExec {
            cli: env!("CARGO_BIN_EXE_fake-claude").into(),
            mcp,
        };
        Fx {
            root,
            remote: Remote::new(),
            cache,
            claude,
        }
    }

    fn file(&self, name: &str, content: &str) -> PathBuf {
        let p = self.root.path().join(name);
        std::fs::write(&p, content).unwrap();
        p
    }

    fn claim(&self) -> TaskClaim {
        serde_json::from_value(json!({
            "id": 5, "githubRepo": "acme/widgets", "githubBranch": "main",
            "title": "제목 T", "description": "상세 D", "kind": "ANALYSIS",
            "gitUrl": self.remote.url()
        }))
        .unwrap()
    }

    fn run(&self, task: &TaskClaim, env: &[(&str, &str)]) -> ResultRequest {
        self.run_with(task, env, &self.claude)
    }

    fn run_with(
        &self,
        task: &TaskClaim,
        env: &[(&str, &str)],
        claude: &ClaudeExec,
    ) -> ResultRequest {
        let mut parent: Vec<(String, String)> = Vec::new();
        for key in ["SystemRoot", "PATH"] {
            if let Ok(v) = std::env::var(key) {
                parent.push((key.to_string(), v));
            }
        }
        for (k, v) in env {
            parent.push((k.to_string(), v.to_string()));
        }
        let deps = Deps {
            worker_id: "w-test",
            cache: &self.cache,
            claude,
            analysis_timeout: Duration::from_secs(60),
            parent_env: Some(&parent),
        };
        analysis::process(task, &deps)
    }
}

fn envelope(result: &str) -> String {
    json!({
        "type": "result", "result": result, "total_cost_usd": 0.5,
        "usage": {"input_tokens": 10, "output_tokens": 5,
                  "cache_creation_input_tokens": 0, "cache_read_input_tokens": 0}
    })
    .to_string()
}

fn usage() -> Usage {
    Usage {
        cost_usd: 0.5,
        input_tokens: 10,
        output_tokens: 5,
        cache_creation_tokens: 0,
        cache_read_tokens: 0,
    }
}

#[test]
fn success_fills_markdown_subtasks_log_duration_usage() {
    let fx = Fx::new();
    let out = fx.file("out.json", &envelope(MARKDOWN));
    let r = fx.run(
        &fx.claim(),
        &[("FAKE_CLAUDE_STDOUT_FILE", out.to_str().unwrap())],
    );
    assert_eq!(r.status, "COMPLETED", "{r:?}");
    assert_eq!(r.worker_id, "w-test");
    assert_eq!(r.markdown_result.as_deref(), Some(MARKDOWN));
    assert!(r.subtasks_json.as_deref().unwrap().contains("첫째 작업"));
    assert_eq!(r.claude_log.as_deref(), Some(MARKDOWN));
    assert!(r.duration_ms.is_some());
    assert_eq!(r.usage, Some(usage()));
    assert_eq!(r.failure_reason, None);
}

#[test]
fn prompt_substitutes_all_five_placeholders() {
    let fx = Fx::new();
    let out = fx.file("out.json", &envelope(MARKDOWN));
    let rec = fx.root.path().join("rec.json");
    let r = fx.run(
        &fx.claim(),
        &[
            ("FAKE_CLAUDE_STDOUT_FILE", out.to_str().unwrap()),
            ("FAKE_CLAUDE_RECORD", rec.to_str().unwrap()),
        ],
    );
    assert_eq!(r.status, "COMPLETED", "{r:?}");
    let v: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(rec).unwrap()).unwrap();
    let stdin = v["stdin"].as_str().unwrap();
    for needle in [
        "acme/widgets",
        "main",
        fx.remote.head_sha().as_str(),
        "제목 T",
        "상세 D",
    ] {
        assert!(stdin.contains(needle), "missing {needle}");
    }
    for ph in [
        "{github_repo}",
        "{github_branch}",
        "{commit_sha}",
        "{title}",
        "{description}",
    ] {
        assert!(!stdin.contains(ph), "left {ph}");
    }
}

#[test]
fn fetch_failure_reason_prefix() {
    let fx = Fx::new();
    let mut task = fx.claim();
    task.git_url = Some(
        fx.root
            .path()
            .join("nope.git")
            .to_str()
            .unwrap()
            .to_string(),
    );
    let r = fx.run(&task, &[]);
    assert_eq!(r.status, "FAILED");
    assert!(
        r.failure_reason
            .as_deref()
            .unwrap()
            .starts_with("레포 fetch 실패: "),
        "{r:?}"
    );
    assert_eq!(r.usage, None);
}

#[test]
fn missing_repo_or_branch_is_fetch_failure() {
    let fx = Fx::new();
    let mut task = fx.claim();
    task.github_repo = String::new();
    let r = fx.run(&task, &[]);
    assert!(
        r.failure_reason
            .as_deref()
            .unwrap()
            .starts_with("레포 fetch 실패: ")
    );
    let mut task = fx.claim();
    task.github_branch = "  ".into();
    let r = fx.run(&task, &[]);
    assert!(
        r.failure_reason
            .as_deref()
            .unwrap()
            .starts_with("레포 fetch 실패: ")
    );
}

#[test]
fn exec_failure_reason_prefix() {
    let fx = Fx::new();
    let claude = ClaudeExec {
        cli: fx.root.path().join("no-such-claude"),
        mcp: fx.claude.mcp.clone(),
    };
    let r = fx.run_with(&fx.claim(), &[], &claude);
    assert_eq!(r.status, "FAILED");
    assert!(
        r.failure_reason
            .as_deref()
            .unwrap()
            .starts_with("claude exec 실패: "),
        "{r:?}"
    );
    assert_eq!(r.usage, None);
}

#[test]
fn nonzero_exit_reason_has_tail_and_usage() {
    let fx = Fx::new();
    let text = format!("{}😀", "가".repeat(3000));
    let out = fx.file("out.json", &envelope(&text));
    let r = fx.run(
        &fx.claim(),
        &[
            ("FAKE_CLAUDE_STDOUT_FILE", out.to_str().unwrap()),
            ("FAKE_CLAUDE_EXIT", "3"),
        ],
    );
    assert_eq!(r.status, "FAILED");
    let expected = format!("claude exit=3\n…{}😀", "가".repeat(1998));
    assert_eq!(r.failure_reason.as_deref(), Some(expected.as_str()));
    assert_eq!(r.usage, Some(usage()));
    assert_eq!(r.markdown_result, None);
}

#[test]
fn parse_failure_reason_prefix_keeps_usage() {
    let fx = Fx::new();
    let out = fx.file("out.json", &envelope("## 1. 요구사항 요약\n내용만 있음"));
    let r = fx.run(
        &fx.claim(),
        &[("FAKE_CLAUDE_STDOUT_FILE", out.to_str().unwrap())],
    );
    assert_eq!(r.status, "FAILED");
    assert_eq!(
        r.failure_reason.as_deref(),
        Some("파싱 실패: 섹션 ## 2. 누락")
    );
    assert_eq!(r.usage, Some(usage()));
}

#[test]
fn null_title_or_description_does_not_panic() {
    let fx = Fx::new();
    let out = fx.file("out.json", &envelope(MARKDOWN));
    let rec = fx.root.path().join("rec.json");
    let mut task = fx.claim();
    task.title = String::new();
    task.description = None;
    let r = fx.run(
        &task,
        &[
            ("FAKE_CLAUDE_STDOUT_FILE", out.to_str().unwrap()),
            ("FAKE_CLAUDE_RECORD", rec.to_str().unwrap()),
        ],
    );
    assert_eq!(r.status, "COMPLETED", "{r:?}");
    let v: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(rec).unwrap()).unwrap();
    let stdin = v["stdin"].as_str().unwrap();
    assert!(!stdin.contains("null"));
    assert!(!stdin.contains("{title}") && !stdin.contains("{description}"));
}

#[test]
fn panic_becomes_failed_result() {
    let r = analysis::catch_panic("w-test", || panic!("boom {}", 7));
    assert_eq!(r.status, "FAILED");
    assert_eq!(r.worker_id, "w-test");
    assert_eq!(
        r.failure_reason.as_deref(),
        Some("처리 중 예외: panic: boom 7")
    );
}
