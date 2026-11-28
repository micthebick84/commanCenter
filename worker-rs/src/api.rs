//! HTTP client for the worker API (`/worker/*`), ported from `WorkerHttpClient.java`.
//!
//! HTTP status codes are classified here (4xx / 5xx) rather than turned into transport errors.
//! No error value or log line ever contains the API key.

use crate::dto::{Heartbeat, ResultRequest, StartupRequest, TaskClaim};
use crate::log;
use crate::mask::mask;
use std::fmt;
use std::time::Duration;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(60);
/// Largest response body read (claims carry a description and attachments' text paths only).
const MAX_BODY_BYTES: u64 = 16 * 1024 * 1024;
/// Error bodies are kept short; they end up in logs and dead-letter reasons.
const MAX_ERROR_BODY_CHARS: usize = 500;

#[derive(Debug, Clone, PartialEq)]
pub enum ApiError {
    /// 4xx: the request itself was refused; retrying the same request is pointless.
    Client { status: u16, body: String },
    /// 5xx (or any other non-success status): the server is unwell; retrying may help.
    Server { status: u16, body: String },
    /// Connection, timeout, TLS-less protocol or body-decoding problems.
    Transport(String),
}

impl fmt::Display for ApiError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ApiError::Client { status, body } => write!(f, "HTTP {status} (client error): {body}"),
            ApiError::Server { status, body } => write!(f, "HTTP {status} (server error): {body}"),
            ApiError::Transport(msg) => write!(f, "transport error: {msg}"),
        }
    }
}

impl std::error::Error for ApiError {}

/// What came back, before the caller decides what the status means.
struct Reply {
    status: u16,
    body: String,
}

pub struct ApiClient {
    agent: ureq::Agent,
    base_url: String,
    api_key: String,
    worker_id: String,
    kinds: Vec<String>,
}

fn truncate_chars(s: &str, max: usize) -> String {
    match s.char_indices().nth(max) {
        Some((idx, _)) => format!("{}...", &s[..idx]),
        None => s.to_string(),
    }
}

/// Percent-encodes everything but RFC 3986 unreserved characters.
fn encode_component(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

impl ApiClient {
    pub fn new(base_url: &str, api_key: &str, worker_id: &str, kinds: &[String]) -> ApiClient {
        // Redirects are never followed: a 3xx comes back as a response and is classified as a
        // server error, so a result is retried / dead-lettered instead of being "delivered" to
        // another host (with the API key header) or turned from POST into GET.
        let config = ureq::Agent::config_builder()
            .http_status_as_error(false)
            .max_redirects(0)
            .timeout_connect(Some(CONNECT_TIMEOUT))
            .timeout_global(Some(REQUEST_TIMEOUT))
            .build();
        ApiClient {
            agent: config.into(),
            base_url: base_url.trim_end_matches('/').to_string(),
            api_key: api_key.to_string(),
            worker_id: worker_id.to_string(),
            kinds: kinds.to_vec(),
        }
    }

    fn url(&self, path_and_query: &str) -> String {
        format!("{}{}", self.base_url, path_and_query)
    }

    fn read_reply(mut resp: ureq::http::Response<ureq::Body>) -> Result<Reply, ApiError> {
        let status = resp.status().as_u16();
        let body = resp
            .body_mut()
            .with_config()
            .limit(MAX_BODY_BYTES)
            .read_to_string()
            .map_err(|e| ApiError::Transport(format!("reading response body: {e}")))?;
        Ok(Reply { status, body })
    }

    fn get(&self, path_and_query: &str) -> Result<Reply, ApiError> {
        let resp = self
            .agent
            .get(self.url(path_and_query))
            .header("X-Worker-API-Key", &self.api_key)
            .call()
            .map_err(|e| ApiError::Transport(e.to_string()))?;
        Self::read_reply(resp)
    }

    fn post_json<T: serde::Serialize>(
        &self,
        path_and_query: &str,
        body: &T,
    ) -> Result<Reply, ApiError> {
        let resp = self
            .agent
            .post(self.url(path_and_query))
            .header("X-Worker-API-Key", &self.api_key)
            .send_json(body)
            .map_err(|e| ApiError::Transport(e.to_string()))?;
        Self::read_reply(resp)
    }

    /// 2xx is success; 4xx and everything else are classified.
    fn require_success(reply: Reply) -> Result<Reply, ApiError> {
        match reply.status {
            200..=299 => Ok(reply),
            status => Err(Self::classify(status, reply.body)),
        }
    }

    fn classify(status: u16, body: String) -> ApiError {
        // Masked here so no log line or dead-letter reason built from it can carry credentials.
        let body = truncate_chars(&mask(&body), MAX_ERROR_BODY_CHARS);
        if (400..500).contains(&status) {
            ApiError::Client { status, body }
        } else {
            ApiError::Server { status, body }
        }
    }

    pub fn heartbeat(&self, hb: &Heartbeat) -> Result<(), ApiError> {
        Self::require_success(self.post_json("/worker/heartbeat", hb)?).map(|_| ())
    }

    /// Tells the API which task ids this worker still holds. A 404 means an API that does not
    /// know the endpoint yet (old version) and counts as success; everything else that is not a
    /// success is an error so the caller retries and does not claim meanwhile.
    pub fn startup(&self, pending: &[i64]) -> Result<(), ApiError> {
        let path = format!(
            "/worker/startup?workerId={}",
            encode_component(&self.worker_id)
        );
        let body = StartupRequest {
            pending_task_ids: pending.to_vec(),
        };
        let reply = self.post_json(&path, &body)?;
        if reply.status == 404 {
            log::warn(
                "startup notice: the API does not know /worker/startup (404, older API) - continuing",
            );
            return Ok(());
        }
        Self::require_success(reply).map(|_| ())
    }

    /// Next claimable task, or `None` when there is nothing to do (204 or an empty body).
    /// An empty kind list is refused before anything is sent: a request without a `kinds`
    /// filter would let the server hand out a task of any kind.
    pub fn next_task(&self) -> Result<Option<TaskClaim>, ApiError> {
        if self.kinds.is_empty() {
            return Err(ApiError::Transport(
                "refusing to poll without a kind filter (no kinds configured)".to_string(),
            ));
        }
        let kinds = self
            .kinds
            .iter()
            .map(|k| encode_component(k))
            .collect::<Vec<_>>()
            .join(",");
        let path = format!(
            "/worker/next-task?workerId={}&kinds={}",
            encode_component(&self.worker_id),
            kinds
        );
        let reply = self.get(&path)?;
        if reply.status == 204 {
            return Ok(None);
        }
        let reply = Self::require_success(reply)?;
        if reply.body.trim().is_empty() {
            return Ok(None);
        }
        serde_json::from_str::<TaskClaim>(&reply.body)
            .map(Some)
            .map_err(|e| ApiError::Transport(format!("invalid claim body: {e}")))
    }

    pub fn post_result(&self, task_id: i64, r: &ResultRequest) -> Result<(), ApiError> {
        let path = format!("/worker/tasks/{task_id}/result");
        Self::require_success(self.post_json(&path, r)?).map(|_| ())
    }
}

impl crate::report::ResultSink for ApiClient {
    fn post_result(&self, task_id: i64, r: &ResultRequest) -> Result<(), ApiError> {
        ApiClient::post_result(self, task_id, r)
    }
}
