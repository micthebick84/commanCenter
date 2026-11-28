//! A local bare repository plus a working copy, for tests that need a clonable remote.
#![allow(dead_code)]

use super::TempDir;
use std::path::{Path, PathBuf};
use std::process::Command;

pub fn git(dir: &Path, args: &[&str]) -> String {
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

pub struct Remote {
    _root: TempDir,
    pub bare: PathBuf,
    pub work: PathBuf,
}

impl Remote {
    pub fn new() -> Remote {
        let root = TempDir::new("remote");
        let bare = root.path().join("bare.git");
        let work = root.path().join("work");
        std::fs::create_dir_all(&bare).unwrap();
        std::fs::create_dir_all(&work).unwrap();
        git(&bare, &["init", "--bare", "-b", "main"]);
        git(&work, &["init", "-b", "main"]);
        git(&work, &["remote", "add", "origin", bare.to_str().unwrap()]);
        std::fs::write(work.join("first.txt"), "first").unwrap();
        git(&work, &["add", "."]);
        git(&work, &["commit", "-m", "first"]);
        git(&work, &["push", "origin", "main"]);
        Remote {
            _root: root,
            bare,
            work,
        }
    }

    pub fn url(&self) -> String {
        self.bare.to_str().unwrap().to_string()
    }

    pub fn head_sha(&self) -> String {
        git(&self.work, &["rev-parse", "HEAD"])
    }
}
