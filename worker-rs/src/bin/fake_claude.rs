//! Test-only stand-in for the `claude` CLI (built only with `--features test-bins`).
//!
//! Behavior is driven by environment variables so tests need no per-OS shell scripts:
//!
//! | variable                      | effect                                                        |
//! |-------------------------------|---------------------------------------------------------------|
//! | `FAKE_CLAUDE_ECHO_STDIN=1`    | copy stdin (read to EOF) to stdout                            |
//! | `FAKE_CLAUDE_STDOUT_FILE`     | print that file's bytes to stdout                             |
//! | `FAKE_CLAUDE_STDERR`          | print that string to stderr                                   |
//! | `FAKE_CLAUDE_RECORD`          | write `{"args","stdin","cwd","envKeys"}` JSON to that path    |
//! | `FAKE_CLAUDE_SPAWN_CHILD=1`   | spawn a copy of this program (inherits stdout/stderr) that    |
//! |                               | sleeps `FAKE_CLAUDE_CHILD_SLEEP_MS` (default 60000), and      |
//! |                               | print `child_pid=<pid>` on stdout                             |
//! | `FAKE_CLAUDE_SLEEP_MS`        | sleep this long before exiting                                |
//! | `FAKE_CLAUDE_CHILD_PID_FILE`  | with SPAWN_CHILD: also write the spawned child's pid to that file |
//! | `FAKE_CLAUDE_AUTH_JSON`       | when args are `auth status --json`: print this text, then exit |
//! | `FAKE_CLAUDE_EXIT`            | exit code (default 0)                                         |
//!
//! Stdin is only read when `ECHO_STDIN` or `RECORD` is set, so a caller that never
//! closes stdin does not hang a run that does not need it.

use std::io::{Read, Write};
use std::process::{Command, Stdio};
use std::time::Duration;

fn var(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|v| !v.is_empty())
}

// The spawned child is deliberately never waited for: tests need it to outlive us.
#[allow(clippy::zombie_processes)]
fn main() {
    let echo = var("FAKE_CLAUDE_ECHO_STDIN").as_deref() == Some("1");
    let record = var("FAKE_CLAUDE_RECORD");

    let mut stdin_bytes = Vec::new();
    if echo || record.is_some() {
        let _ = std::io::stdin().read_to_end(&mut stdin_bytes);
    }

    if let Some(path) = record {
        let mut env_keys: Vec<String> = std::env::vars().map(|(k, _)| k).collect();
        env_keys.sort();
        let doc = serde_json::json!({
            "args": std::env::args().skip(1).collect::<Vec<_>>(),
            "stdin": String::from_utf8_lossy(&stdin_bytes),
            "cwd": std::env::current_dir().map(|p| p.display().to_string()).unwrap_or_default(),
            "envKeys": env_keys,
        });
        std::fs::write(path, doc.to_string()).expect("write record file");
    }

    let mut stdout = std::io::stdout();
    let argv: Vec<String> = std::env::args().skip(1).collect();
    if argv == ["auth", "status", "--json"]
        && let Some(json) = var("FAKE_CLAUDE_AUTH_JSON")
    {
        let _ = stdout.write_all(json.as_bytes());
        let _ = stdout.flush();
        let code = var("FAKE_CLAUDE_EXIT")
            .and_then(|v| v.parse::<i32>().ok())
            .unwrap_or(0);
        std::process::exit(code);
    }
    if echo {
        let _ = stdout.write_all(&stdin_bytes);
    }
    if let Some(path) = var("FAKE_CLAUDE_STDOUT_FILE") {
        let _ = stdout.write_all(&std::fs::read(path).expect("read stdout file"));
    }
    if let Some(text) = var("FAKE_CLAUDE_STDERR") {
        let _ = std::io::stderr().write_all(text.as_bytes());
    }

    if var("FAKE_CLAUDE_SPAWN_CHILD").as_deref() == Some("1") {
        let exe = std::env::current_exe().expect("current exe");
        let child_sleep = var("FAKE_CLAUDE_CHILD_SLEEP_MS").unwrap_or_else(|| "60000".into());
        let child = Command::new(exe)
            .env_remove("FAKE_CLAUDE_SPAWN_CHILD")
            .env_remove("FAKE_CLAUDE_STDOUT_FILE")
            .env_remove("FAKE_CLAUDE_STDERR")
            .env_remove("FAKE_CLAUDE_RECORD")
            .env_remove("FAKE_CLAUDE_ECHO_STDIN")
            .env_remove("FAKE_CLAUDE_EXIT")
            .env_remove("FAKE_CLAUDE_CHILD_PID_FILE")
            .env_remove("FAKE_CLAUDE_AUTH_JSON")
            .env("FAKE_CLAUDE_SLEEP_MS", child_sleep)
            .stdin(Stdio::null())
            .stdout(Stdio::inherit())
            .stderr(Stdio::inherit())
            .spawn()
            .expect("spawn child");
        let _ = writeln!(stdout, "child_pid={}", child.id());
        if let Some(path) = var("FAKE_CLAUDE_CHILD_PID_FILE") {
            std::fs::write(path, child.id().to_string()).expect("write child pid file");
        }
    }
    let _ = stdout.flush();

    if let Some(ms) = var("FAKE_CLAUDE_SLEEP_MS").and_then(|v| v.parse::<u64>().ok()) {
        std::thread::sleep(Duration::from_millis(ms));
    }
    let code = var("FAKE_CLAUDE_EXIT")
        .and_then(|v| v.parse::<i32>().ok())
        .unwrap_or(0);
    std::process::exit(code);
}
