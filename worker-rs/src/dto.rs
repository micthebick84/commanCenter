//! JSON bodies exchanged with the API (camelCase on the wire).
//!
//! Requests omit null fields; responses may carry nulls and unknown fields, which are ignored.

use crate::mask::mask;
use serde::{Deserialize, Deserializer, Serialize};
use std::collections::BTreeMap;

fn null_as_default<'de, D, T>(d: D) -> Result<T, D::Error>
where
    D: Deserializer<'de>,
    T: Deserialize<'de> + Default,
{
    Ok(Option::<T>::deserialize(d)?.unwrap_or_default())
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct McpSpec {
    pub name: String,
    pub url: String,
    pub transport: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub headers: Option<BTreeMap<String, String>>,
}

/// A claimed task. `kind` stays a plain string so an unknown kind still deserializes.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskClaim {
    pub id: i64,
    pub github_repo: String,
    pub github_branch: String,
    pub title: String,
    #[serde(default)]
    pub description: Option<String>,
    pub kind: String,
    #[serde(default, deserialize_with = "null_as_default")]
    pub mcps_extra: Vec<McpSpec>,
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default)]
    pub effort: Option<String>,
    #[serde(default)]
    pub git_url: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Usage {
    pub cost_usd: f64,
    pub input_tokens: i64,
    pub output_tokens: i64,
    pub cache_creation_tokens: i64,
    pub cache_read_tokens: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResultRequest {
    pub worker_id: String,
    pub status: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub markdown_result: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub subtasks_json: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub claude_log: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub duration_ms: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub failure_reason: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub usage: Option<Usage>,
}

impl ResultRequest {
    /// Masks credentials in every free-text field.
    pub fn masked(mut self) -> ResultRequest {
        for text in [
            &mut self.markdown_result,
            &mut self.subtasks_json,
            &mut self.claude_log,
            &mut self.failure_reason,
        ]
        .into_iter()
        .flatten()
        {
            *text = mask(text);
        }
        self
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Heartbeat {
    pub worker_id: String,
    pub hostname: String,
    pub version: String,
    pub claude_session_ok: bool,
    pub vpn_status: String,
    pub mcps: Vec<String>,
    pub lost_report_count: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub claude_auth_method: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub claude_auth_expires_at: Option<String>,
    pub dead_report_count: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StartupRequest {
    pub pending_task_ids: Vec<i64>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{Value, json};

    fn contract(raw: &str) -> Value {
        serde_json::from_str(raw).unwrap()
    }

    #[test]
    fn claim_analysis_deserializes() {
        let claim: TaskClaim =
            serde_json::from_str(include_str!("../contract/claim-analysis.json")).unwrap();
        assert_eq!(claim.id, 1);
        assert_eq!(claim.kind, "ANALYSIS");
        assert_eq!(claim.github_repo, "acme/widgets");
        assert_eq!(claim.mcps_extra.len(), 1);
        assert_eq!(claim.mcps_extra[0].transport, "http");
        assert_eq!(claim.git_url, None);
        assert_eq!(claim.model.as_deref(), Some("sonnet"));
    }

    #[test]
    fn unknown_kind_and_fields_still_deserialize() {
        let claim: TaskClaim = serde_json::from_value(json!({
            "id": 9, "githubRepo": "a/b", "githubBranch": "main", "title": "t",
            "description": null, "kind": "FUTURE_KIND", "mcpsExtra": null,
            "model": null, "effort": null, "gitUrl": "https://example.com/a/b.git",
            "somethingNew": {"x": 1}
        }))
        .unwrap();
        assert_eq!(claim.kind, "FUTURE_KIND");
        assert!(claim.mcps_extra.is_empty());
        assert_eq!(claim.description, None);
        assert_eq!(
            claim.git_url.as_deref(),
            Some("https://example.com/a/b.git")
        );
    }

    fn ok_request() -> ResultRequest {
        ResultRequest {
            worker_id: "w-1".into(),
            status: "COMPLETED".into(),
            markdown_result: Some("## 1. 요구사항 요약\n내용".into()),
            subtasks_json: Some("[]".into()),
            claude_log: None,
            duration_ms: Some(1234),
            failure_reason: None,
            usage: Some(Usage {
                cost_usd: 0.12,
                input_tokens: 100,
                output_tokens: 50,
                cache_creation_tokens: 0,
                cache_read_tokens: 0,
            }),
        }
    }

    #[test]
    fn result_ok_matches_contract() {
        let got = serde_json::to_value(ok_request()).unwrap();
        assert_eq!(
            got,
            contract(include_str!("../contract/result-analysis-ok.json"))
        );
    }

    #[test]
    fn result_failed_matches_contract() {
        let req = ResultRequest {
            worker_id: "w-1".into(),
            status: "FAILED".into(),
            markdown_result: None,
            subtasks_json: None,
            claude_log: None,
            duration_ms: Some(500),
            failure_reason: Some("분석 시간 초과".into()),
            usage: None,
        };
        let got = serde_json::to_value(req).unwrap();
        assert_eq!(
            got,
            contract(include_str!("../contract/result-analysis-failed.json"))
        );
    }

    #[test]
    fn heartbeat_matches_contract() {
        let hb = Heartbeat {
            worker_id: "w-1".into(),
            hostname: "host-a".into(),
            version: "0.1.0".into(),
            claude_session_ok: true,
            vpn_status: "UP".into(),
            mcps: vec!["docs".into()],
            lost_report_count: 0,
            claude_auth_method: Some("env-token".into()),
            claude_auth_expires_at: Some("2026-12-31T00:00:00Z".into()),
            dead_report_count: 0,
        };
        let got = serde_json::to_value(hb).unwrap();
        assert_eq!(got, contract(include_str!("../contract/heartbeat.json")));
    }

    #[test]
    fn startup_request_matches_contract() {
        let got = serde_json::to_value(StartupRequest {
            pending_task_ids: vec![3, 7],
        })
        .unwrap();
        assert_eq!(
            got,
            contract(include_str!("../contract/startup-request.json"))
        );
    }

    #[test]
    fn masked_hides_credentials_in_all_text_fields() {
        let secret = "https://oauth2:tok123@host/x.git";
        let req = ResultRequest {
            worker_id: "w-1".into(),
            status: "FAILED".into(),
            markdown_result: Some(format!("a {secret}")),
            subtasks_json: Some(format!("b {secret}")),
            claude_log: Some(format!("c {secret}")),
            duration_ms: Some(1),
            failure_reason: Some(format!("d {secret}")),
            usage: None,
        }
        .masked();
        let text = serde_json::to_string(&req).unwrap();
        assert!(!text.contains("tok123"), "{text}");
        assert_eq!(
            req.markdown_result.as_deref(),
            Some("a https://***@host/x.git")
        );
        assert_eq!(
            req.failure_reason.as_deref(),
            Some("d https://***@host/x.git")
        );
    }

    #[test]
    fn masked_keeps_absent_fields_absent() {
        let masked = ok_request().masked();
        assert_eq!(masked.claude_log, None);
        assert_eq!(masked, ok_request());
    }
}
