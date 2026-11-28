//! MCP config merging (Java `WorkerMcpSupport`).
//!
//! The top-level `mcpServers` of `~/.claude.json` and every `projects.*.mcpServers` are
//! merged (same name: the later definition wins, the first position is kept), written to
//! one file, and handed to `claude` via `--mcp-config … --strict-mcp-config --allowedTools …`.
//! A task's extra servers are overlaid in a temporary file the caller deletes after the run.

use crate::dto::McpSpec;
use crate::log;
use serde::Deserialize;
use serde::de::{Deserializer, IgnoredAny, MapAccess, Visitor};
use serde_json::{Map, Value, json};
use std::fmt;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU32, Ordering};

type Servers = Vec<(String, Value)>;

/// Arguments for one `claude` run, plus the temporary config file to delete afterwards.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct McpArgs {
    pub args: Vec<String>,
    pub temp_file: Option<PathBuf>,
}

#[derive(Debug, Clone)]
pub struct McpSupport {
    out_file: Option<PathBuf>,
    servers: Servers,
    names: Vec<String>,
    temp_dir: PathBuf,
}

/// A JSON object read with its key order; anything that is not an object reads as empty
/// (Java `JsonNode.path` on a non-object yields a missing node).
#[derive(Default)]
struct Ordered(Servers);

impl<'de> Deserialize<'de> for Ordered {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        struct V;
        impl<'de> Visitor<'de> for V {
            type Value = Ordered;
            fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
                f.write_str("any JSON value")
            }
            fn visit_map<A: MapAccess<'de>>(self, mut m: A) -> Result<Ordered, A::Error> {
                let mut out = Servers::new();
                while let Some((k, v)) = m.next_entry::<String, Value>()? {
                    upsert(&mut out, k, v);
                }
                Ok(Ordered(out))
            }
            fn visit_seq<A: serde::de::SeqAccess<'de>>(
                self,
                mut s: A,
            ) -> Result<Ordered, A::Error> {
                while s.next_element::<IgnoredAny>()?.is_some() {}
                Ok(Ordered::default())
            }
            fn visit_bool<E>(self, _: bool) -> Result<Ordered, E> {
                Ok(Ordered::default())
            }
            fn visit_i64<E>(self, _: i64) -> Result<Ordered, E> {
                Ok(Ordered::default())
            }
            fn visit_u64<E>(self, _: u64) -> Result<Ordered, E> {
                Ok(Ordered::default())
            }
            fn visit_f64<E>(self, _: f64) -> Result<Ordered, E> {
                Ok(Ordered::default())
            }
            fn visit_str<E>(self, _: &str) -> Result<Ordered, E> {
                Ok(Ordered::default())
            }
            fn visit_unit<E>(self) -> Result<Ordered, E> {
                Ok(Ordered::default())
            }
            fn visit_none<E>(self) -> Result<Ordered, E> {
                Ok(Ordered::default())
            }
        }
        d.deserialize_any(V)
    }
}

struct Node {
    servers: Servers,
    projects: Vec<Servers>,
}

impl<'de> Deserialize<'de> for Node {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        struct V;
        impl<'de> Visitor<'de> for V {
            type Value = Node;
            fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
                f.write_str("any JSON value")
            }
            fn visit_map<A: MapAccess<'de>>(self, mut m: A) -> Result<Node, A::Error> {
                let mut node = Node {
                    servers: Servers::new(),
                    projects: Vec::new(),
                };
                while let Some(key) = m.next_key::<String>()? {
                    match key.as_str() {
                        "mcpServers" => node.servers = m.next_value::<Ordered>()?.0,
                        "projects" => node.projects = m.next_value::<Projects>()?.0,
                        _ => {
                            m.next_value::<IgnoredAny>()?;
                        }
                    }
                }
                Ok(node)
            }
            fn visit_seq<A: serde::de::SeqAccess<'de>>(self, mut s: A) -> Result<Node, A::Error> {
                while s.next_element::<IgnoredAny>()?.is_some() {}
                Ok(Node {
                    servers: Vec::new(),
                    projects: Vec::new(),
                })
            }
            fn visit_bool<E>(self, _: bool) -> Result<Node, E> {
                Ok(Node {
                    servers: Vec::new(),
                    projects: Vec::new(),
                })
            }
            fn visit_i64<E>(self, _: i64) -> Result<Node, E> {
                Ok(Node {
                    servers: Vec::new(),
                    projects: Vec::new(),
                })
            }
            fn visit_u64<E>(self, _: u64) -> Result<Node, E> {
                Ok(Node {
                    servers: Vec::new(),
                    projects: Vec::new(),
                })
            }
            fn visit_f64<E>(self, _: f64) -> Result<Node, E> {
                Ok(Node {
                    servers: Vec::new(),
                    projects: Vec::new(),
                })
            }
            fn visit_str<E>(self, _: &str) -> Result<Node, E> {
                Ok(Node {
                    servers: Vec::new(),
                    projects: Vec::new(),
                })
            }
            fn visit_unit<E>(self) -> Result<Node, E> {
                Ok(Node {
                    servers: Vec::new(),
                    projects: Vec::new(),
                })
            }
        }
        d.deserialize_any(V)
    }
}

/// `projects`: each member's `mcpServers`, in key order; a non-object `projects` is empty.
struct Projects(Vec<Servers>);

impl<'de> Deserialize<'de> for Projects {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        struct V;
        impl<'de> Visitor<'de> for V {
            type Value = Projects;
            fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
                f.write_str("any JSON value")
            }
            fn visit_map<A: MapAccess<'de>>(self, mut m: A) -> Result<Projects, A::Error> {
                let mut out = Vec::new();
                while let Some((_, node)) = m.next_entry::<IgnoredAny, Node>()? {
                    out.push(node.servers);
                }
                Ok(Projects(out))
            }
            fn visit_seq<A: serde::de::SeqAccess<'de>>(
                self,
                mut s: A,
            ) -> Result<Projects, A::Error> {
                while s.next_element::<IgnoredAny>()?.is_some() {}
                Ok(Projects(Vec::new()))
            }
            fn visit_bool<E>(self, _: bool) -> Result<Projects, E> {
                Ok(Projects(Vec::new()))
            }
            fn visit_i64<E>(self, _: i64) -> Result<Projects, E> {
                Ok(Projects(Vec::new()))
            }
            fn visit_u64<E>(self, _: u64) -> Result<Projects, E> {
                Ok(Projects(Vec::new()))
            }
            fn visit_f64<E>(self, _: f64) -> Result<Projects, E> {
                Ok(Projects(Vec::new()))
            }
            fn visit_str<E>(self, _: &str) -> Result<Projects, E> {
                Ok(Projects(Vec::new()))
            }
            fn visit_unit<E>(self) -> Result<Projects, E> {
                Ok(Projects(Vec::new()))
            }
        }
        d.deserialize_any(V)
    }
}

/// Java `ObjectNode.set`: an existing key keeps its position and takes the new value.
fn upsert(list: &mut Servers, key: String, value: Value) {
    match list.iter_mut().find(|(k, _)| *k == key) {
        Some(slot) => slot.1 = value,
        None => list.push((key, value)),
    }
}

fn wrapper(servers: &Servers) -> Value {
    let map: Map<String, Value> = servers.iter().cloned().collect();
    json!({ "mcpServers": Value::Object(map) })
}

fn assemble_args(config: &Path, names: &[String]) -> Vec<String> {
    let allowed: Vec<String> = names.iter().map(|n| format!("mcp__{n}")).collect();
    vec![
        "--mcp-config".to_string(),
        config.to_string_lossy().into_owned(),
        "--strict-mcp-config".to_string(),
        "--allowedTools".to_string(),
        allowed.join(","),
    ]
}

fn write_pretty(path: &Path, value: &Value) -> std::io::Result<()> {
    let text = serde_json::to_string_pretty(value).map_err(std::io::Error::other)?;
    let mut opts = std::fs::OpenOptions::new();
    opts.write(true).create(true).truncate(true);
    private_mode(&mut opts);
    let mut file = opts.open(path)?;
    #[cfg(unix)]
    {
        // `mode` only applies when the file is created; fix a pre-existing file too
        use std::os::unix::fs::PermissionsExt;
        file.set_permissions(std::fs::Permissions::from_mode(0o600))?;
    }
    std::io::Write::write_all(&mut file, text.as_bytes())
}

/// Owner-only (0600) on unix, like Java `Files.createTempFile`; the files can carry
/// per-task credentials in `headers`. Windows keeps the default (per-user temp ACL).
#[cfg(unix)]
fn private_mode(opts: &mut std::fs::OpenOptions) {
    use std::os::unix::fs::OpenOptionsExt;
    opts.mode(0o600);
}

#[cfg(not(unix))]
fn private_mode(_opts: &mut std::fs::OpenOptions) {}

fn build_merged(claude_json: &Path, out_file: &Path) -> std::io::Result<Option<Servers>> {
    if !claude_json.exists() {
        log::info("~/.claude.json not found - skipping MCP merge");
        return Ok(None);
    }
    let text = std::fs::read_to_string(claude_json)?;
    // like Jackson `readTree`: the first value counts, trailing text is ignored
    let root = serde_json::Deserializer::from_str(&text)
        .into_iter::<Node>()
        .next()
        .transpose()
        .map_err(std::io::Error::other)?;
    let Some(root) = root else {
        return Ok(None);
    };
    let mut merged = Servers::new();
    for (k, v) in root.servers {
        upsert(&mut merged, k, v);
    }
    for project in root.projects {
        for (k, v) in project {
            upsert(&mut merged, k, v);
        }
    }
    if merged.is_empty() {
        log::info("no MCP servers registered - skipping MCP merge");
        return Ok(None);
    }
    if let Some(parent) = out_file.parent() {
        std::fs::create_dir_all(parent)?;
    }
    write_pretty(out_file, &wrapper(&merged))?;
    Ok(Some(merged))
}

impl McpSupport {
    /// Reads `claude_json`, writes the merged config to `out_file`. A missing or unreadable
    /// source (or a write failure) logs a warning and yields an empty state, never an error.
    pub fn init(claude_json: &Path, out_file: &Path) -> McpSupport {
        let (servers, out) = match build_merged(claude_json, out_file) {
            Ok(Some(servers)) => (servers, Some(out_file.to_path_buf())),
            Ok(None) => (Vec::new(), None),
            Err(e) => {
                log::warn(&format!(
                    "MCP config build failed (continuing without MCP): {e}"
                ));
                (Vec::new(), None)
            }
        };
        let names: Vec<String> = servers.iter().map(|(k, _)| k.clone()).collect();
        if !names.is_empty() {
            log::info(&format!("MCP merged: {} servers {:?}", names.len(), names));
        }
        McpSupport {
            out_file: out,
            servers,
            names,
            temp_dir: std::env::temp_dir(),
        }
    }

    /// Directory for per-task temporary configs (default: the system temp dir).
    pub fn with_temp_dir(mut self, dir: &Path) -> McpSupport {
        self.temp_dir = dir.to_path_buf();
        self
    }

    pub fn server_names(&self) -> &[String] {
        &self.names
    }

    /// Arguments for the merged base config only; empty when there is no server.
    pub fn base_args(&self) -> Vec<String> {
        match &self.out_file {
            Some(file) if !self.names.is_empty() => assemble_args(file, &self.names),
            _ => Vec::new(),
        }
    }

    /// Base config plus the task's extra servers (extras win on equal names) in a new
    /// temporary file the caller must delete. No extras: the base arguments, no file.
    pub fn args_for(&self, extras: &[McpSpec]) -> std::io::Result<McpArgs> {
        if extras.is_empty() {
            return Ok(McpArgs {
                args: self.base_args(),
                temp_file: None,
            });
        }
        let mut merged = self.servers.clone();
        let mut names = self.names.clone();
        for spec in extras {
            let mut server = Map::new();
            server.insert("type".into(), Value::String(spec.transport.clone()));
            server.insert("url".into(), Value::String(spec.url.clone()));
            if let Some(headers) = spec.headers.as_ref().filter(|h| !h.is_empty()) {
                server.insert("headers".into(), serde_json::to_value(headers)?);
            }
            upsert(&mut merged, spec.name.clone(), Value::Object(server));
            if !names.contains(&spec.name) {
                names.push(spec.name.clone());
            }
        }
        let (path, mut file) = create_temp(&self.temp_dir)?;
        let written = serde_json::to_string_pretty(&wrapper(&merged))
            .map_err(std::io::Error::other)
            .and_then(|text| std::io::Write::write_all(&mut file, text.as_bytes()));
        drop(file);
        if let Err(e) = written {
            let _ = std::fs::remove_file(&path);
            return Err(e);
        }
        log::info(&format!(
            "per-task MCP config: {} base + {} extra servers",
            self.names.len(),
            extras.len()
        ));
        Ok(McpArgs {
            args: assemble_args(&path, &names),
            temp_file: Some(path),
        })
    }
}

fn create_temp(dir: &Path) -> std::io::Result<(PathBuf, std::fs::File)> {
    static N: AtomicU32 = AtomicU32::new(0);
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    for _ in 0..16 {
        let path = dir.join(format!(
            "netismaker-mcp-{}-{}-{nanos}.json",
            std::process::id(),
            N.fetch_add(1, Ordering::Relaxed)
        ));
        let mut opts = std::fs::OpenOptions::new();
        opts.write(true).create_new(true);
        private_mode(&mut opts);
        match opts.open(&path) {
            Ok(f) => return Ok((path, f)),
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(e),
        }
    }
    Err(std::io::Error::other(
        "could not create a temporary MCP config file",
    ))
}
