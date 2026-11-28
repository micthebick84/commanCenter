//! Child-process runner (Java `ProcessRunner.execute` / `ClaudeExecAdapter.run`).
//!
//! Reader threads drain stdout and stderr while the caller waits for the process to
//! EXIT (not for EOF) up to a limit. After a normal exit the readers get at most
//! [`READER_GRACE`] to finish, so a grandchild that keeps the pipes open cannot block
//! the call. On timeout the whole process tree is killed.

use std::io::{Read, Write};
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

/// How long to wait for the output readers once the process has exited (or was killed).
const READER_GRACE: Duration = Duration::from_secs(2);
const EXIT_POLL: Duration = Duration::from_millis(15);

#[derive(Debug, Clone)]
pub struct Output {
    pub exit_code: i32,
    pub stdout: String,
    pub stderr: String,
    pub timed_out: bool,
    pub duration_ms: u64,
}

type Sink = Arc<Mutex<Vec<u8>>>;

fn drain<R: Read + Send + 'static>(mut src: R) -> (Sink, JoinHandle<()>) {
    let sink: Sink = Arc::new(Mutex::new(Vec::new()));
    let target = Arc::clone(&sink);
    let handle = std::thread::spawn(move || {
        let mut buf = [0u8; 8192];
        loop {
            match src.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    if let Ok(mut v) = target.lock() {
                        v.extend_from_slice(&buf[..n]);
                    }
                }
            }
        }
    });
    (sink, handle)
}

fn text(sink: &Sink) -> String {
    let bytes = sink.lock().map(|v| v.clone()).unwrap_or_default();
    String::from_utf8_lossy(&bytes).into_owned()
}

/// Runs `cmd` with piped stdout/stderr. `stdin` (if any) is written on its own thread and
/// then closed; without it the child's stdin is null.
pub fn run(cmd: &mut Command, stdin: Option<&[u8]>, timeout: Duration) -> std::io::Result<Output> {
    cmd.stdin(if stdin.is_some() {
        Stdio::piped()
    } else {
        Stdio::null()
    })
    .stdout(Stdio::piped())
    .stderr(Stdio::piped());
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        cmd.process_group(0); // own group, so the whole tree can be killed
    }

    let started = Instant::now();
    let mut child = cmd.spawn()?;
    let (out_sink, out_reader) = drain(child.stdout.take().expect("piped stdout"));
    let (err_sink, err_reader) = drain(child.stderr.take().expect("piped stderr"));
    if let (Some(data), Some(mut pipe)) = (stdin, child.stdin.take()) {
        let data = data.to_vec();
        // Own thread: a child that writes a lot before reading must not deadlock us.
        std::thread::spawn(move || {
            let _ = pipe.write_all(&data);
            // dropping `pipe` closes the child's stdin
        });
    }

    let deadline = started + timeout;
    let mut timed_out = false;
    let status = loop {
        if let Some(status) = child.try_wait()? {
            break status;
        }
        if Instant::now() >= deadline {
            timed_out = true;
            kill_tree(&mut child);
            break child.wait()?;
        }
        std::thread::sleep(EXIT_POLL);
    };
    let duration_ms = started.elapsed().as_millis() as u64;

    // Readers may be stuck behind a grandchild that holds the pipe: wait briefly, then
    // return whatever was read so far (the threads are detached and end with the pipe).
    let grace_end = Instant::now() + READER_GRACE;
    while !(out_reader.is_finished() && err_reader.is_finished()) && Instant::now() < grace_end {
        std::thread::sleep(Duration::from_millis(10));
    }

    Ok(Output {
        exit_code: status.code().unwrap_or(-1),
        stdout: text(&out_sink),
        stderr: text(&err_sink),
        timed_out,
        duration_ms,
    })
}

/// Kills the child and everything it started.
fn kill_tree(child: &mut Child) {
    let pid = child.id().to_string();
    #[cfg(windows)]
    {
        let _ = Command::new("taskkill")
            .args(["/PID", &pid, "/T", "/F"])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }
    #[cfg(unix)]
    {
        let _ = Command::new("kill")
            .args(["-KILL", "--", &format!("-{pid}")])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }
    let _ = child.kill(); // fallback: at least the direct child
}
