use netis_worker_rs::config::Config;
use netis_worker_rs::log;
use netis_worker_rs::runner::{self, Timings};
use std::collections::HashMap;
use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::Arc;
use std::sync::atomic::AtomicBool;

fn home_dir(env: &HashMap<String, String>) -> Option<PathBuf> {
    ["USERPROFILE", "HOME"]
        .iter()
        .find_map(|k| env.get(*k).filter(|v| !v.is_empty()))
        .map(PathBuf::from)
}

fn main() -> ExitCode {
    // vars_os + lossy conversion: a non-Unicode variable must not panic the process.
    let env: HashMap<String, String> = std::env::vars_os()
        .map(|(k, v)| {
            (
                k.to_string_lossy().into_owned(),
                v.to_string_lossy().into_owned(),
            )
        })
        .collect();
    let Some(home) = home_dir(&env) else {
        log::error("worker-rs fatal: home directory unknown (USERPROFILE/HOME not set)");
        return ExitCode::from(1);
    };
    match Config::from_env(&env, &home) {
        Ok(cfg) => {
            let timings = Timings {
                auth_check: cfg.auth_check_interval,
                ..Timings::default()
            };
            // `run` prints `worker-rs started: ...` once the MCP file is prepared, then
            // starts the threads; nothing ever sets `stop` in a real run.
            runner::run(cfg, timings, Arc::new(AtomicBool::new(false)));
            ExitCode::SUCCESS
        }
        Err(reason) => {
            log::error(&format!("worker-rs fatal: {reason}"));
            ExitCode::from(1)
        }
    }
}
