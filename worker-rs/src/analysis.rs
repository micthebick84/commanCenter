//! Analysis processing (Java `WorkerMainLoop.processAnalysis`): prepare the repo, run claude,
//! parse the answer, and turn every outcome into a ready-to-report `ResultRequest`.

use crate::claude::{ClaudeExec, ExecResult, tail_utf16};
use crate::contract::ANALYSIS_PROMPT;
use crate::dto::{ResultRequest, TaskClaim};
use crate::log;
use crate::parser;
use crate::repo::{GitCache, RepoRef};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::time::Duration;

const FAILURE_TAIL_UNITS: usize = 2000;

pub struct Deps<'a> {
    pub worker_id: &'a str,
    pub cache: &'a GitCache,
    pub claude: &'a ClaudeExec,
    pub analysis_timeout: Duration,
    /// Environment the claude child starts from (secrets are stripped later).
    /// `None` = this process's real environment; tests pass their own.
    pub parent_env: Option<&'a [(String, String)]>,
}

fn failed(worker_id: &str, reason: String, exec: Option<&ExecResult>) -> ResultRequest {
    ResultRequest {
        worker_id: worker_id.to_string(),
        status: "FAILED".to_string(),
        markdown_result: None,
        subtasks_json: None,
        claude_log: None,
        duration_ms: None,
        failure_reason: Some(reason),
        usage: exec.and_then(|e| e.usage.clone()),
    }
}

/// The panic payload as text, with `user:secret@` in URLs masked.
pub(crate) fn panic_message(payload: &(dyn std::any::Any + Send)) -> String {
    crate::mask::mask(&panic_payload_text(payload))
}

pub(crate) fn panic_payload_text(payload: &(dyn std::any::Any + Send)) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic".to_string()
    }
}

/// Runs `f`; a panic inside becomes a FAILED result (`처리 중 예외: panic: <message>`).
pub fn catch_panic(worker_id: &str, f: impl FnOnce() -> ResultRequest) -> ResultRequest {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(r) => r,
        Err(payload) => failed(
            worker_id,
            format!("처리 중 예외: panic: {}", panic_message(payload.as_ref())),
            None,
        ),
    }
}

/// Always returns a COMPLETED or FAILED request; never panics.
pub fn process(task: &TaskClaim, deps: &Deps) -> ResultRequest {
    catch_panic(deps.worker_id, || run(task, deps))
}

fn run(task: &TaskClaim, deps: &Deps) -> ResultRequest {
    let id = deps.worker_id;
    if task.github_repo.trim().is_empty() {
        return failed(id, "레포 fetch 실패: githubRepo is empty".to_string(), None);
    }
    if task.github_branch.trim().is_empty() {
        return failed(
            id,
            "레포 fetch 실패: githubBranch is empty".to_string(),
            None,
        );
    }
    let repo_ref = RepoRef::from_snapshot(&task.github_repo, task.git_url.as_deref());
    let checkout = match deps.cache.ensure_fresh(&repo_ref, &task.github_branch) {
        Ok(c) => c,
        Err(e) => return failed(id, format!("레포 fetch 실패: {e}"), None),
    };

    // Sequential replacement in Java's order: a later value is never re-scanned.
    let prompt = ANALYSIS_PROMPT
        .replace("{github_repo}", &task.github_repo)
        .replace("{github_branch}", &task.github_branch)
        .replace("{commit_sha}", &checkout.commit_sha)
        .replace("{title}", &task.title)
        .replace("{description}", task.description.as_deref().unwrap_or(""));

    let model = task.model.as_deref();
    let effort = task.effort.as_deref();
    let exec = match deps.parent_env {
        Some(env) => deps.claude.exec_with_env(
            &prompt,
            &checkout.dir,
            deps.analysis_timeout,
            &task.mcps_extra,
            model,
            effort,
            env.iter().cloned(),
        ),
        None => deps.claude.exec(
            &prompt,
            &checkout.dir,
            deps.analysis_timeout,
            &task.mcps_extra,
            model,
            effort,
        ),
    };
    let exec = match exec {
        Ok(e) => e,
        Err(e) => return failed(id, format!("claude exec 실패: {e}"), None),
    };
    if exec.exit_code != 0 {
        let (tail, cut) = tail_utf16(&exec.stdout, FAILURE_TAIL_UNITS);
        let reason = format!(
            "claude exit={}\n{}{}",
            exec.exit_code,
            if cut { "…" } else { "" },
            tail
        );
        return failed(id, reason, Some(&exec));
    }

    let parsed = match parser::parse(&exec.stdout) {
        Ok(p) => p,
        Err(e) => return failed(id, format!("파싱 실패: {e}"), Some(&exec)),
    };
    log::info(&format!(
        "analysis done: task={} duration={}ms warnings={:?}",
        task.id, exec.duration_ms, parsed.warnings
    ));
    ResultRequest {
        worker_id: id.to_string(),
        status: "COMPLETED".to_string(),
        markdown_result: Some(parsed.markdown),
        subtasks_json: Some(parsed.subtasks_json),
        claude_log: Some(exec.stdout.clone()),
        duration_ms: Some(i64::try_from(exec.duration_ms).unwrap_or(i64::MAX)),
        failure_reason: None,
        usage: exec.usage,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn caught_panic_message_is_masked() {
        let r = catch_panic("w-1", || {
            panic!("fetch https://oauth2:caught-secret@h/x.git failed")
        });
        assert_eq!(r.status, "FAILED");
        let reason = r.failure_reason.unwrap();
        assert!(!reason.contains("caught-secret"), "{reason}");
        assert!(reason.contains("https://***@h/x.git"), "{reason}");
    }
}
