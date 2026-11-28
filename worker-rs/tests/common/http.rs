//! In-process fake API: `tiny_http` on 127.0.0.1 with an OS-assigned port. Never the real API.
#![allow(dead_code)]

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::Duration;

#[derive(Debug, Clone)]
pub struct Recorded {
    pub method: String,
    /// Path and query exactly as sent.
    pub url: String,
    pub headers: Vec<(String, String)>,
    pub body: String,
}

impl Recorded {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.as_str())
    }
}

type Handler = dyn Fn(&Recorded) -> (u16, String) + Send + Sync;

pub struct FakeServer {
    pub base_url: String,
    requests: Arc<Mutex<Vec<Recorded>>>,
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl FakeServer {
    pub fn start(
        handler: impl Fn(&Recorded) -> (u16, String) + Send + Sync + 'static,
    ) -> FakeServer {
        let server = tiny_http::Server::http("127.0.0.1:0").expect("bind fake server");
        let port = server.server_addr().to_ip().expect("ip addr").port();
        let requests = Arc::new(Mutex::new(Vec::new()));
        let stop = Arc::new(AtomicBool::new(false));
        let handler: Arc<Handler> = Arc::new(handler);
        let (reqs, stop2) = (requests.clone(), stop.clone());
        let thread = std::thread::spawn(move || {
            while !stop2.load(Ordering::Relaxed) {
                let Ok(Some(mut req)) = server.recv_timeout(Duration::from_millis(20)) else {
                    continue;
                };
                let mut body = String::new();
                let _ = req.as_reader().read_to_string(&mut body);
                let rec = Recorded {
                    method: req.method().to_string(),
                    url: req.url().to_string(),
                    headers: req
                        .headers()
                        .iter()
                        .map(|h| (h.field.to_string(), h.value.to_string()))
                        .collect(),
                    body,
                };
                let (status, resp_body) = handler(&rec);
                reqs.lock().unwrap().push(rec);
                let resp = tiny_http::Response::from_string(resp_body)
                    .with_status_code(status)
                    .with_header(
                        tiny_http::Header::from_bytes("Content-Type", "application/json").unwrap(),
                    );
                let _ = req.respond(resp);
            }
        });
        FakeServer {
            base_url: format!("http://127.0.0.1:{port}"),
            requests,
            stop,
            thread: Some(thread),
        }
    }

    pub fn requests(&self) -> Vec<Recorded> {
        self.requests.lock().unwrap().clone()
    }
}

impl Drop for FakeServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}

/// A base URL nothing listens on (bind, remember the port, close).
pub fn refused_base_url() -> String {
    let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = l.local_addr().unwrap().port();
    drop(l);
    format!("http://127.0.0.1:{port}")
}
