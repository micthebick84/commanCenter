use netis_worker_rs::config::Config;
use netis_worker_rs::log;
use std::collections::HashMap;
use std::path::PathBuf;
use std::process::ExitCode;

fn home_dir(env: &HashMap<String, String>) -> Option<PathBuf> {
    ["USERPROFILE", "HOME"]
        .iter()
        .find_map(|k| env.get(*k).filter(|v| !v.is_empty()))
        .map(PathBuf::from)
}

fn main() -> ExitCode {
    let env: HashMap<String, String> = std::env::vars().collect();
    let Some(home) = home_dir(&env) else {
        log::error("worker-rs fatal: home directory unknown (USERPROFILE/HOME not set)");
        return ExitCode::from(1);
    };
    match Config::from_env(&env, &home) {
        Ok(cfg) => {
            log::info(&format!(
                "worker-rs started: id={} kinds={}",
                cfg.worker_id,
                cfg.kinds.join(",")
            ));
            ExitCode::SUCCESS
        }
        Err(reason) => {
            log::error(&format!("worker-rs fatal: {reason}"));
            ExitCode::from(1)
        }
    }
}
