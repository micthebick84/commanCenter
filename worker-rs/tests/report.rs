//! `report` tests: reporter retry policy, dead-letter file format and replay. Temp dirs only.

mod common;

use common::TempDir;
use netis_worker_rs::api::ApiError;
use netis_worker_rs::dto::{ResultRequest, Usage};
use netis_worker_rs::report::{DeadLetter, ResultSink, report_terminal};
use serde_json::Value;
use std::collections::VecDeque;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::Duration;

/// Sink scripted with a sequence of results; Ok once the script runs out.
struct ScriptedSink {
    script: Mutex<VecDeque<Result<(), ApiError>>>,
    calls: Mutex<Vec<(i64, ResultRequest)>>,
}

impl ScriptedSink {
    fn new(script: Vec<Result<(), ApiError>>) -> ScriptedSink {
        ScriptedSink {
            script: Mutex::new(script.into()),
            calls: Mutex::new(Vec::new()),
        }
    }
    fn always(f: impl Fn() -> ApiError, n: usize) -> ScriptedSink {
        ScriptedSink::new((0..n).map(|_| Err(f())).collect())
    }
    fn call_count(&self) -> usize {
        self.calls.lock().unwrap().len()
    }
}

impl ResultSink for ScriptedSink {
    fn post_result(&self, task_id: i64, r: &ResultRequest) -> Result<(), ApiError> {
        self.calls.lock().unwrap().push((task_id, r.clone()));
        self.script.lock().unwrap().pop_front().unwrap_or(Ok(()))
    }
}

fn server_err() -> ApiError {
    ApiError::Server {
        status: 503,
        body: "down".into(),
    }
}
fn client_err() -> ApiError {
    ApiError::Client {
        status: 409,
        body: "stale".into(),
    }
}

fn fast() -> Vec<Duration> {
    vec![Duration::from_millis(1); 5]
}

fn result(status: &str) -> ResultRequest {
    ResultRequest {
        worker_id: "w1".into(),
        status: status.into(),
        markdown_result: Some("## 결과\n줄1\r\n줄2 \"따옴표\" \\ 끝\n".into()),
        subtasks_json: Some("[]".into()),
        claude_log: None,
        duration_ms: Some(10),
        failure_reason: None,
        usage: None,
    }
}

fn with_usage(mut r: ResultRequest) -> ResultRequest {
    r.usage = Some(Usage {
        cost_usd: 0.5,
        input_tokens: 1,
        output_tokens: 2,
        cache_creation_tokens: 3,
        cache_read_tokens: 4,
    });
    r
}

struct Env {
    _tmp: TempDir,
    dir: PathBuf,
}
impl Env {
    fn new() -> Env {
        let tmp = TempDir::new("report");
        let dir = tmp.path().join("dead-letter");
        Env { _tmp: tmp, dir }
    }
    fn dl(&self) -> DeadLetter {
        DeadLetter::new(&self.dir, "w1")
    }
    fn main_path(&self) -> PathBuf {
        self.dir.join("w1.jsonl")
    }
    fn dead_path(&self) -> PathBuf {
        self.dir.join("w1.dead.jsonl")
    }
    fn lines(&self, p: PathBuf) -> Vec<String> {
        std::fs::read_to_string(p)
            .map(|s| s.lines().map(String::from).collect())
            .unwrap_or_default()
    }
}

#[test]
fn success_first_try_writes_nothing() {
    let env = Env::new();
    let dl = env.dl();
    let sink = ScriptedSink::new(vec![Ok(())]);
    assert!(report_terminal(&sink, &dl, 1, result("COMPLETED"), &fast()));
    assert_eq!(sink.call_count(), 1);
    assert!(!env.main_path().exists());
    assert_eq!(dl.pending_count(), 0);
}

#[test]
fn retries_5xx_with_backoff_then_records_non_permanent() {
    let env = Env::new();
    let dl = env.dl();
    let sink = ScriptedSink::always(server_err, 100);
    assert!(!report_terminal(
        &sink,
        &dl,
        7,
        result("COMPLETED"),
        &fast()
    ));
    assert_eq!(sink.call_count(), 6);
    let lines = env.lines(env.main_path());
    assert_eq!(lines.len(), 1);
    let v: Value = serde_json::from_str(&lines[0]).unwrap();
    assert_eq!(v["permanent"], false);
    assert_eq!(v["taskId"], 7);
    assert_eq!(v["attempts"], 0);
}

#[test]
fn backoff_delays_are_actually_waited() {
    let env = Env::new();
    let sink = ScriptedSink::always(server_err, 100);
    let start = std::time::Instant::now();
    report_terminal(
        &sink,
        &env.dl(),
        1,
        result("COMPLETED"),
        &[Duration::from_millis(40), Duration::from_millis(40)],
    );
    assert_eq!(sink.call_count(), 3);
    assert!(start.elapsed() >= Duration::from_millis(80));
}

#[test]
fn transient_failure_then_success_writes_nothing() {
    let env = Env::new();
    let sink = ScriptedSink::new(vec![
        Err(server_err()),
        Err(ApiError::Transport("reset".into())),
        Ok(()),
    ]);
    assert!(report_terminal(
        &sink,
        &env.dl(),
        1,
        result("COMPLETED"),
        &fast()
    ));
    assert_eq!(sink.call_count(), 3);
    assert!(!env.main_path().exists());
}

#[test]
fn client_error_records_immediately_as_permanent() {
    let env = Env::new();
    let sink = ScriptedSink::always(client_err, 100);
    assert!(!report_terminal(
        &sink,
        &env.dl(),
        3,
        result("FAILED"),
        &fast()
    ));
    assert_eq!(sink.call_count(), 1);
    let lines = env.lines(env.main_path());
    assert_eq!(lines.len(), 1);
    let v: Value = serde_json::from_str(&lines[0]).unwrap();
    assert_eq!(v["permanent"], true);
}

#[test]
fn line_format_matches_java() {
    let env = Env::new();
    let sink = ScriptedSink::always(client_err, 1);
    report_terminal(&sink, &env.dl(), 12, result("COMPLETED"), &fast());
    let lines = env.lines(env.main_path());
    let v: Value = serde_json::from_str(&lines[0]).unwrap();
    let mut keys: Vec<&str> = v.as_object().unwrap().keys().map(String::as_str).collect();
    keys.sort_unstable();
    let mut want = vec![
        "ts",
        "taskId",
        "status",
        "permanent",
        "reason",
        "attempts",
        "result",
    ];
    want.sort_unstable();
    assert_eq!(keys, want);
    assert_eq!(v["status"], "분석완료");
    assert_eq!(v["result"]["status"], "COMPLETED");
    assert_eq!(v["result"]["workerId"], "w1");
    assert!(v["ts"].as_str().unwrap().contains('T'));
    assert!(v["reason"].as_str().unwrap().contains("409"));
}

#[test]
fn masked_payload_is_sent_and_stored() {
    let env = Env::new();
    let mut r = result("FAILED");
    r.failure_reason = Some("clone https://oauth2:tok999@host/x.git failed".into());
    let sink = ScriptedSink::always(client_err, 1);
    report_terminal(&sink, &env.dl(), 1, r, &fast());
    let sent = sink.calls.lock().unwrap()[0].1.clone();
    assert!(!sent.failure_reason.as_ref().unwrap().contains("tok999"));
    let text = std::fs::read_to_string(env.main_path()).unwrap();
    assert!(!text.contains("tok999"), "{text}");
}

#[test]
fn line_with_usage_korean_and_newlines_roundtrips() {
    let env = Env::new();
    let dl = env.dl();
    let r = with_usage(result("COMPLETED"));
    dl.record(9, &r, false, "재시도 소진(6회) 503 \"x\"\n둘째 줄");
    assert_eq!(env.lines(env.main_path()).len(), 1, "one physical line");
    assert_eq!(dl.pending_count(), 1);
    assert_eq!(dl.pending_task_ids(), vec![9]);
    let sink = ScriptedSink::new(vec![]);
    dl.replay(&sink, 3);
    let calls = sink.calls.lock().unwrap();
    assert_eq!(calls.len(), 1);
    assert_eq!(calls[0].0, 9);
    assert_eq!(calls[0].1, r.clone().masked());
    assert!(calls[0].1.usage.is_some());
}

#[test]
fn replay_success_removes_line() {
    let env = Env::new();
    let dl = env.dl();
    dl.record(1, &result("COMPLETED"), false, "x");
    dl.record(2, &result("FAILED"), false, "y");
    let sink = ScriptedSink::new(vec![Ok(()), Ok(())]);
    dl.replay(&sink, 3);
    assert_eq!(sink.call_count(), 2);
    assert_eq!(dl.pending_count(), 0);
    assert_eq!(dl.dead_count(), 0);
    assert!(env.lines(env.main_path()).is_empty());
}

#[test]
fn replay_stops_cycle_on_5xx_and_keeps_lines() {
    let env = Env::new();
    let dl = env.dl();
    for id in 1..=3 {
        dl.record(id, &result("COMPLETED"), false, "x");
    }
    let sink = ScriptedSink::new(vec![Ok(()), Err(server_err())]);
    dl.replay(&sink, 3);
    assert_eq!(sink.call_count(), 2, "third entry is not attempted");
    assert_eq!(dl.pending_task_ids(), vec![2, 3]);
    for l in env.lines(env.main_path()) {
        let v: Value = serde_json::from_str(&l).unwrap();
        assert_eq!(v["attempts"], 0, "5xx does not count as an attempt");
    }
}

#[test]
fn replay_stops_cycle_on_transport_error() {
    let env = Env::new();
    let dl = env.dl();
    dl.record(1, &result("COMPLETED"), false, "x");
    dl.record(2, &result("COMPLETED"), false, "x");
    let sink = ScriptedSink::new(vec![Err(ApiError::Transport("refused".into()))]);
    dl.replay(&sink, 3);
    assert_eq!(sink.call_count(), 1);
    assert_eq!(dl.pending_count(), 2);
}

#[test]
fn replay_4xx_three_times_moves_to_dead_file() {
    let env = Env::new();
    let dl = env.dl();
    dl.record(5, &result("COMPLETED"), false, "x");
    let sink = ScriptedSink::always(client_err, 10);
    dl.replay(&sink, 3);
    assert_eq!(dl.pending_count(), 1);
    assert_eq!(dl.dead_count(), 0);
    let v: Value = serde_json::from_str(&env.lines(env.main_path())[0]).unwrap();
    assert_eq!(v["attempts"], 1);
    dl.replay(&sink, 3);
    dl.replay(&sink, 3);
    assert_eq!(sink.call_count(), 3, "one attempt per entry per cycle");
    assert_eq!(dl.pending_count(), 0);
    assert_eq!(dl.dead_count(), 1);
    let dead = env.lines(env.dead_path());
    assert_eq!(dead.len(), 1);
    let v: Value = serde_json::from_str(&dead[0]).unwrap();
    assert_eq!(v["attempts"], 3);
    assert_eq!(v["taskId"], 5);
}

#[test]
fn dead_file_rotates_at_limit() {
    let env = Env::new();
    let dl = DeadLetter::with_max_dead_bytes(&env.dir, "w1", 10);
    let sink = ScriptedSink::always(client_err, 10);
    dl.record(1, &result("COMPLETED"), true, "a");
    dl.replay(&sink, 1);
    assert!(env.dead_path().exists());
    assert!(!env.dir.join("w1.dead.jsonl.1").exists());
    dl.record(2, &result("COMPLETED"), true, "b");
    dl.replay(&sink, 1);
    let rolled = env.lines(env.dir.join("w1.dead.jsonl.1"));
    assert_eq!(rolled.len(), 1);
    assert!(rolled[0].contains("\"taskId\":1"));
    let current = env.lines(env.dead_path());
    assert_eq!(current.len(), 1);
    assert!(current[0].contains("\"taskId\":2"));
    assert_eq!(dl.dead_count(), 2, "both generations are counted");
}

#[test]
fn counts_reflect_files() {
    let env = Env::new();
    let dl = env.dl();
    assert_eq!((dl.pending_count(), dl.dead_count()), (0, 0));
    dl.record(1, &result("COMPLETED"), false, "x");
    dl.record(2, &result("COMPLETED"), false, "x");
    assert_eq!(dl.pending_count(), 2);
    std::fs::write(env.dead_path(), "{}\n{}\n\n").unwrap();
    assert_eq!(dl.dead_count(), 2);
    std::fs::remove_file(env.dead_path()).unwrap();
    assert_eq!(dl.dead_count(), 0);
    // a fresh instance (restart) sees the same files
    assert_eq!(env.dl().pending_count(), 2);
}

#[test]
fn pending_task_ids_are_distinct() {
    let env = Env::new();
    let dl = env.dl();
    dl.record(4, &result("COMPLETED"), false, "x");
    dl.record(9, &result("FAILED"), false, "x");
    dl.record(4, &result("FAILED"), false, "x");
    assert_eq!(dl.pending_task_ids(), vec![4, 9]);
    assert_eq!(dl.pending_count(), 3);
}

#[test]
fn java_written_line_without_attempts_reads_as_zero() {
    let env = Env::new();
    std::fs::create_dir_all(&env.dir).unwrap();
    let line = r#"{"ts":"2026-11-01T10:00:00+09:00","taskId":5,"status":"분석완료","permanent":false,"reason":"x","result":{"workerId":"w1","status":"COMPLETED","durationMs":3}}"#;
    std::fs::write(env.main_path(), format!("{line}\n")).unwrap();
    let dl = env.dl();
    assert_eq!(dl.pending_task_ids(), vec![5]);
    let sink = ScriptedSink::always(client_err, 5);
    dl.replay(&sink, 3);
    let v: Value = serde_json::from_str(&env.lines(env.main_path())[0]).unwrap();
    assert_eq!(v["attempts"], 1);
    dl.replay(&sink, 3);
    dl.replay(&sink, 3);
    assert_eq!(dl.dead_count(), 1);
}

#[test]
fn java_written_line_with_nulls_and_crlf_is_readable() {
    let env = Env::new();
    std::fs::create_dir_all(&env.dir).unwrap();
    let line = r#"{"ts":"t","taskId":6,"status":"실패","permanent":true,"reason":"x","attempts":2,"result":{"workerId":"w1","status":"FAILED","markdownResult":null,"usage":null,"failureReason":"z"}}"#;
    std::fs::write(env.main_path(), format!("{line}\r\n")).unwrap();
    let dl = env.dl();
    assert_eq!(dl.pending_task_ids(), vec![6]);
    let sink = ScriptedSink::new(vec![]);
    dl.replay(&sink, 3);
    assert_eq!(sink.call_count(), 1);
    assert_eq!(dl.pending_count(), 0);
}

#[test]
fn unreadable_line_goes_to_dead_not_lost() {
    let env = Env::new();
    let dl = env.dl();
    dl.record(1, &result("COMPLETED"), false, "x");
    let mut text = std::fs::read_to_string(env.main_path()).unwrap();
    text.push_str("this is {not json\n");
    text.push_str("{\"taskId\":3,\"result\":{\"nope\":1}}\n");
    std::fs::write(env.main_path(), text).unwrap();
    let sink = ScriptedSink::new(vec![]);
    dl.replay(&sink, 3);
    assert_eq!(sink.call_count(), 1, "only the readable line is sent");
    assert_eq!(dl.pending_count(), 0);
    let dead = env.lines(env.dead_path());
    assert_eq!(dead.len(), 2);
    assert!(dead[0].contains("this is {not json"));
    assert_eq!(dl.dead_count(), 2);
}

#[test]
fn no_temp_files_are_left_behind() {
    let env = Env::new();
    let dl = env.dl();
    dl.record(1, &result("COMPLETED"), false, "x");
    dl.replay(&ScriptedSink::new(vec![]), 3);
    let names: Vec<String> = std::fs::read_dir(&env.dir)
        .unwrap()
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .collect();
    assert!(names.iter().all(|n| !n.ends_with(".tmp")), "{names:?}");
}

#[test]
fn concurrent_records_do_not_lose_lines() {
    let env = Env::new();
    let dl = std::sync::Arc::new(env.dl());
    let handles: Vec<_> = (0..4)
        .map(|t| {
            let dl = dl.clone();
            std::thread::spawn(move || {
                for i in 0..10 {
                    dl.record(t * 100 + i, &result("COMPLETED"), false, "x");
                }
            })
        })
        .collect();
    for h in handles {
        h.join().unwrap();
    }
    assert_eq!(dl.pending_count(), 40);
}

#[test]
fn replay_with_nothing_pending_is_quiet() {
    let env = Env::new();
    let sink = ScriptedSink::new(vec![]);
    env.dl().replay(&sink, 3);
    assert_eq!(sink.call_count(), 0);
    assert!(!env.main_path().exists());
}
