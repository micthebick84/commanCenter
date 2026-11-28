//! `repo` tests: pure identification helpers, and `GitCache` against local bare repositories.

mod common;

use common::TempDir;
use netis_worker_rs::repo::{GitCache, Host, RepoRef, authenticated_url};
use std::fs::File;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::Duration;

// ---- pure ----

#[test]
fn from_snapshot_defaults_to_github() {
    let r = RepoRef::from_snapshot("acme/widgets", None);
    assert_eq!(r.host, Host::Github);
    assert_eq!(r.path, "acme/widgets");
    assert_eq!(r.git_url, "https://github.com/acme/widgets.git");
    assert_eq!(
        RepoRef::from_snapshot("acme/widgets", Some("  ")).host,
        Host::Github
    );
}

#[test]
fn from_snapshot_gitlab_by_host() {
    let url = "https://git.example.test:8443/g/sub/p.git";
    let r = RepoRef::from_snapshot("g/sub/p", Some(url));
    assert_eq!(r.host, Host::Gitlab);
    assert_eq!(r.git_url, url);
}

#[test]
fn github_host_case_insensitive() {
    let r = RepoRef::from_snapshot(
        "acme/widgets",
        Some("https://GitHub.com:443/acme/widgets.git"),
    );
    assert_eq!(r.host, Host::Github);
    let r = RepoRef::from_snapshot(
        "acme/widgets",
        Some("https://user:pw@GITHUB.COM/acme/widgets.git"),
    );
    assert_eq!(r.host, Host::Github);
}

#[test]
fn local_key_github() {
    assert_eq!(
        RepoRef::from_snapshot("acme/widgets", None).local_key(),
        "acme/widgets"
    );
}

#[test]
fn local_key_gitlab() {
    let r = RepoRef::from_snapshot("g/sub/p", Some("https://git.example.test/g/sub/p.git"));
    assert_eq!(r.local_key(), "_gitlab/g+sub+p");
}

#[test]
fn authenticated_url_inserts_oauth2() {
    let gh = RepoRef::from_snapshot("acme/widgets", None);
    assert_eq!(
        authenticated_url(&gh, Some("ghp_x"), Some("glpat_y")),
        "https://oauth2:ghp_x@github.com/acme/widgets.git"
    );
    let gl = RepoRef::from_snapshot("g/p", Some("http://git.example.test/g/p.git"));
    assert_eq!(
        authenticated_url(&gl, Some("ghp_x"), Some("glpat_y")),
        "http://oauth2:glpat_y@git.example.test/g/p.git"
    );
}

#[test]
fn authenticated_url_without_token_is_plain() {
    let gh = RepoRef::from_snapshot("acme/widgets", None);
    assert_eq!(authenticated_url(&gh, None, Some("glpat_y")), gh.git_url);
    assert_eq!(authenticated_url(&gh, Some("  "), None), gh.git_url);
}

// ---- git cache against local bare repositories ----

fn git(dir: &Path, args: &[&str]) -> String {
    let out = Command::new("git")
        .current_dir(dir)
        .args(["-c", "user.name=Test", "-c", "user.email=test@example.test"])
        .args(args)
        .output()
        .expect("run git");
    assert!(
        out.status.success(),
        "git {args:?} failed: {}",
        String::from_utf8_lossy(&out.stderr)
    );
    String::from_utf8_lossy(&out.stdout).trim().to_string()
}

struct Remote {
    _root: TempDir,
    bare: PathBuf,
    work: PathBuf,
}

impl Remote {
    fn new() -> Remote {
        let root = TempDir::new("remote");
        let bare = root.path().join("bare.git");
        let work = root.path().join("work");
        std::fs::create_dir_all(&bare).unwrap();
        std::fs::create_dir_all(&work).unwrap();
        git(&bare, &["init", "--bare", "-b", "main"]);
        git(&work, &["init", "-b", "main"]);
        git(&work, &["remote", "add", "origin", bare.to_str().unwrap()]);
        let r = Remote {
            _root: root,
            bare,
            work,
        };
        r.commit("first");
        r
    }

    /// Adds a commit on main, pushes it, and returns its sha.
    fn commit(&self, name: &str) -> String {
        std::fs::write(self.work.join(format!("{name}.txt")), name).unwrap();
        git(&self.work, &["add", "."]);
        git(&self.work, &["commit", "-m", name]);
        git(&self.work, &["push", "origin", "main"]);
        git(&self.work, &["rev-parse", "HEAD"])
    }

    fn url(&self) -> String {
        self.bare.to_str().unwrap().to_string()
    }
}

fn cache(repos: &Path) -> GitCache {
    GitCache {
        repos_dir: repos.to_path_buf(),
        github_pat: None,
        gitlab_token: None,
        lock_wait: Duration::from_secs(10),
        lock_poll: Duration::from_millis(20),
        git_timeout: Duration::from_secs(60),
    }
}

#[test]
fn first_call_clones_and_returns_head_sha() {
    let remote = Remote::new();
    let repos = TempDir::new("repos");
    let r = RepoRef::from_snapshot("acme/widgets", Some(&remote.url()));
    let got = cache(repos.path()).ensure_fresh(&r, "main").unwrap();
    assert_eq!(got.commit_sha, git(&remote.work, &["rev-parse", "HEAD"]));
    assert_eq!(got.dir, repos.path().join("_gitlab").join("acme+widgets"));
    assert!(got.dir.join(".git").exists());
    assert!(got.dir.join("first.txt").exists());
}

#[test]
fn second_call_fetches_new_commit() {
    let remote = Remote::new();
    let repos = TempDir::new("repos");
    let r = RepoRef::from_snapshot("acme/widgets", Some(&remote.url()));
    let c = cache(repos.path());
    let first = c.ensure_fresh(&r, "main").unwrap();
    let newer = remote.commit("second");
    assert_ne!(first.commit_sha, newer);
    let second = c.ensure_fresh(&r, "main").unwrap();
    assert_eq!(second.commit_sha, newer);
    assert!(second.dir.join("second.txt").exists());
}

#[test]
fn origin_url_has_no_credentials() {
    let remote = Remote::new();
    let repos = TempDir::new("repos");
    let r = RepoRef::from_snapshot("acme/widgets", Some(&remote.url()));
    let mut c = cache(repos.path());
    c.gitlab_token = Some("tok-123".into());
    let got = c.ensure_fresh(&r, "main").unwrap();
    let config = std::fs::read_to_string(got.dir.join(".git").join("config")).unwrap();
    assert!(!config.contains("oauth2:"), "{config}");
    assert!(!config.contains("tok-123"), "{config}");
}

#[test]
fn writes_lock_and_last_used_files() {
    let remote = Remote::new();
    let repos = TempDir::new("repos");
    let r = RepoRef::from_snapshot("acme/widgets", Some(&remote.url()));
    cache(repos.path()).ensure_fresh(&r, "main").unwrap();
    let base = repos.path().join("_gitlab");
    assert!(base.join("acme+widgets.lock").is_file());
    let stamp = std::fs::read_to_string(base.join("acme+widgets.last-used")).unwrap();
    // Java writes Instant.now().toString(): UTC, ends with Z.
    assert!(
        stamp.len() > 19 && stamp.contains('T') && stamp.ends_with('Z'),
        "{stamp}"
    );
    time::OffsetDateTime::parse(&stamp, &time::format_description::well_known::Rfc3339)
        .expect("last-used is an ISO timestamp");
}

#[test]
fn lock_held_elsewhere_times_out() {
    let repos = TempDir::new("repos");
    let r = RepoRef::from_snapshot(
        "acme/widgets",
        Some("https://git.example.test/acme/widgets.git"),
    );
    let lock_path = repos.path().join("_gitlab").join("acme+widgets.lock");
    std::fs::create_dir_all(lock_path.parent().unwrap()).unwrap();
    let holder = File::options()
        .create(true)
        .write(true)
        .truncate(false)
        .open(&lock_path)
        .unwrap();
    holder.try_lock().expect("test takes the lock first");

    let mut c = cache(repos.path());
    c.lock_wait = Duration::from_millis(300);
    let err = c.ensure_fresh(&r, "main").unwrap_err();
    assert!(err.contains("repo lock timeout"), "{err}");
    assert!(err.contains("_gitlab/acme+widgets"), "{err}");
    assert!(!repos.path().join("_gitlab").join("acme+widgets").exists());
    drop(holder);
}

#[test]
fn git_failure_message_is_masked() {
    let repos = TempDir::new("repos");
    let r = RepoRef::from_snapshot(
        "acme/widgets",
        Some("https://oauth2:secret@127.0.0.1:1/x.git"),
    );
    let err = cache(repos.path()).ensure_fresh(&r, "nope").unwrap_err();
    assert!(err.starts_with("git failed ("), "{err}");
    assert!(!err.contains("secret"), "{err}");
    // Real git already strips the password from its own messages, so there is no `***@` to
    // see here; the masking of echoed command lines is covered by the timeout test below.
}

#[test]
fn timeout_message_masks_the_command() {
    let repos = TempDir::new("repos");
    let r = RepoRef::from_snapshot(
        "acme/widgets",
        Some("https://oauth2:secret@127.0.0.1:1/x.git"),
    );
    let mut c = cache(repos.path());
    c.git_timeout = Duration::from_millis(1);
    let err = c.ensure_fresh(&r, "main").unwrap_err();
    assert!(err.starts_with("process timeout ("), "{err}");
    assert!(!err.contains("secret"), "{err}");
    assert!(err.contains("***@"), "{err}");
}

#[test]
fn rev_parse_failure_is_error() {
    // A repository with no commits: `git rev-parse HEAD` prints "HEAD" but exits non-zero.
    let dir = TempDir::new("empty-repo");
    git(dir.path(), &["init", "-b", "main"]);
    let c = cache(dir.path());
    let err = c.rev_parse_head(dir.path()).unwrap_err();
    assert!(err.starts_with("git failed ("), "{err}");
}
