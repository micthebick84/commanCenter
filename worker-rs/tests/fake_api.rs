//! `runner::run` against an in-process fake API (127.0.0.1, OS-assigned port) and `fake-claude`.
//! Conditions are awaited with a bounded deadline; the runner is always stopped and joined.
#![cfg(feature = "test-bins")]

mod common;

use common::TempDir;
use common::git::Remote;
use common::http::{FakeServer, Recorded};
use netis_worker_rs::config::Config;
use netis_worker_rs::dto::ResultRequest;
use netis_worker_rs::report::DeadLetter;
use netis_worker_rs::runner::{self, RunOptions, Timings};
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

const WORKER_ID: &str = "w-test";
const MARKDOWN: &str = "## 1. 요구사항 요약\n요약\n\n## 2. 영향 범위\n- a\n\n## 3. 접근 방법\n방법\n\n## 4. Subtask 분해\n- **제목**: 첫째\n  - 대상 파일: `a.rs`\n  - 예상 LoC: 약 1줄\n  - 위험도: L\n  - 설명: x\n";

fn timings() -> Timings {
    Timings {
        poll: Duration::from_millis(20),
        heartbeat: Duration::from_millis(50),
        replay: Duration::from_millis(50),
        auth_check: Duration::from_secs(3600),
        backoff: vec![Duration::from_millis(1), Duration::from_millis(1)],
        analysis_timeout: Duration::from_secs(60),
    }
}

/// Stops and joins the runner thread on drop, so an assertion failure cannot leak it.
struct RunGuard {
    stop: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}

impl Drop for RunGuard {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

struct Harness {
    // field order = drop order: the runner stops before the server it talks to
    _runner: RunGuard,
    server: FakeServer,
    root: TempDir,
    _remote: Remote,
}

type Prepare = Box<dyn Fn(&TempDir)>;

struct Setup {
    timings: Timings,
    env: Vec<(&'static str, String)>,
    claude_json: Option<Value>,
    prepare: Option<Prepare>,
}

impl Setup {
    fn new() -> Setup {
        Setup {
            timings: timings(),
            env: Vec::new(),
            claude_json: None,
            prepare: None,
        }
    }
}

fn claim_json(remote_url: &str, kind: &str) -> String {
    json!({
        "id": 1, "githubRepo": "acme/widgets", "githubBranch": "main",
        "title": "제목", "description": "상세", "kind": kind,
        "gitUrl": remote_url
    })
    .to_string()
}

fn start(
    remote: Remote,
    setup: Setup,
    handler: impl Fn(&Recorded, &str) -> (u16, String) + Send + Sync + 'static,
) -> Harness {
    let root = TempDir::new("fake-api");
    let home = root.path().join("home");
    std::fs::create_dir_all(&home).unwrap();
    if let Some(cj) = &setup.claude_json {
        std::fs::write(home.join(".claude.json"), cj.to_string()).unwrap();
    }
    if let Some(prepare) = &setup.prepare {
        prepare(&root);
    }
    let remote_url = remote.url();
    let server = FakeServer::start(move |r| handler(r, &remote_url));
    let config = Config {
        worker_id: WORKER_ID.to_string(),
        api_base_url: server.base_url.clone(),
        api_key: "k".to_string(),
        kinds: vec!["ANALYSIS".to_string()],
        version: "t1".to_string(),
        repos_dir: root.path().join("repos"),
        dead_letter_dir: root.path().join("dead"),
        claude_cli: env!("CARGO_BIN_EXE_fake-claude").into(),
        github_pat: None,
        gitlab_token: None,
        auth_check_interval: Duration::from_secs(3600),
        home,
    };
    let mut env: HashMap<String, String> = HashMap::new();
    for key in ["SystemRoot", "PATH"] {
        if let Ok(v) = std::env::var(key) {
            env.insert(key.to_string(), v);
        }
    }
    env.insert("FAKE_CLAUDE_STDOUT_FILE".to_string(), envelope_file(&root));
    for (k, v) in &setup.env {
        env.insert(k.to_string(), v.clone());
    }
    let stop = Arc::new(AtomicBool::new(false));
    let stop2 = stop.clone();
    let timings = setup.timings;
    let handle = std::thread::spawn(move || {
        runner::run_with(config, timings, stop2, RunOptions { env });
    });
    Harness {
        _runner: RunGuard {
            stop,
            handle: Some(handle),
        },
        server,
        root,
        _remote: remote,
    }
}

impl Harness {
    fn requests(&self) -> Vec<Recorded> {
        self.server.requests()
    }

    fn count(&self, needle: &str) -> usize {
        self.requests()
            .iter()
            .filter(|r| r.url.contains(needle))
            .count()
    }

    fn wait(&self, what: &str, cond: impl Fn(&[Recorded]) -> bool) {
        let deadline = Instant::now() + Duration::from_secs(30);
        loop {
            let reqs = self.requests();
            if cond(&reqs) {
                return;
            }
            if Instant::now() > deadline {
                let urls: Vec<String> = reqs.iter().map(|r| r.url.clone()).collect();
                panic!("timed out waiting for {what}; requests so far: {urls:?}");
            }
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    fn dead_dir(&self) -> std::path::PathBuf {
        self.root.path().join("dead")
    }
}

fn envelope_file(root: &TempDir) -> String {
    let p = root.path().join("claude-out.json");
    let body = json!({
        "type": "result", "result": MARKDOWN, "total_cost_usd": 0.1,
        "usage": {"input_tokens": 1, "output_tokens": 1,
                  "cache_creation_input_tokens": 0, "cache_read_input_tokens": 0}
    });
    std::fs::write(&p, body.to_string()).unwrap();
    p.to_str().unwrap().to_string()
}

fn is_next_task(r: &Recorded) -> bool {
    r.url.starts_with("/worker/next-task")
}

fn is_result(r: &Recorded) -> bool {
    r.url.starts_with("/worker/tasks/") && r.url.ends_with("/result")
}

fn body(r: &Recorded) -> Value {
    serde_json::from_str(&r.body).unwrap_or(Value::Null)
}

/// Serves the claim once, then 204.
fn claim_once(kind: &'static str) -> impl Fn(&Recorded, &str) -> (u16, String) + Send + Sync {
    let served = AtomicUsize::new(0);
    move |r, remote| {
        if r.url.starts_with("/worker/startup") {
            return (200, "{}".into());
        }
        if is_next_task(r) {
            if served.fetch_add(1, Ordering::SeqCst) == 0 {
                return (200, claim_json(remote, kind));
            }
            return (204, String::new());
        }
        (200, "{}".into())
    }
}

#[test]
fn startup_is_retried_until_ok_and_no_claim_before() {
    let startups = AtomicUsize::new(0);
    let h = start(Remote::new(), Setup::new(), move |r, _| {
        if r.url.starts_with("/worker/startup") {
            return if startups.fetch_add(1, Ordering::SeqCst) < 2 {
                (500, "boom".into())
            } else {
                (200, "{}".into())
            };
        }
        if is_next_task(r) {
            return (204, String::new());
        }
        (200, "{}".into())
    });
    h.wait("a next-task poll", |reqs| reqs.iter().any(is_next_task));
    let reqs = h.requests();
    let first_poll = reqs.iter().position(is_next_task).unwrap();
    let before: Vec<&str> = reqs[..first_poll]
        .iter()
        .filter(|r| r.url.starts_with("/worker/startup"))
        .map(|r| r.url.as_str())
        .collect();
    assert_eq!(before.len(), 3, "2 failures then the successful one");
    assert!(before[0].contains("workerId=w-test"));
    drop(reqs);
    h.wait("more polls", |reqs| {
        reqs.iter().filter(|r| is_next_task(r)).count() >= 3
    });
    assert_eq!(h.count("/worker/startup"), 3, "no startup after success");
    let polls = h.requests();
    let poll = polls.iter().find(|r| is_next_task(r)).unwrap();
    assert!(poll.url.contains("kinds=ANALYSIS"), "{}", poll.url);
}

#[test]
fn heartbeat_carries_counts_mcps_and_auth() {
    let mut setup = Setup::new();
    setup.timings.replay = Duration::from_secs(3600);
    setup.env = vec![("CLAUDE_CODE_OAUTH_TOKEN", "test-token".to_string())];
    setup.claude_json = Some(json!({
        "mcpServers": {"alpha": {"type": "http", "url": "https://example.test/a"}}
    }));
    setup.prepare = Some(Box::new(|root| {
        let dir = root.path().join("dead");
        let dl = DeadLetter::new(&dir, WORKER_ID);
        let req = ResultRequest {
            worker_id: WORKER_ID.into(),
            status: "FAILED".into(),
            markdown_result: None,
            subtasks_json: None,
            claude_log: None,
            duration_ms: None,
            failure_reason: Some("x".into()),
            usage: None,
        };
        dl.record(11, &req, false, "test");
        dl.record(12, &req, false, "test");
        std::fs::write(dir.join(format!("{WORKER_ID}.dead.jsonl")), "{}\n").unwrap();
    }));
    let h = start(Remote::new(), setup, |r, _| {
        if is_next_task(r) {
            return (204, String::new());
        }
        (200, "{}".into())
    });
    h.wait("a heartbeat with the auth state", |reqs| {
        reqs.iter()
            .any(|r| r.url == "/worker/heartbeat" && body(r)["claudeAuthMethod"] == "ENV_TOKEN")
    });
    let reqs = h.requests();
    let hb = body(
        reqs.iter()
            .find(|r| r.url == "/worker/heartbeat" && body(r)["claudeAuthMethod"] == "ENV_TOKEN")
            .unwrap(),
    );
    assert_eq!(hb["workerId"], WORKER_ID);
    assert_eq!(hb["version"], "t1");
    assert_eq!(hb["lostReportCount"], 2);
    assert_eq!(hb["deadReportCount"], 1);
    assert_eq!(hb["mcps"], json!(["alpha"]));
    assert_eq!(hb["claudeSessionOk"], true);
    assert!(hb.get("vpnStatus").is_none());
    let hostname = hb["hostname"].as_str().unwrap();
    assert!(!hostname.is_empty() && hostname.encode_utf16().count() <= 100);
    let startup = reqs
        .iter()
        .find(|r| r.url.starts_with("/worker/startup"))
        .unwrap();
    assert_eq!(body(startup)["pendingTaskIds"], json!([11, 12]));
}

#[test]
fn claims_processes_and_reports_completed() {
    let h = start(Remote::new(), Setup::new(), claim_once("ANALYSIS"));
    h.wait("the result report", |reqs| reqs.iter().any(is_result));
    let reqs = h.requests();
    let res = reqs.iter().find(|r| is_result(r)).unwrap();
    assert_eq!(res.url, "/worker/tasks/1/result");
    let b = body(res);
    assert_eq!(b["status"], "COMPLETED");
    assert_eq!(b["workerId"], WORKER_ID);
    assert!(!b["markdownResult"].as_str().unwrap().is_empty());
    assert_eq!(res.header("X-Worker-API-Key"), Some("k"));
}

#[test]
fn non_analysis_claim_is_not_processed() {
    let h = start(Remote::new(), Setup::new(), claim_once("DEPLOY"));
    h.wait("polls after the foreign claim", |reqs| {
        let first = reqs.iter().position(is_next_task);
        match first {
            Some(i) => reqs[i..].iter().filter(|r| is_next_task(r)).count() >= 4,
            None => false,
        }
    });
    assert_eq!(h.requests().iter().filter(|r| is_result(r)).count(), 0);
}

#[test]
fn result_5xx_is_dead_lettered_then_replayed() {
    let fail = Arc::new(AtomicBool::new(true));
    let ok_results = Arc::new(AtomicUsize::new(0));
    let (fail2, ok2) = (fail.clone(), ok_results.clone());
    let inner = claim_once("ANALYSIS");
    let handler = move |r: &Recorded, remote: &str| {
        if is_result(r) {
            if fail2.load(Ordering::SeqCst) {
                return (500, "down".into());
            }
            ok2.fetch_add(1, Ordering::SeqCst);
            return (200, "{}".into());
        }
        inner(r, remote)
    };
    let h = start(Remote::new(), Setup::new(), handler);
    let dl = DeadLetter::new(&h.dead_dir(), WORKER_ID);
    h.wait("the report to land in the dead-letter file", |_| {
        dl.pending_count() == 1
    });
    assert!(h.count("/result") >= 3, "retried before giving up");
    fail.store(false, Ordering::SeqCst);
    h.wait("the replayed report to be accepted", |_| {
        ok_results.load(Ordering::SeqCst) >= 1
    });
    h.wait("the dead-letter file to empty", |_| dl.pending_count() == 0);
}

#[test]
fn no_polling_while_processing() {
    let mut setup = Setup::new();
    let rec_dir = Arc::new(TempDir::new("rec"));
    let record = rec_dir.path().join("rec.json");
    setup.env = vec![
        ("FAKE_CLAUDE_SLEEP_MS", "1500".to_string()),
        ("FAKE_CLAUDE_RECORD", record.to_str().unwrap().to_string()),
    ];
    let h = start(Remote::new(), setup, claim_once("ANALYSIS"));
    let started = Instant::now();
    while !record.exists() {
        assert!(
            started.elapsed() < Duration::from_secs(30),
            "claude never started"
        );
        std::thread::sleep(Duration::from_millis(5));
    }
    let polls_when_claude_started = h.requests().iter().filter(|r| is_next_task(r)).count();
    h.wait("the result report", |reqs| reqs.iter().any(is_result));
    let reqs = h.requests();
    let result_at = reqs.iter().position(is_result).unwrap();
    let polls_before_result = reqs[..result_at].iter().filter(|r| is_next_task(r)).count();
    assert_eq!(
        polls_before_result, polls_when_claude_started,
        "next-task was polled while the task was being processed"
    );
    drop(rec_dir);
}
