//! Values shared with the Java side, embedded from `worker-rs/contract/`.

use serde::Deserialize;
use std::collections::BTreeMap;
use std::sync::OnceLock;

pub const ANALYSIS_PROMPT: &str = include_str!("../contract/analysis-prompt.txt");
const CONSTANTS_JSON: &str = include_str!("../contract/constants.json");

/// Task kinds this program can process (phase 1).
pub const IMPLEMENTED_KINDS: &[&str] = &["ANALYSIS"];

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Constants {
    secret_env_names: Vec<String>,
    task_statuses: BTreeMap<String, String>,
    kinds: Vec<String>,
}

fn constants() -> &'static Constants {
    static C: OnceLock<Constants> = OnceLock::new();
    C.get_or_init(|| {
        serde_json::from_str(CONSTANTS_JSON).expect("embedded constants.json is valid")
    })
}

/// Env names removed from child processes and never logged.
pub fn secret_env_names() -> &'static [String] {
    &constants().secret_env_names
}

/// Korean display name of a task status constant (`COMPLETED` -> `분석완료`).
pub fn status_ko(name: &str) -> Option<&'static str> {
    constants().task_statuses.get(name).map(String::as_str)
}

/// Every task kind the server knows.
pub fn known_kinds() -> &'static [String] {
    &constants().kinds
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn secret_names_include_worker_api_key() {
        assert!(secret_env_names().iter().any(|n| n == "WORKER_API_KEY"));
    }

    #[test]
    fn status_ko_completed_is_분석완료() {
        assert_eq!(status_ko("COMPLETED"), Some("분석완료"));
        assert_eq!(status_ko("NOPE"), None);
    }

    /// `analysis.rs` hard-codes these two status names in every result it builds.
    #[test]
    fn status_names_used_by_analysis_exist() {
        assert!(status_ko("FAILED").is_some());
        assert!(status_ko("COMPLETED").is_some());
    }

    #[test]
    fn prompt_has_all_placeholders() {
        for p in [
            "{github_repo}",
            "{github_branch}",
            "{commit_sha}",
            "{title}",
            "{description}",
        ] {
            assert!(ANALYSIS_PROMPT.contains(p), "missing {p}");
        }
    }

    #[test]
    fn embedded_contract_text_has_no_carriage_return() {
        assert!(!ANALYSIS_PROMPT.contains('\r'));
        assert!(!CONSTANTS_JSON.contains('\r'));
    }

    #[test]
    fn implemented_kinds_are_known() {
        for k in IMPLEMENTED_KINDS {
            assert!(known_kinds().iter().any(|n| n == k));
        }
    }
}
