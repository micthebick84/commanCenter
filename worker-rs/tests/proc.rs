//! `proc::run` tests. They drive the `fake-claude` helper (needs `--features test-bins`).
#![cfg(feature = "test-bins")]

mod common;

use common::TempDir;
use netis_worker_rs::proc;
use std::process::Command;
use std::time::{Duration, Instant};

fn fake() -> Command {
    Command::new(env!("CARGO_BIN_EXE_fake-claude"))
}

fn parse_child_pid(stdout: &str) -> u32 {
    stdout
        .lines()
        .find_map(|l| l.strip_prefix("child_pid="))
        .expect("child_pid line")
        .trim()
        .parse()
        .expect("pid")
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

/// Kills the grandchild when dropped, so a failing assertion cannot leak it.
struct KillOnDrop(u32);

impl Drop for KillOnDrop {
    fn drop(&mut self) {
        force_kill(self.0);
    }
}

#[test]
fn captures_stdout_and_stderr_separately() {
    let dir = TempDir::new("proc-out");
    let file = dir.path().join("out.txt");
    std::fs::write(&file, "hello out").unwrap();
    let mut cmd = fake();
    cmd.env("FAKE_CLAUDE_STDOUT_FILE", &file)
        .env("FAKE_CLAUDE_STDERR", "oops err");
    let out = proc::run(&mut cmd, None, Duration::from_secs(30)).unwrap();
    assert_eq!(out.exit_code, 0);
    assert_eq!(out.stdout, "hello out");
    assert_eq!(out.stderr, "oops err");
    assert!(!out.timed_out);
}

#[test]
fn writes_stdin_then_closes() {
    let mut cmd = fake();
    cmd.env("FAKE_CLAUDE_ECHO_STDIN", "1");
    let out = proc::run(
        &mut cmd,
        Some(b"line one\nline two"),
        Duration::from_secs(30),
    )
    .unwrap();
    assert_eq!(out.stdout, "line one\nline two");
}

#[test]
fn utf8_roundtrip_korean() {
    let text = "안녕하세요 NetisMaker 😀";
    let mut cmd = fake();
    cmd.env("FAKE_CLAUDE_ECHO_STDIN", "1");
    let out = proc::run(&mut cmd, Some(text.as_bytes()), Duration::from_secs(30)).unwrap();
    assert_eq!(out.stdout, text);
}

#[test]
fn nonzero_exit_is_reported() {
    let mut cmd = fake();
    cmd.env("FAKE_CLAUDE_EXIT", "7")
        .env("FAKE_CLAUDE_STDERR", "bad");
    let out = proc::run(&mut cmd, None, Duration::from_secs(30)).unwrap();
    assert_eq!(out.exit_code, 7);
    assert_eq!(out.stderr, "bad");
    assert!(!out.timed_out);
}

#[test]
fn timeout_kills_process_tree() {
    let mut cmd = fake();
    cmd.env("FAKE_CLAUDE_SPAWN_CHILD", "1")
        .env("FAKE_CLAUDE_SLEEP_MS", "120000")
        .env("FAKE_CLAUDE_CHILD_SLEEP_MS", "120000");
    let started = Instant::now();
    let out = proc::run(&mut cmd, None, Duration::from_secs(3)).unwrap();
    let elapsed = started.elapsed();
    let grandchild = parse_child_pid(&out.stdout);
    let _guard = KillOnDrop(grandchild);
    // Give the OS a moment to finish tearing the process down before looking.
    std::thread::sleep(Duration::from_millis(300));
    let alive = pid_alive(grandchild);
    assert!(out.timed_out, "expected timed_out");
    assert!(!alive, "grandchild {grandchild} survived the timeout");
    assert!(elapsed < Duration::from_secs(15), "took {elapsed:?}");
}

#[test]
fn returns_when_child_exits_but_grandchild_holds_stdout() {
    let mut cmd = fake();
    cmd.env("FAKE_CLAUDE_SPAWN_CHILD", "1")
        .env("FAKE_CLAUDE_SLEEP_MS", "0")
        .env("FAKE_CLAUDE_CHILD_SLEEP_MS", "120000");
    let started = Instant::now();
    let out = proc::run(&mut cmd, None, Duration::from_secs(30)).unwrap();
    let elapsed = started.elapsed();
    let _guard = KillOnDrop(parse_child_pid(&out.stdout));
    assert!(!out.timed_out);
    assert_eq!(out.exit_code, 0);
    assert!(elapsed < Duration::from_secs(10), "took {elapsed:?}");
}
