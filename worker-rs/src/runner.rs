//! The run loop: three threads (polling, heartbeat, admin) around one shared API client,
//! dead-letter store and login state (Java `WorkerMainLoop` heartbeat / startup / poll).
//!
//! A panic in a thread's own loop code ends the whole process (`worker-rs fatal: ...`,
//! exit code 1) so a half-dead daemon never keeps heartbeating while it no longer polls.
//! A claim of a kind this worker does not implement is fatal too (fail-stop): [`run_with`]
//! stops every thread and returns the reason, and `main` ends the process with exit code 1 -
//! the server's dead-worker recovery then requeues what was captured.

use crate::analysis::{self, Deps};
use crate::api::ApiClient;
use crate::auth_probe::{self, AuthState};
use crate::claude::ClaudeExec;
use crate::config::Config;
use crate::contract::IMPLEMENTED_KINDS;
use crate::dto::Heartbeat;
use crate::log;
use crate::mcp::McpSupport;
use crate::proc;
use crate::repo::GitCache;
use crate::report::{self, DEFAULT_BACKOFF, DeadLetter};
use std::collections::HashMap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::PathBuf;
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const REPLAY_MAX_ATTEMPTS: u32 = 3;
const HOSTNAME_MAX_UNITS: usize = 100;
/// How often a sleeping thread looks at the stop flag.
const STOP_CHECK: Duration = Duration::from_millis(50);

#[derive(Debug, Clone)]
pub struct Timings {
    pub poll: Duration,
    pub heartbeat: Duration,
    pub replay: Duration,
    pub auth_check: Duration,
    pub backoff: Vec<Duration>,
    pub analysis_timeout: Duration,
}

impl Default for Timings {
    fn default() -> Timings {
        Timings {
            poll: Duration::from_secs(5),
            heartbeat: Duration::from_secs(10),
            replay: Duration::from_secs(60),
            auth_check: Duration::from_secs(300),
            backoff: DEFAULT_BACKOFF.to_vec(),
            analysis_timeout: Duration::from_secs(600),
        }
    }
}

/// Pieces tests replace so they never touch the real environment.
/// No `Default`: an empty environment would start claude without `PATH`/`SystemRoot`.
#[derive(Clone)]
pub struct RunOptions {
    /// The environment the claude child starts from and the login probe decides on.
    pub env: HashMap<String, String>,
}

/// Hand-written: the environment holds every secret of the process. Only the count is printed.
impl std::fmt::Debug for RunOptions {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RunOptions")
            .field("env_vars", &self.env.len())
            .finish()
    }
}

impl RunOptions {
    /// This process's environment (non-Unicode values are converted lossily).
    pub fn from_process() -> RunOptions {
        RunOptions {
            env: std::env::vars_os()
                .map(|(k, v)| {
                    (
                        k.to_string_lossy().into_owned(),
                        v.to_string_lossy().into_owned(),
                    )
                })
                .collect(),
        }
    }
}

/// Cuts `s` to at most `max_units` UTF-16 code units on a character boundary.
pub fn clip_utf16(s: &str, max_units: usize) -> String {
    let mut units = 0;
    let mut out = String::new();
    for ch in s.chars() {
        units += ch.len_utf16();
        if units > max_units {
            break;
        }
        out.push(ch);
    }
    out
}

/// The heartbeat's host name: trimmed, cut to 100 UTF-16 units, `unknown` when absent.
pub fn hostname_from(raw: Option<&str>) -> String {
    match raw.map(str::trim).filter(|h| !h.is_empty()) {
        Some(h) => clip_utf16(h, HOSTNAME_MAX_UNITS),
        None => "unknown".to_string(),
    }
}

fn os_hostname() -> String {
    let from_env = ["COMPUTERNAME", "HOSTNAME"]
        .iter()
        .find_map(|k| std::env::var(k).ok().filter(|v| !v.trim().is_empty()));
    let raw = from_env.or_else(|| {
        let out = proc::run(&mut Command::new("hostname"), None, Duration::from_secs(5)).ok()?;
        (out.exit_code == 0 && !out.timed_out).then_some(out.stdout)
    });
    hostname_from(raw.as_deref())
}

fn sleep_unless_stopped(stop: &AtomicBool, total: Duration) {
    let end = Instant::now() + total;
    while !stop.load(Ordering::Relaxed) {
        let now = Instant::now();
        if now >= end {
            return;
        }
        std::thread::sleep((end - now).min(STOP_CHECK));
    }
}

/// Runs a thread body; a panic in it ends the process instead of leaving a half-dead daemon.
/// (`panic_message` masks credentials in the message.)
fn guarded<T>(name: &str, f: impl FnOnce() -> T) -> T {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(v) => v,
        Err(payload) => {
            log::error(&format!(
                "worker-rs fatal: {name} thread panicked: {}",
                analysis::panic_message(payload.as_ref())
            ));
            std::process::exit(1);
        }
    }
}

fn mcp_out_file(config: &Config) -> PathBuf {
    let dir = config
        .repos_dir
        .parent()
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."));
    dir.join(format!("worker-mcp-{}.json", config.worker_id))
}

/// Production entry point. `Err` = a fatal condition (the reason, without the `worker-rs fatal:`
/// prefix), returned after every thread has stopped; `main` logs it and exits with code 1.
/// `Ok` only when `stop` was set from outside (tests); `main` never sets it.
pub fn run(config: Config, timings: Timings, stop: Arc<AtomicBool>) -> Result<(), String> {
    run_with(config, timings, stop, RunOptions::from_process())
}

/// [`run`] with injected options. On a fatal condition the stop flag is set, all three threads
/// are joined, and the reason is returned.
pub fn run_with(
    config: Config,
    timings: Timings,
    stop: Arc<AtomicBool>,
    opts: RunOptions,
) -> Result<(), String> {
    let mcp = McpSupport::init(&config.home.join(".claude.json"), &mcp_out_file(&config));
    log::info(&format!(
        "worker-rs started: id={} kinds={}",
        config.worker_id,
        config.kinds.join(",")
    ));

    let api = ApiClient::new(
        &config.api_base_url,
        &config.api_key,
        &config.worker_id,
        &config.kinds,
    );
    let dead_letter = DeadLetter::new(&config.dead_letter_dir, &config.worker_id);
    let cache = GitCache {
        repos_dir: config.repos_dir.clone(),
        github_pat: config.github_pat.clone(),
        gitlab_token: config.gitlab_token.clone(),
        lock_wait: Duration::from_secs(60),
        lock_poll: Duration::from_millis(500),
        git_timeout: Duration::from_secs(600),
    };
    let claude = ClaudeExec {
        cli: config.claude_cli.clone(),
        mcp,
    };
    let auth: Mutex<AuthState> = Mutex::new(AuthState::default());
    let hostname = os_hostname();
    let parent_env: Vec<(String, String)> = opts
        .env
        .iter()
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();

    let shared = Shared {
        config: &config,
        timings: &timings,
        stop: &stop,
        api: &api,
        dead_letter: &dead_letter,
        cache: &cache,
        claude: &claude,
        auth: &auth,
        hostname: &hostname,
        env: &opts.env,
        parent_env: &parent_env,
    };
    std::thread::scope(|s| {
        let polling = s.spawn(|| guarded("polling", || shared.poll_loop()));
        s.spawn(|| guarded("heartbeat", || shared.heartbeat_loop()));
        s.spawn(|| guarded("admin", || shared.admin_loop()));
        // `guarded` exits the process on a panic, so a join error is not expected here.
        let outcome = polling
            .join()
            .unwrap_or_else(|_| Err("polling thread ended abnormally".to_string()));
        // Whatever ended the polling thread ends the other two as well.
        stop.store(true, Ordering::Relaxed);
        outcome
    })
}

struct Shared<'a> {
    config: &'a Config,
    timings: &'a Timings,
    stop: &'a AtomicBool,
    api: &'a ApiClient,
    dead_letter: &'a DeadLetter,
    cache: &'a GitCache,
    claude: &'a ClaudeExec,
    auth: &'a Mutex<AuthState>,
    hostname: &'a str,
    env: &'a HashMap<String, String>,
    parent_env: &'a [(String, String)],
}

impl Shared<'_> {
    fn stopped(&self) -> bool {
        self.stop.load(Ordering::Relaxed)
    }

    fn heartbeat_loop(&self) {
        while !self.stopped() {
            let auth = self.auth.lock().unwrap_or_else(|e| e.into_inner()).clone();
            let hb = Heartbeat {
                worker_id: self.config.worker_id.clone(),
                hostname: self.hostname.to_string(),
                version: self.config.version.clone(),
                // undetermined stays undetermined (the key is left out), never `false`
                claude_session_ok: auth.ok,
                vpn_status: None,
                mcps: self.claude.mcp.server_names().to_vec(),
                lost_report_count: self.dead_letter.pending_count() as i64,
                claude_auth_method: auth.method,
                claude_auth_expires_at: auth.expires_at,
                dead_report_count: self.dead_letter.dead_count() as i64,
            };
            if let Err(e) = self.api.heartbeat(&hb) {
                log::warn(&format!("heartbeat failed: {e}"));
            }
            sleep_unless_stopped(self.stop, self.timings.heartbeat);
        }
    }

    fn admin_loop(&self) {
        let mut next_probe = Instant::now();
        let mut next_replay = Instant::now() + self.timings.replay;
        while !self.stopped() {
            let now = Instant::now();
            if now >= next_probe {
                let state = auth_probe::probe(self.env, &self.config.home, &self.config.claude_cli);
                *self.auth.lock().unwrap_or_else(|e| e.into_inner()) = state;
                next_probe = Instant::now() + self.timings.auth_check;
            }
            if now >= next_replay {
                self.dead_letter.replay(self.api, REPLAY_MAX_ATTEMPTS);
                next_replay = Instant::now() + self.timings.replay;
            }
            sleep_unless_stopped(self.stop, STOP_CHECK);
        }
    }

    /// Retries the startup notice until it succeeds; `false` when stopped first.
    fn report_startup(&self) -> bool {
        while !self.stopped() {
            match self.api.startup(&self.dead_letter.pending_task_ids()) {
                Ok(()) => {
                    log::info("startup reported");
                    return true;
                }
                Err(e) => {
                    log::warn(&format!("startup notice failed (retrying next cycle): {e}"));
                    sleep_unless_stopped(self.stop, self.timings.poll);
                }
            }
        }
        false
    }

    /// `Err` = fatal (a claim of a kind this worker does not implement); `Ok` when stopped.
    fn poll_loop(&self) -> Result<(), String> {
        if !self.report_startup() {
            return Ok(());
        }
        let deps = Deps {
            worker_id: &self.config.worker_id,
            cache: self.cache,
            claude: self.claude,
            analysis_timeout: self.timings.analysis_timeout,
            parent_env: Some(self.parent_env),
        };
        while !self.stopped() {
            match self.api.next_task() {
                Err(e) => log::warn(&format!("next-task poll failed: {e}")),
                Ok(None) => {}
                Ok(Some(task)) => {
                    log::info(&format!(
                        "task claimed: id={} kind={} repo={}/{}",
                        task.id, task.kind, task.github_repo, task.github_branch
                    ));
                    if !IMPLEMENTED_KINDS.contains(&task.kind.as_str()) {
                        // Fail-stop: a server that ignored `kinds` would otherwise hand this
                        // worker one foreign task per poll. Not processed, not reported.
                        log::error(&format!(
                            "task {} has kind {} which this worker does not process - stopping; the server's dead-worker recovery requeues it",
                            task.id, task.kind
                        ));
                        return Err(fatal_wrong_kind(&task.kind, task.id));
                    } else {
                        let result = analysis::process(&task, &deps);
                        let delivered = report::report_terminal(
                            self.api,
                            self.dead_letter,
                            task.id,
                            result,
                            &self.timings.backoff,
                        );
                        log::info(&format!(
                            "task {} reported (delivered={delivered})",
                            task.id
                        ));
                    }
                }
            }
            sleep_unless_stopped(self.stop, self.timings.poll);
        }
        Ok(())
    }
}

/// Reason for the fatal stop after a claim of a kind this worker does not implement.
pub fn fatal_wrong_kind(kind: &str, task_id: i64) -> String {
    format!("claimed a task of a kind this worker does not implement: {kind} (task {task_id})")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn run_options_debug_prints_no_env_values() {
        let opts = RunOptions {
            env: HashMap::from([(
                "WORKER_API_KEY".to_string(),
                "run-options-secret".to_string(),
            )]),
        };
        for text in [format!("{opts:?}"), format!("{opts:#?}")] {
            assert!(!text.contains("run-options-secret"), "{text}");
            assert!(!text.contains("WORKER_API_KEY"), "{text}");
        }
    }

    #[test]
    fn wrong_kind_reason_names_kind_and_task() {
        assert_eq!(
            fatal_wrong_kind("DEPLOY", 7),
            "claimed a task of a kind this worker does not implement: DEPLOY (task 7)"
        );
    }

    #[test]
    fn hostname_is_cut_to_100_utf16_units() {
        let long = "가".repeat(150);
        assert_eq!(hostname_from(Some(&long)).encode_utf16().count(), 100);
        // an emoji straddling the limit is dropped whole: 1 + 49 emoji = 99 units
        let emoji = format!("a{}", "😀".repeat(60));
        let cut = hostname_from(Some(&emoji));
        assert_eq!(cut.encode_utf16().count(), 99);
        assert!(cut.starts_with('a'));
        assert_eq!(hostname_from(Some("  host-a \n")), "host-a");
    }

    #[test]
    fn missing_or_blank_hostname_is_unknown() {
        assert_eq!(hostname_from(None), "unknown");
        assert_eq!(hostname_from(Some("  \n")), "unknown");
    }

    #[test]
    fn default_timings_match_the_constants() {
        let t = Timings::default();
        assert_eq!(t.poll, Duration::from_secs(5));
        assert_eq!(t.heartbeat, Duration::from_secs(10));
        assert_eq!(t.replay, Duration::from_secs(60));
        assert_eq!(t.auth_check, Duration::from_secs(300));
        assert_eq!(t.analysis_timeout, Duration::from_secs(600));
        assert_eq!(t.backoff.len(), 5);
    }
}
