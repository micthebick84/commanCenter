//! Repository identification and the shared git cache (Java `RepoRef`, `GitRemotes`,
//! `GitRepoCache.ensureFresh` / `withRepoLock`).
//!
//! The cache folder is shared with the Java worker, so folder keys, the `<key>.lock`
//! and `<key>.last-used` files, and the git commands match the Java code.

use crate::{log, mask, proc};
use regex::Regex;
use std::fs::{File, OpenOptions, TryLockError};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::OnceLock;
use std::time::{Duration, Instant};
use time::OffsetDateTime;
use time::format_description::well_known::Rfc3339;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Host {
    Github,
    Gitlab,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RepoRef {
    pub host: Host,
    /// GitHub: `owner/repo`; GitLab: the full project path.
    pub path: String,
    /// Plain https URL without credentials.
    pub git_url: String,
}

/// Java `^(https?://)(?:[^@/\s]+@)?([^/\s]+)` (`\s` is ASCII-only in Java).
fn origin_pattern() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(r"^(https?://)(?:[^@/ \t\n\x0B\x0C\r]+@)?([^/ \t\n\x0B\x0C\r]+)")
            .expect("valid origin regex")
    })
}

fn is_blank(s: &str) -> bool {
    // Java `String.isBlank` treats Unicode white space as blank.
    s.chars().all(char::is_whitespace)
}

impl RepoRef {
    /// Restores a ref from the task snapshot. No `git_url` means GitHub; otherwise the
    /// host name decides (`github.com`, case-insensitive, port ignored -> GitHub).
    pub fn from_snapshot(path: &str, git_url: Option<&str>) -> RepoRef {
        let url = match git_url {
            Some(u) if !is_blank(u) => u,
            _ => {
                return RepoRef {
                    host: Host::Github,
                    path: path.to_string(),
                    git_url: format!("https://github.com/{path}.git"),
                };
            }
        };
        let authority = origin_pattern()
            .captures(url)
            .and_then(|c| c.get(2))
            .map(|m| m.as_str())
            .unwrap_or("");
        let host_name = strip_port(authority);
        RepoRef {
            host: if host_name.eq_ignore_ascii_case("github.com") {
                Host::Github
            } else {
                Host::Gitlab
            },
            path: path.to_string(),
            git_url: url.to_string(),
        }
    }

    /// Local folder key: GitHub `owner/repo`, GitLab `_gitlab/<path with / -> +>`.
    pub fn local_key(&self) -> String {
        match self.host {
            Host::Github => self.path.clone(),
            Host::Gitlab => format!("_gitlab/{}", self.path.replace('/', "+")),
        }
    }
}

/// Java `replaceAll(":\\d+$", "")`.
fn strip_port(authority: &str) -> &str {
    match authority.rfind(':') {
        Some(i)
            if i + 1 < authority.len()
                && authority[i + 1..].bytes().all(|b| b.is_ascii_digit()) =>
        {
            &authority[..i]
        }
        _ => authority,
    }
}

/// URL for clone/fetch: `https://oauth2:<token>@host/path.git` when the host's token is
/// set, else the plain URL (an empty `oauth2:@` password is rejected by GitHub).
pub fn authenticated_url(
    r: &RepoRef,
    github_pat: Option<&str>,
    gitlab_token: Option<&str>,
) -> String {
    let token = match r.host {
        Host::Gitlab => gitlab_token,
        Host::Github => github_pat,
    };
    let Some(token) = token.filter(|t| !is_blank(t)) else {
        return r.git_url.clone();
    };
    for scheme in ["https://", "http://"] {
        if let Some(rest) = r.git_url.strip_prefix(scheme) {
            return format!("{scheme}oauth2:{token}@{rest}");
        }
    }
    r.git_url.clone()
}

#[derive(Debug, Clone)]
pub struct CheckedOut {
    pub dir: PathBuf,
    pub commit_sha: String,
}

#[derive(Clone)]
pub struct GitCache {
    pub repos_dir: PathBuf,
    pub github_pat: Option<String>,
    pub gitlab_token: Option<String>,
    /// Production: 60 s.
    pub lock_wait: Duration,
    /// Production: 500 ms.
    pub lock_poll: Duration,
    /// Production: 600 s.
    pub git_timeout: Duration,
}

/// Hand-written so the PAT and the GitLab token are never printed (`***` when set).
impl std::fmt::Debug for GitCache {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let hidden = |v: &Option<String>| v.as_ref().map(|_| "***");
        f.debug_struct("GitCache")
            .field("repos_dir", &self.repos_dir)
            .field("github_pat", &hidden(&self.github_pat))
            .field("gitlab_token", &hidden(&self.gitlab_token))
            .field("lock_wait", &self.lock_wait)
            .field("lock_poll", &self.lock_poll)
            .field("git_timeout", &self.git_timeout)
            .finish()
    }
}

impl GitCache {
    /// Clones or fast-forwards the cache for `r` and returns the folder and `HEAD` sha.
    /// Holds the repo lock for the duration of the call.
    pub fn ensure_fresh(&self, r: &RepoRef, branch: &str) -> Result<CheckedOut, String> {
        let key = r.local_key();
        let lock_path = self.repos_dir.join(format!("{key}.lock"));
        if let Some(parent) = lock_path.parent() {
            std::fs::create_dir_all(parent).map_err(|e| format!("{e}"))?;
        }
        // Keep the handle open while holding the lock; never delete the lock file.
        let lock = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(false)
            .open(&lock_path)
            .map_err(|e| format!("{e}"))?;
        self.acquire(&lock, &key)?;
        self.touch_last_used(&key);
        let result = self.do_ensure_fresh(r, branch);
        let _ = lock.unlock();
        drop(lock);
        result
    }

    fn acquire(&self, lock: &File, key: &str) -> Result<(), String> {
        let deadline = Instant::now() + self.lock_wait;
        loop {
            match lock.try_lock() {
                Ok(()) => return Ok(()),
                Err(TryLockError::WouldBlock) => {}
                Err(TryLockError::Error(e)) => return Err(format!("{e}")),
            }
            if Instant::now() >= deadline {
                return Err(format!(
                    "repo lock timeout ({}s): {key}",
                    self.lock_wait.as_secs()
                ));
            }
            std::thread::sleep(self.lock_poll);
        }
    }

    /// Records the last use. Failure is only a warning (the cleanup job then falls back
    /// to git metadata mtimes).
    fn touch_last_used(&self, key: &str) {
        let marker = self.repos_dir.join(format!("{key}.last-used"));
        let stamp = OffsetDateTime::now_utc()
            .format(&Rfc3339)
            .unwrap_or_default();
        if let Err(e) = std::fs::write(&marker, stamp) {
            log::warn(&format!(
                "repo cache last-used write failed (continuing): {} - {e}",
                marker.display()
            ));
        }
    }

    fn do_ensure_fresh(&self, r: &RepoRef, branch: &str) -> Result<CheckedOut, String> {
        let key = r.local_key();
        let target = self.repos_dir.join(&key);
        let parent = target
            .parent()
            .ok_or_else(|| "repo cache path has no parent".to_string())?
            .to_path_buf();
        std::fs::create_dir_all(&parent).map_err(|e| format!("{e}"))?;
        let dir_name = target
            .file_name()
            .and_then(|n| n.to_str())
            .ok_or_else(|| "repo cache path has no name".to_string())?
            .to_string();
        let auth = authenticated_url(r, self.github_pat.as_deref(), self.gitlab_token.as_deref());

        if !target.join(".git").exists() {
            self.git(
                &parent,
                &[
                    "-c",
                    "credential.helper=",
                    "clone",
                    "--depth=1",
                    "--branch",
                    branch,
                    &auth,
                    &dir_name,
                ],
            )?;
            // Scrub right away; a failure above propagates (masked) instead.
            self.scrub_origin(&target, r)?;
        } else {
            self.scrub_origin(&target, r)?;
            let refspec = format!("+refs/heads/{branch}:refs/remotes/origin/{branch}");
            self.git(
                &target,
                &[
                    "-c",
                    "credential.helper=",
                    "fetch",
                    "--prune",
                    &auth,
                    &refspec,
                ],
            )?;
            let remote_ref = format!("origin/{branch}");
            self.git(&target, &["checkout", "--force", "--detach", &remote_ref])?;
        }
        let commit_sha = self.rev_parse_head(&target)?;
        Ok(CheckedOut {
            dir: target,
            commit_sha,
        })
    }

    fn scrub_origin(&self, target: &Path, r: &RepoRef) -> Result<(), String> {
        self.git(target, &["remote", "set-url", "origin", &r.git_url])
            .map(|_| ())
    }

    /// `git rev-parse HEAD`. Unlike the Java code, a non-zero exit or empty output is an
    /// error (an empty sha must never reach a prompt).
    pub fn rev_parse_head(&self, dir: &Path) -> Result<String, String> {
        let out = self.git(dir, &["rev-parse", "HEAD"])?;
        let sha = out.trim().to_string();
        if sha.is_empty() {
            return Err("git failed (0): rev-parse HEAD returned nothing".to_string());
        }
        Ok(sha)
    }

    /// Runs `git <args>` in `dir`; returns stdout. Errors are masked strings:
    /// `process timeout (<n>s): <command>` or `git failed (<code>): <output>`.
    fn git(&self, dir: &Path, args: &[&str]) -> Result<String, String> {
        let mut cmd = Command::new("git");
        cmd.current_dir(dir).args(args);
        let out = proc::run(&mut cmd, None, self.git_timeout)
            .map_err(|e| mask::mask(&format!("git failed to start: {e}")))?;
        if out.timed_out {
            return Err(format!(
                "process timeout ({}s): {}",
                self.git_timeout.as_secs(),
                mask::mask(&format!("git {}", args.join(" ")))
            ));
        }
        if out.exit_code != 0 {
            // Java merges stderr into stdout for these calls; do the same for the message.
            return Err(git_failure_message(out.exit_code, &out.stdout, &out.stderr));
        }
        Ok(out.stdout)
    }
}

/// `git failed (<code>): <masked output>`. Java merges stderr into stdout for these calls;
/// the message does the same, then hides `user:secret@` in URLs.
fn git_failure_message(exit_code: i32, stdout: &str, stderr: &str) -> String {
    let mut text = String::from(stdout);
    text.push_str(stderr);
    format!("git failed ({exit_code}): {}", mask::mask(text.trim_end()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn failure_message_masks_credentials_in_output() {
        let msg = git_failure_message(
            128,
            "Cloning into 'x'...\n",
            "fatal: unable to access 'https://oauth2:secret@h/x.git/'\n",
        );
        assert!(msg.starts_with("git failed (128): "), "{msg}");
        assert!(msg.contains("https://***@h/x.git/"), "{msg}");
        assert!(!msg.contains("secret"), "{msg}");
    }

    #[test]
    fn debug_never_prints_tokens() {
        let cache = GitCache {
            repos_dir: PathBuf::from("repos"),
            github_pat: Some("pat-secret-value".into()),
            gitlab_token: Some("gitlab-secret-value".into()),
            lock_wait: Duration::from_secs(1),
            lock_poll: Duration::from_millis(1),
            git_timeout: Duration::from_secs(1),
        };
        for text in [format!("{cache:?}"), format!("{cache:#?}")] {
            assert!(!text.contains("pat-secret-value"), "{text}");
            assert!(!text.contains("gitlab-secret-value"), "{text}");
            assert!(text.contains("***"), "{text}");
        }
    }
}
