//! `claude -p` executor (Java `ClaudeExecAdapter`): command line, stdin prompt, separate
//! stdout/stderr, timeout with process-tree kill, envelope parsing, stderr tail.

use crate::contract::secret_env_names;
use crate::dto::{McpSpec, Usage};
use crate::log;
use crate::mcp::{McpArgs, McpSupport};
use crate::proc;
use serde_json::Value;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::Duration;

/// Longest stderr tail appended to a failed run, in UTF-16 code units (Java `String.length`).
const STDERR_TAIL_UNITS: usize = 2000;

#[derive(Debug, Clone)]
pub struct ExecResult {
    pub exit_code: i32,
    pub stdout: String,
    pub duration_ms: u64,
    pub usage: Option<Usage>,
}

#[derive(Debug, Clone)]
pub struct ClaudeExec {
    pub cli: PathBuf,
    pub mcp: McpSupport,
}

/// Command-line arguments after the program name, in the order pinned by the Java
/// `ClaudeExecAdapterTest`: `-p --output-format json --setting-sources project
/// [--model m] [--effort e] <mcp args>` (`--allowedTools` is variadic, so it goes last).
/// The permission-bypass flag is never added for analysis.
pub fn build_args(model: Option<&str>, effort: Option<&str>, mcp_args: &[String]) -> Vec<String> {
    let mut args: Vec<String> = [
        "-p",
        "--output-format",
        "json",
        "--setting-sources",
        "project",
    ]
    .iter()
    .map(|s| s.to_string())
    .collect();
    if let Some(m) = model.filter(|m| !m.trim().is_empty()) {
        args.push("--model".into());
        args.push(m.to_string());
    }
    if let Some(e) = effort.filter(|e| !e.trim().is_empty()) {
        args.push("--effort".into());
        args.push(e.to_string());
    }
    args.extend(mcp_args.iter().cloned());
    args
}

/// Parent env minus the stack secrets (names compared case-insensitively).
pub fn child_env(parent: impl Iterator<Item = (String, String)>) -> Vec<(String, String)> {
    parent
        .filter(|(k, _)| !secret_env_names().iter().any(|n| n.eq_ignore_ascii_case(k)))
        .collect()
}

/// `java.time.Duration.toString`: `PT10M`, `PT30S`, `PT1H30M`, `PT1.5S`, `PT0S`.
pub fn iso_duration(d: Duration) -> String {
    let total = d.as_secs();
    let (h, m, s) = (total / 3600, (total % 3600) / 60, total % 60);
    let nanos = d.subsec_nanos();
    if total == 0 && nanos == 0 {
        return "PT0S".into();
    }
    let mut out = String::from("PT");
    if h != 0 {
        out.push_str(&format!("{h}H"));
    }
    if m != 0 {
        out.push_str(&format!("{m}M"));
    }
    if s != 0 || nanos != 0 {
        out.push_str(&s.to_string());
        if nanos != 0 {
            let frac = format!("{nanos:09}");
            out.push('.');
            out.push_str(frac.trim_end_matches('0'));
        }
        out.push('S');
    }
    out
}

/// The last `max_units` UTF-16 code units of `s`, and whether anything was cut.
/// Cuts only at character boundaries: if the cut would fall inside a surrogate pair
/// (an emoji), the whole character is dropped, so the tail is never longer than
/// `max_units` and never invalid. Reused by the result reporter (Task 7).
pub fn tail_utf16(s: &str, max_units: usize) -> (String, bool) {
    let total: usize = s.chars().map(char::len_utf16).sum();
    if total <= max_units {
        return (s.to_string(), false);
    }
    let mut units = 0;
    let mut start = s.len();
    for (idx, ch) in s.char_indices().rev() {
        let n = ch.len_utf16();
        if units + n > max_units {
            break;
        }
        units += n;
        start = idx;
    }
    (s[start..].to_string(), true)
}

/// Java `BufferedReader.readLine` loop that appends `'\n'` to every line: line ends
/// (`\n`, `\r\n`, lone `\r`) become `\n` and a missing final one is added.
fn java_lines(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 1);
    let mut chars = s.chars().peekable();
    let mut pending = false;
    while let Some(c) = chars.next() {
        match c {
            '\r' => {
                if chars.peek() == Some(&'\n') {
                    chars.next();
                }
                out.push('\n');
                pending = false;
            }
            '\n' => {
                out.push('\n');
                pending = false;
            }
            _ => {
                out.push(c);
                pending = true;
            }
        }
    }
    if pending {
        out.push('\n');
    }
    out
}

/// Jackson `JsonNode.asLong(0)`: numbers truncate, numeric strings parse, booleans are
/// 1/0, everything else (missing, null, object, array) is 0.
fn as_long(v: Option<&Value>) -> i64 {
    match v {
        Some(Value::Number(n)) => n.as_i64().unwrap_or_else(|| {
            n.as_u64()
                .map(|u| u.min(i64::MAX as u64) as i64)
                .unwrap_or_else(|| n.as_f64().map(|f| f as i64).unwrap_or(0))
        }),
        Some(Value::String(s)) => {
            let t = s.trim();
            t.parse::<i64>()
                .ok()
                .or_else(|| t.parse::<f64>().ok().map(|f| f as i64))
                .unwrap_or(0)
        }
        Some(Value::Bool(b)) => i64::from(*b),
        _ => 0,
    }
}

/// Unwraps the `claude -p --output-format json` envelope. Anything that does not fit
/// (not a JSON object, no cost/usage, `result` not a string) yields the raw text and no
/// usage. Usage is the sum over `modelUsage` (it includes sub-agents) when that object is
/// non-empty, else the `usage` fields; `is_error` is not looked at.
pub fn parse_envelope(raw: &str) -> (String, Option<Usage>) {
    if raw.trim().is_empty() {
        return (raw.to_string(), None);
    }
    // like Jackson `readTree`: only the first value matters, trailing text is ignored
    let first = serde_json::Deserializer::from_str(raw.trim())
        .into_iter::<Value>()
        .next();
    let Some(Ok(Value::Object(node))) = first else {
        return (raw.to_string(), None);
    };
    let cost = node.get("total_cost_usd").filter(|v| v.is_number());
    let usage_obj = node.get("usage").filter(|v| v.is_object());
    if cost.is_none() && usage_obj.is_none() {
        return (raw.to_string(), None);
    }
    let cost_usd = cost.and_then(Value::as_f64).unwrap_or(0.0);
    let model_usage = node
        .get("modelUsage")
        .and_then(Value::as_object)
        .filter(|m| !m.is_empty());
    let usage = match model_usage {
        Some(models) => {
            let mut u = Usage {
                cost_usd,
                input_tokens: 0,
                output_tokens: 0,
                cache_creation_tokens: 0,
                cache_read_tokens: 0,
            };
            for m in models.values() {
                u.input_tokens += as_long(m.get("inputTokens"));
                u.output_tokens += as_long(m.get("outputTokens"));
                u.cache_creation_tokens += as_long(m.get("cacheCreationInputTokens"));
                u.cache_read_tokens += as_long(m.get("cacheReadInputTokens"));
            }
            u
        }
        None => {
            let u = usage_obj;
            let field = |name: &str| as_long(u.and_then(|o| o.get(name)));
            Usage {
                cost_usd,
                input_tokens: field("input_tokens"),
                output_tokens: field("output_tokens"),
                cache_creation_tokens: field("cache_creation_input_tokens"),
                cache_read_tokens: field("cache_read_input_tokens"),
            }
        }
    };
    let text = match node.get("result") {
        Some(Value::String(s)) => s.clone(),
        _ => raw.to_string(),
    };
    (text, Some(usage))
}

impl ClaudeExec {
    /// Runs claude with the worker's own environment (secrets stripped).
    pub fn exec(
        &self,
        prompt: &str,
        dir: &Path,
        timeout: Duration,
        mcps_extra: &[McpSpec],
        model: Option<&str>,
        effort: Option<&str>,
    ) -> Result<ExecResult, String> {
        let parent = std::env::vars_os()
            .filter_map(|(k, v)| Some((k.into_string().ok()?, v.into_string().ok()?)));
        self.exec_with_env(prompt, dir, timeout, mcps_extra, model, effort, parent)
    }

    /// [`ClaudeExec::exec`] with an explicit parent environment (tests inject their own).
    /// `Err` = could not run, or the limit was hit (`claude timeout after PT10M`).
    #[allow(clippy::too_many_arguments)]
    pub fn exec_with_env(
        &self,
        prompt: &str,
        dir: &Path,
        timeout: Duration,
        mcps_extra: &[McpSpec],
        model: Option<&str>,
        effort: Option<&str>,
        parent_env: impl Iterator<Item = (String, String)>,
    ) -> Result<ExecResult, String> {
        let mcp_args = match self.mcp.args_for(mcps_extra) {
            Ok(a) => a,
            Err(e) => {
                log::error(&format!(
                    "per-task MCP config failed - continuing without extras: {e}"
                ));
                McpArgs {
                    args: self.mcp.base_args(),
                    temp_file: None,
                }
            }
        };
        let result = self.run(
            prompt,
            dir,
            timeout,
            model,
            effort,
            &mcp_args.args,
            parent_env,
        );
        if let Some(tmp) = &mcp_args.temp_file
            && let Err(e) = std::fs::remove_file(tmp)
            && e.kind() != std::io::ErrorKind::NotFound
        {
            log::warn(&format!(
                "could not delete temporary MCP config {}: {e}",
                tmp.display()
            ));
        }
        result
    }

    #[allow(clippy::too_many_arguments)]
    fn run(
        &self,
        prompt: &str,
        dir: &Path,
        timeout: Duration,
        model: Option<&str>,
        effort: Option<&str>,
        mcp_args: &[String],
        parent_env: impl Iterator<Item = (String, String)>,
    ) -> Result<ExecResult, String> {
        let mut cmd = Command::new(&self.cli);
        cmd.args(build_args(model, effort, mcp_args))
            .current_dir(dir)
            .env_clear()
            .envs(child_env(parent_env));
        let out = proc::run(&mut cmd, Some(prompt.as_bytes()), timeout)
            .map_err(|e| format!("claude could not be started: {}", e.kind()))?;
        if out.timed_out {
            return Err(format!("claude timeout after {}", iso_duration(timeout)));
        }
        let stdout = java_lines(&out.stdout);
        let stderr = java_lines(&out.stderr);
        let (mut text, usage) = parse_envelope(&stdout);
        if usage.is_none() {
            log::warn(&format!(
                "claude envelope not parsed - usage not collected (stdout {} bytes)",
                stdout.len()
            ));
        }
        if out.exit_code != 0 && !stderr.is_empty() {
            let (tail, cut) = tail_utf16(&stderr, STDERR_TAIL_UNITS);
            text.push_str("\n[stderr]\n");
            if cut {
                text.push('…');
            }
            text.push_str(&tail);
        }
        Ok(ExecResult {
            exit_code: out.exit_code,
            stdout: text,
            duration_ms: out.duration_ms,
            usage,
        })
    }
}
