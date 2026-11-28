//! Result reporting with retry, dead-letter persistence and replay.
//!
//! Ports `ResultReporter`, `SilentLossTracker` and `DeadLetterReplayJob` of the Java worker.
//! Files (all under the dead-letter directory):
//! - `<workerId>.jsonl`: results that could not be delivered, one JSON object per line
//! - `<workerId>.dead.jsonl`: entries given up on (kept for forensics), rotated to `.dead.jsonl.1`
//!
//! Line format (same keys as Java): `{"ts","taskId","status","permanent","reason","attempts","result"}`
//! where `status` is the Korean display value and `result.status` the enum constant name.
//!
//! Unlike Java, every line this program writes can be read back: `usage` round-trips.

use crate::api::ApiError;
use crate::contract::status_ko;
use crate::dto::ResultRequest;
use crate::log;
use serde::Serialize;
use serde_json::Value;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::Duration;
use time::OffsetDateTime;
use time::format_description::well_known::Rfc3339;

/// Retry gaps between the (up to six) delivery attempts: 2, 4, 8, 16, 32 seconds.
pub const DEFAULT_BACKOFF: [Duration; 5] = [
    Duration::from_secs(2),
    Duration::from_secs(4),
    Duration::from_secs(8),
    Duration::from_secs(16),
    Duration::from_secs(32),
];

/// Dead file size at which it is rotated to `.dead.jsonl.1`.
pub const DEFAULT_MAX_DEAD_BYTES: u64 = 5 * 1024 * 1024;

/// Where results are delivered. `ApiClient` is the real one; tests script their own.
pub trait ResultSink {
    fn post_result(&self, task_id: i64, r: &ResultRequest) -> Result<(), ApiError>;
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Line<'a> {
    ts: String,
    task_id: i64,
    status: &'a str,
    permanent: bool,
    reason: &'a str,
    attempts: u32,
    result: &'a ResultRequest,
}

/// A readable pending line. `raw` is the line exactly as stored (the key for resolve/reject).
struct Entry {
    task_id: i64,
    result: ResultRequest,
    attempts: u32,
    raw: String,
}

pub struct DeadLetter {
    main_path: PathBuf,
    dead_path: PathBuf,
    rolled_dead_path: PathBuf,
    max_dead_bytes: u64,
    /// Serializes all file access (polling thread and admin thread share this).
    lock: Mutex<()>,
}

fn now() -> OffsetDateTime {
    OffsetDateTime::now_local().unwrap_or_else(|_| OffsetDateTime::now_utc())
}

/// Lines of a file; blank lines dropped, trailing `\r` removed, invalid UTF-8 replaced.
fn read_lines(path: &Path) -> io::Result<Vec<String>> {
    match fs::read(path) {
        Ok(bytes) => Ok(String::from_utf8_lossy(&bytes)
            .split('\n')
            .map(|l| l.trim_end_matches('\r'))
            .filter(|l| !l.trim().is_empty())
            .map(String::from)
            .collect()),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(Vec::new()),
        Err(e) => Err(e),
    }
}

/// Replaces `path` with `lines` (one per row) via a temp file and a rename, so a crash leaves
/// either the old or the new content and never a half-written line.
fn write_atomic(path: &Path, lines: &[String]) -> io::Result<()> {
    if let Some(dir) = path.parent() {
        fs::create_dir_all(dir)?;
    }
    let name = path
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let tmp = path.with_file_name(format!("{name}.{}.tmp", std::process::id()));
    let mut content = String::new();
    for l in lines {
        content.push_str(l);
        content.push('\n');
    }
    let result = fs::write(&tmp, content).and_then(|()| fs::rename(&tmp, path));
    if result.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    result
}

fn parse_entry(line: &str) -> Result<Entry, String> {
    let v: Value = serde_json::from_str(line).map_err(|e| e.to_string())?;
    let task_id = v
        .get("taskId")
        .and_then(Value::as_i64)
        .ok_or("taskId missing")?;
    let result = v.get("result").ok_or("result missing")?;
    if !result.is_object() {
        return Err("result is not an object".into());
    }
    let result: ResultRequest =
        serde_json::from_value(result.clone()).map_err(|e| e.to_string())?;
    // A line without `attempts` (older writer) reads as 0.
    let attempts = v
        .get("attempts")
        .and_then(Value::as_u64)
        .map_or(0, |n| u32::try_from(n).unwrap_or(u32::MAX));
    Ok(Entry {
        task_id,
        result,
        attempts,
        raw: line.to_string(),
    })
}

impl DeadLetter {
    pub fn new(dir: &Path, worker_id: &str) -> DeadLetter {
        DeadLetter::with_max_dead_bytes(dir, worker_id, DEFAULT_MAX_DEAD_BYTES)
    }

    pub fn with_max_dead_bytes(dir: &Path, worker_id: &str, max_dead_bytes: u64) -> DeadLetter {
        let dead_path = dir.join(format!("{worker_id}.dead.jsonl"));
        DeadLetter {
            main_path: dir.join(format!("{worker_id}.jsonl")),
            rolled_dead_path: dir.join(format!("{worker_id}.dead.jsonl.1")),
            dead_path,
            max_dead_bytes,
            lock: Mutex::new(()),
        }
    }

    fn guard(&self) -> std::sync::MutexGuard<'_, ()> {
        self.lock.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Entries still waiting for delivery (non-blank lines of the main file).
    pub fn pending_count(&self) -> usize {
        let _g = self.guard();
        read_lines(&self.main_path).map_or(0, |l| l.len())
    }

    /// Entries given up on, both dead-file generations.
    pub fn dead_count(&self) -> usize {
        let _g = self.guard();
        [&self.dead_path, &self.rolled_dead_path]
            .iter()
            .map(|p| read_lines(p).map_or(0, |l| l.len()))
            .sum()
    }

    /// Distinct task ids of the readable pending lines, in order of first appearance.
    pub fn pending_task_ids(&self) -> Vec<i64> {
        let _g = self.guard();
        let mut ids = Vec::new();
        for line in read_lines(&self.main_path).unwrap_or_default() {
            if let Ok(e) = parse_entry(&line)
                && !ids.contains(&e.task_id)
            {
                ids.push(e.task_id);
            }
        }
        ids
    }

    /// Appends an undelivered result. Never panics; an I/O failure is logged.
    pub fn record(&self, task_id: i64, r: &ResultRequest, permanent: bool, reason: &str) {
        let _g = self.guard();
        if let Err(e) = self.record_locked(task_id, r, permanent, reason) {
            log::error(&format!(
                "dead-letter write failed task={task_id} (result is lost): {e}"
            ));
        }
    }

    fn record_locked(
        &self,
        task_id: i64,
        r: &ResultRequest,
        permanent: bool,
        reason: &str,
    ) -> Result<(), String> {
        let ts = now().format(&Rfc3339).map_err(|e| e.to_string())?;
        let line = serde_json::to_string(&Line {
            ts,
            task_id,
            status: status_ko(&r.status).unwrap_or(&r.status),
            permanent,
            reason,
            attempts: 0,
            result: r,
        })
        .map_err(|e| e.to_string())?;
        let mut lines = read_lines(&self.main_path).map_err(|e| e.to_string())?;
        lines.push(line);
        write_atomic(&self.main_path, &lines).map_err(|e| e.to_string())
    }

    /// Appends lines to the dead file, rotating it first when it reached the size limit.
    fn append_dead(&self, new_lines: &[String]) -> io::Result<()> {
        if let Ok(meta) = fs::metadata(&self.dead_path)
            && meta.len() >= self.max_dead_bytes
        {
            fs::rename(&self.dead_path, &self.rolled_dead_path)?;
            log::warn(&format!(
                "dead file rotated: {} reached {} bytes",
                self.dead_path.display(),
                self.max_dead_bytes
            ));
        }
        let mut lines = read_lines(&self.dead_path)?;
        lines.extend_from_slice(new_lines);
        write_atomic(&self.dead_path, &lines)
    }

    /// Readable pending entries; unreadable lines are moved to the dead file (never dropped,
    /// never allowed to block the rest). Any I/O problem yields an empty list for this cycle.
    fn snapshot_pending(&self) -> Vec<Entry> {
        let _g = self.guard();
        let lines = match read_lines(&self.main_path) {
            Ok(l) => l,
            Err(e) => {
                log::error(&format!("dead-letter read failed: {e}"));
                return Vec::new();
            }
        };
        let mut good = Vec::new();
        let mut bad = Vec::new();
        for line in lines {
            match parse_entry(&line) {
                Ok(e) => good.push(e),
                Err(why) => {
                    log::warn(&format!(
                        "dead-letter line unreadable, moving to dead file: {why}"
                    ));
                    bad.push(line);
                }
            }
        }
        if !bad.is_empty() {
            let moved = self.append_dead(&bad).and_then(|()| {
                let kept: Vec<String> = good.iter().map(|e| e.raw.clone()).collect();
                write_atomic(&self.main_path, &kept)
            });
            if let Err(e) = moved {
                log::error(&format!("moving unreadable dead-letter lines failed: {e}"));
            }
        }
        good
    }

    fn resolve(&self, e: &Entry) {
        let _g = self.guard();
        let result = read_lines(&self.main_path).and_then(|mut lines| {
            match lines.iter().position(|l| *l == e.raw) {
                Some(i) => {
                    lines.remove(i);
                    write_atomic(&self.main_path, &lines)
                }
                None => {
                    log::warn(&format!("dead-letter line already gone task={}", e.task_id));
                    Ok(())
                }
            }
        });
        if let Err(err) = result {
            log::error(&format!(
                "dead-letter resolve failed task={} (retried next cycle): {err}",
                e.task_id
            ));
        }
    }

    /// The server refused (4xx): count the attempt; at `max_attempts` move to the dead file.
    fn reject(&self, e: &Entry, max_attempts: u32) {
        let _g = self.guard();
        let result = (|| -> Result<(), String> {
            let mut lines = read_lines(&self.main_path).map_err(|x| x.to_string())?;
            let Some(idx) = lines.iter().position(|l| *l == e.raw) else {
                log::warn(&format!("dead-letter line already gone task={}", e.task_id));
                return Ok(());
            };
            let next = e.attempts.saturating_add(1);
            let mut v: Value = serde_json::from_str(&e.raw).map_err(|x| x.to_string())?;
            v["attempts"] = Value::from(next);
            let updated = serde_json::to_string(&v).map_err(|x| x.to_string())?;
            if next >= max_attempts {
                lines.remove(idx);
                self.append_dead(&[updated]).map_err(|x| x.to_string())?;
                log::warn(&format!(
                    "dead-letter replay exhausted ({next}/{max_attempts}) task={} -> dead file",
                    e.task_id
                ));
            } else {
                lines[idx] = updated;
            }
            write_atomic(&self.main_path, &lines).map_err(|x| x.to_string())
        })();
        if let Err(err) = result {
            log::error(&format!(
                "dead-letter reject failed task={} (retried next cycle): {err}",
                e.task_id
            ));
        }
    }

    /// One replay cycle: one delivery attempt per pending entry.
    /// Success removes the line; a 4xx counts an attempt (dead file at `max_attempts`);
    /// a 5xx or transport error ends the cycle at once and leaves everything in place.
    /// The lock is not held while talking to the network.
    pub fn replay(&self, sink: &dyn ResultSink, max_attempts: u32) {
        let entries = self.snapshot_pending();
        if entries.is_empty() {
            return;
        }
        let total = entries.len();
        let (mut ok, mut rejected, mut deferred) = (0, 0, 0);
        for (i, e) in entries.iter().enumerate() {
            // A line written by an older version may hold unmasked text; masking is idempotent.
            match sink.post_result(e.task_id, &e.result.clone().masked()) {
                Ok(()) => {
                    self.resolve(e);
                    ok += 1;
                    log::info(&format!("dead-letter replay delivered task={}", e.task_id));
                }
                Err(ApiError::Client { .. }) => {
                    self.reject(e, max_attempts);
                    rejected += 1;
                }
                Err(err) => {
                    deferred = total - i;
                    log::warn(&format!(
                        "dead-letter replay cycle stopped (server unreachable, next cycle retries): {err}"
                    ));
                    break;
                }
            }
        }
        log::info(&format!(
            "dead-letter replay cycle done: delivered {ok} / refused {rejected} / deferred {deferred}"
        ));
    }
}

/// Delivers a terminal result: up to `backoff.len() + 1` attempts, waiting `backoff[i]` after
/// failed attempt `i`. A 4xx is permanent (no retry); 5xx and transport errors are retried and,
/// when exhausted, recorded as non-permanent. Masks credentials before anything leaves the
/// process (network or disk). Never panics. Returns whether the result was delivered.
pub fn report_terminal(
    sink: &dyn ResultSink,
    dl: &DeadLetter,
    task_id: i64,
    r: ResultRequest,
    backoff: &[Duration],
) -> bool {
    let req = r.masked();
    let attempts = backoff.len() + 1;
    let mut last = String::new();
    for (attempt, gap) in backoff.iter().map(Some).chain([None]).enumerate() {
        match sink.post_result(task_id, &req) {
            Ok(()) => return true,
            Err(ApiError::Client { status, .. }) => {
                return lost(dl, task_id, &req, true, &format!("4xx {status}"));
            }
            Err(e) => {
                log::warn(&format!(
                    "result report failed (attempt {}/{attempts}) task={task_id}: {e}",
                    attempt + 1
                ));
                last = e.to_string();
                if let Some(gap) = gap {
                    std::thread::sleep(*gap);
                }
            }
        }
    }
    lost(
        dl,
        task_id,
        &req,
        false,
        &format!("재시도 소진({attempts}회) {last}"),
    )
}

fn lost(dl: &DeadLetter, task_id: i64, req: &ResultRequest, permanent: bool, reason: &str) -> bool {
    log::error(&format!(
        "result report finally failed (SILENT_LOSS) task={task_id} status={} permanent={permanent} reason={reason}",
        status_ko(&req.status).unwrap_or(&req.status)
    ));
    dl.record(task_id, req, permanent, reason);
    false
}
