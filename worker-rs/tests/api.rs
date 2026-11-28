//! `api` tests against an in-process fake server.

mod common;

use common::http::{FakeServer, refused_base_url};
use netis_worker_rs::api::{ApiClient, ApiError};
use netis_worker_rs::dto::{Heartbeat, ResultRequest, Usage};
use serde_json::Value;

const KEY: &str = "secret-key-123";

fn client(base: &str) -> ApiClient {
    ApiClient::new(base, KEY, "w1", &["ANALYSIS".to_string()])
}

fn heartbeat() -> Heartbeat {
    Heartbeat {
        worker_id: "w1".into(),
        hostname: "h".into(),
        version: "0.1.0".into(),
        claude_session_ok: true,
        vpn_status: "UP".into(),
        mcps: vec![],
        lost_report_count: 0,
        claude_auth_method: None,
        claude_auth_expires_at: None,
        dead_report_count: 0,
    }
}

fn failed_result() -> ResultRequest {
    ResultRequest {
        worker_id: "w1".into(),
        status: "FAILED".into(),
        markdown_result: None,
        subtasks_json: None,
        claude_log: None,
        duration_ms: None,
        failure_reason: Some("x".into()),
        usage: None,
    }
}

fn json(raw: &str) -> Value {
    serde_json::from_str(raw).unwrap()
}

#[test]
fn every_request_has_api_key_header() {
    let srv = FakeServer::start(|r| {
        if r.url.contains("next-task") {
            (204, String::new())
        } else {
            (200, "{}".into())
        }
    });
    let c = client(&srv.base_url);
    c.heartbeat(&heartbeat()).unwrap();
    c.startup(&[1]).unwrap();
    c.next_task().unwrap();
    c.post_result(5, &failed_result()).unwrap();
    let reqs = srv.requests();
    assert_eq!(reqs.len(), 4);
    for q in reqs {
        assert_eq!(q.header("X-Worker-API-Key"), Some(KEY), "{}", q.url);
    }
}

#[test]
fn next_task_url_always_has_kinds() {
    let srv = FakeServer::start(|_| (204, String::new()));
    client(&srv.base_url).next_task().unwrap();
    let reqs = srv.requests();
    assert_eq!(reqs[0].method, "GET");
    assert_eq!(reqs[0].url, "/worker/next-task?workerId=w1&kinds=ANALYSIS");
    assert!(!reqs[0].url.contains("supports"));
}

#[test]
fn next_task_joins_several_kinds_with_literal_comma() {
    let srv = FakeServer::start(|_| (204, String::new()));
    let c = ApiClient::new(
        &srv.base_url,
        KEY,
        "w1",
        &["IMPLEMENTATION".to_string(), "DESIGN".to_string()],
    );
    c.next_task().unwrap();
    assert_eq!(
        srv.requests()[0].url,
        "/worker/next-task?workerId=w1&kinds=IMPLEMENTATION,DESIGN"
    );
}

#[test]
fn worker_id_is_percent_encoded_in_query() {
    let srv = FakeServer::start(|_| (204, String::new()));
    let c = ApiClient::new(&srv.base_url, KEY, "a b&c", &["ANALYSIS".to_string()]);
    c.next_task().unwrap();
    assert_eq!(
        srv.requests()[0].url,
        "/worker/next-task?workerId=a%20b%26c&kinds=ANALYSIS"
    );
}

#[test]
fn base_url_trailing_slash_is_tolerated() {
    let srv = FakeServer::start(|_| (204, String::new()));
    client(&format!("{}/", srv.base_url)).next_task().unwrap();
    assert!(srv.requests()[0].url.starts_with("/worker/next-task?"));
}

#[test]
fn next_task_204_is_none() {
    let srv = FakeServer::start(|_| (204, String::new()));
    assert_eq!(client(&srv.base_url).next_task().unwrap(), None);
}

#[test]
fn next_task_200_empty_body_is_none() {
    let srv = FakeServer::start(|_| (200, String::new()));
    assert_eq!(client(&srv.base_url).next_task().unwrap(), None);
}

#[test]
fn next_task_200_parses_contract_claim() {
    let srv = FakeServer::start(|_| {
        (
            200,
            include_str!("../contract/claim-analysis.json").to_string(),
        )
    });
    let claim = client(&srv.base_url).next_task().unwrap().unwrap();
    assert_eq!(claim.id, 1);
    assert_eq!(claim.kind, "ANALYSIS");
    assert_eq!(claim.github_repo, "acme/widgets");
}

#[test]
fn next_task_garbage_body_is_error() {
    let srv = FakeServer::start(|_| (200, "not json".into()));
    assert!(client(&srv.base_url).next_task().is_err());
}

#[test]
fn startup_ok_for_200_empty_and_404() {
    for (status, body) in [
        (200, include_str!("../contract/startup-response.json")),
        (200, ""),
        (204, ""),
        (404, "{\"error\":\"nope\"}"),
    ] {
        let srv = FakeServer::start(move |_| (status, body.to_string()));
        client(&srv.base_url)
            .startup(&[3, 7])
            .unwrap_or_else(|e| panic!("{status}: {e}"));
        let reqs = srv.requests();
        assert_eq!(reqs[0].method, "POST");
        assert_eq!(reqs[0].url, "/worker/startup?workerId=w1");
        assert_eq!(json(&reqs[0].body), json("{\"pendingTaskIds\":[3,7]}"));
    }
}

#[test]
fn startup_err_for_401_and_500_and_refused() {
    for status in [401, 403, 500, 503] {
        let srv = FakeServer::start(move |_| (status, "no".into()));
        let err = client(&srv.base_url).startup(&[]).unwrap_err();
        match (status, err) {
            (401 | 403, ApiError::Client { status: s, .. }) => assert_eq!(s, status),
            (500 | 503, ApiError::Server { status: s, .. }) => assert_eq!(s, status),
            (_, other) => panic!("{status}: {other:?}"),
        }
    }
    let err = client(&refused_base_url()).startup(&[]).unwrap_err();
    assert!(matches!(err, ApiError::Transport(_)), "{err:?}");
}

#[test]
fn heartbeat_body_matches_contract() {
    let srv = FakeServer::start(|_| (200, String::new()));
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
    client(&srv.base_url).heartbeat(&hb).unwrap();
    let reqs = srv.requests();
    assert_eq!(reqs[0].url, "/worker/heartbeat");
    assert_eq!(
        json(&reqs[0].body),
        json(include_str!("../contract/heartbeat.json"))
    );
    assert!(
        reqs[0]
            .header("Content-Type")
            .unwrap()
            .starts_with("application/json")
    );
}

#[test]
fn post_result_body_matches_contract_shape() {
    let srv = FakeServer::start(|_| (200, String::new()));
    let r = ResultRequest {
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
    };
    client(&srv.base_url).post_result(42, &r).unwrap();
    let reqs = srv.requests();
    assert_eq!(reqs[0].method, "POST");
    assert_eq!(reqs[0].url, "/worker/tasks/42/result");
    assert_eq!(
        json(&reqs[0].body),
        json(include_str!("../contract/result-analysis-ok.json"))
    );
}

#[test]
fn client_and_server_errors_are_distinguished() {
    let srv = FakeServer::start(|r| {
        if r.url.contains("/tasks/1/") {
            (409, "stale".into())
        } else {
            (502, "bad gateway".into())
        }
    });
    let c = client(&srv.base_url);
    let r = failed_result();
    match c.post_result(1, &r).unwrap_err() {
        ApiError::Client { status, body } => {
            assert_eq!(status, 409);
            assert_eq!(body, "stale");
        }
        other => panic!("{other:?}"),
    }
    assert!(matches!(
        c.post_result(2, &r).unwrap_err(),
        ApiError::Server { status: 502, .. }
    ));
}

#[test]
fn errors_never_contain_the_api_key() {
    let srv = FakeServer::start(|_| (500, "boom".into()));
    let refused = client(&refused_base_url()).startup(&[]).unwrap_err();
    let texts = [
        client(&srv.base_url).startup(&[]).unwrap_err().to_string(),
        refused.to_string(),
        format!("{refused:?}"),
        format!("{:?}", client(&srv.base_url).next_task().unwrap_err()),
    ];
    for t in texts {
        assert!(!t.contains(KEY), "{t}");
    }
}
