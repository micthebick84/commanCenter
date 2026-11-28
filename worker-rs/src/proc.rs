//! Child-process runner (Java `ProcessRunner.execute` / `ClaudeExecAdapter.run`).
//!
//! Reader threads drain stdout and stderr while the caller waits for the process to
//! EXIT (not for EOF) up to a limit. After a normal exit the readers get at most
//! [`READER_GRACE`] to finish, so a grandchild that keeps the pipes open cannot block
//! the call. On timeout the whole process tree is killed.

use crate::log;
use std::io::{Read, Write};
use std::process::{Child, Command, ExitStatus, Stdio};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

/// How long to wait for the output readers once the process has exited (or was killed).
const READER_GRACE: Duration = Duration::from_secs(2);
const EXIT_POLL: Duration = Duration::from_millis(15);
/// Upper bound for the kill helper, and for the child's exit after the kill.
const KILL_WAIT: Duration = Duration::from_secs(10);

#[derive(Debug, Clone)]
pub struct Output {
    pub exit_code: i32,
    pub stdout: String,
    pub stderr: String,
    pub timed_out: bool,
    pub duration_ms: u64,
}

/// Output collected by a reader thread. `closed` mirrors Java `OutputDrain.close()`: once
/// `run` has taken its final snapshot the thread discards anything it still reads, so a
/// grandchild that keeps writing cannot grow memory after the call returned.
#[derive(Clone, Default)]
struct Sink(Arc<Mutex<SinkState>>);

#[derive(Default)]
struct SinkState {
    buf: Vec<u8>,
    closed: bool,
}

impl Sink {
    fn push(&self, data: &[u8]) {
        if let Ok(mut s) = self.0.lock()
            && !s.closed
        {
            s.buf.extend_from_slice(data);
        }
    }

    /// Final snapshot as lossy UTF-8; later `push` calls are ignored.
    fn take_and_close(&self) -> String {
        let bytes = match self.0.lock() {
            Ok(mut s) => {
                s.closed = true;
                std::mem::take(&mut s.buf)
            }
            Err(_) => Vec::new(),
        };
        String::from_utf8_lossy(&bytes).into_owned()
    }
}

fn drain<R: Read + Send + 'static>(mut src: R) -> (Sink, JoinHandle<()>) {
    let sink = Sink::default();
    let target = sink.clone();
    let handle = std::thread::spawn(move || {
        let mut buf = [0u8; 8192];
        loop {
            match src.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => target.push(&buf[..n]),
            }
        }
    });
    (sink, handle)
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

    // Every early return closes both sinks, so detached readers stop collecting output.
    let close_sinks = || {
        out_sink.take_and_close();
        err_sink.take_and_close();
    };
    let deadline = started + timeout;
    let mut timed_out = false;
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status,
            Ok(None) => {}
            Err(e) => {
                let _ = kill_tree(&mut child);
                close_sinks();
                return Err(e);
            }
        }
        if Instant::now() >= deadline {
            timed_out = true;
            match kill_tree(&mut child) {
                Ok(status) => break status,
                Err(e) => {
                    close_sinks();
                    return Err(e);
                }
            }
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
        stdout: out_sink.take_and_close(),
        stderr: err_sink.take_and_close(),
        timed_out,
        duration_ms,
    })
}

/// Polls `poll` every [`EXIT_POLL`] until it yields a value or `limit` has passed.
/// `Ok(None)` = still nothing after `limit`; an error from `poll` ends the wait at once.
fn wait_bounded<T>(
    limit: Duration,
    mut poll: impl FnMut() -> std::io::Result<Option<T>>,
) -> std::io::Result<Option<T>> {
    let end = Instant::now() + limit;
    loop {
        if let Some(v) = poll()? {
            return Ok(Some(v));
        }
        if Instant::now() >= end {
            return Ok(None);
        }
        std::thread::sleep(EXIT_POLL);
    }
}

/// Runs a kill helper (`taskkill` / `kill`) and waits for it at most [`KILL_WAIT`]; a helper
/// that hangs is killed itself. Never blocks longer than that.
fn run_kill_helper(cmd: &mut Command) {
    let Ok(mut helper) = cmd
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
    else {
        return;
    };
    if !matches!(wait_bounded(KILL_WAIT, || helper.try_wait()), Ok(Some(_))) {
        log::warn(&format!(
            "kill helper (pid {}) did not finish in {}s - killing it",
            helper.id(),
            KILL_WAIT.as_secs()
        ));
        let _ = helper.kill();
        let _ = wait_bounded(KILL_WAIT, || helper.try_wait());
    }
}

/// Kills the child and everything it started, then waits (bounded) for the child's exit.
/// `Err` when the child is still alive after [`KILL_WAIT`] - logged with its pid; the caller
/// gets the error instead of hanging (the process is not ended here).
fn kill_tree(child: &mut Child) -> std::io::Result<ExitStatus> {
    let pid = child.id();
    #[cfg(windows)]
    run_kill_helper(Command::new("taskkill").args(["/PID", &pid.to_string(), "/T", "/F"]));
    #[cfg(unix)]
    run_kill_helper(Command::new("kill").args(["-KILL", "--", &format!("-{pid}")]));
    let _ = child.kill(); // fallback: at least the direct child
    match wait_bounded(KILL_WAIT, || child.try_wait()) {
        Ok(Some(status)) => Ok(status),
        Ok(None) => {
            log::error(&format!(
                "process {pid} could not be killed (still running {}s after the kill)",
                KILL_WAIT.as_secs()
            ));
            Err(std::io::Error::other(format!(
                "process {pid} could not be killed"
            )))
        }
        Err(e) => Err(e),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;

    /// A reader fed from a channel, so the test decides when data arrives.
    struct ChannelReader(mpsc::Receiver<Vec<u8>>);

    impl Read for ChannelReader {
        fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
            match self.0.recv() {
                Ok(data) => {
                    buf[..data.len()].copy_from_slice(&data);
                    Ok(data.len())
                }
                Err(_) => Ok(0),
            }
        }
    }

    #[test]
    fn wait_bounded_gives_up_on_a_condition_that_never_holds() {
        let t0 = Instant::now();
        let mut polls = 0;
        let got = wait_bounded::<()>(Duration::from_millis(120), || {
            polls += 1;
            Ok(None)
        })
        .unwrap();
        assert_eq!(got, None);
        let took = t0.elapsed();
        assert!(took >= Duration::from_millis(120), "{took:?}");
        assert!(took < Duration::from_secs(5), "{took:?}");
        assert!(polls >= 2, "polled {polls} times");
    }

    #[test]
    fn wait_bounded_returns_as_soon_as_the_value_appears() {
        let mut polls = 0;
        let got = wait_bounded(Duration::from_secs(30), || {
            polls += 1;
            Ok((polls == 3).then_some(42))
        })
        .unwrap();
        assert_eq!(got, Some(42));
        assert_eq!(polls, 3);
    }

    #[test]
    fn wait_bounded_passes_a_poll_error_through() {
        let got = wait_bounded::<()>(Duration::from_secs(30), || {
            Err(std::io::Error::other("try_wait failed"))
        });
        assert_eq!(got.unwrap_err().to_string(), "try_wait failed");
    }

    #[test]
    fn drain_discards_data_after_close() {
        let (tx, rx) = mpsc::channel();
        let (sink, handle) = drain(ChannelReader(rx));
        tx.send(b"before".to_vec()).unwrap();
        // Wait (bounded) until the thread has collected the first chunk.
        let t0 = Instant::now();
        while sink.0.lock().unwrap().buf.is_empty() && t0.elapsed() < Duration::from_secs(5) {
            std::thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(sink.take_and_close(), "before");
        // The "grandchild" keeps writing after run returned: nothing may be kept.
        tx.send(b"after-after-after".to_vec()).unwrap();
        drop(tx);
        handle.join().unwrap();
        let state = sink.0.lock().unwrap();
        assert!(
            state.buf.is_empty(),
            "collected after close: {:?}",
            state.buf
        );
    }
}
