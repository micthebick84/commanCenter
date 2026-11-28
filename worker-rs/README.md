# worker-rs

Rust pilot of the NetisMaker worker daemon (analysis tasks only). Pure-Rust dependencies, no C compiler needed.

## Build and test

```bash
export PATH="$HOME/.cargo/bin:$PATH"   # Git Bash
cd worker-rs
cargo fmt --check && cargo clippy --all-targets --features test-bins -- -D warnings && cargo test --locked --features test-bins
cargo build --release --locked         # target/release/netis-worker-rs(.exe)
```

`contract/` holds golden files shared with the Java side. A Java test owns and guards them; do not edit by hand.

## Environment

| Name | Required | Default / meaning |
|---|---|---|
| `WORKER_ID` | yes | at most 50 characters; unique per process |
| `API_BASE_URL` | yes | must start with `http://` |
| `WORKER_API_KEY` | yes | sent as `X-Worker-API-Key` |
| `WORKER_KINDS` | no | comma list; empty = `ANALYSIS` (only implemented kind) |
| `WORKER_VERSION` | no | `0.1.0`; at most 50 characters |
| `REPOS_DIR` | no | `~/netis-maker/repos` |
| `DEAD_LETTER_DIR` | no | `~/netis-maker/dead-letter` |
| `CLAUDE_CLI` | no | else `claude(.exe)` on `PATH`, then `~/.local/bin` |
| `GITHUB_PAT`, `GITLAB_TOKEN` | no | git credentials |

Startup prints `worker-rs started: id=<id> kinds=<kinds>`; a bad setting prints `worker-rs fatal: <reason>` and exits 1.
