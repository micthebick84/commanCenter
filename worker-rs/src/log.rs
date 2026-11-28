//! Log lines: `<ISO-8601 time with offset> <LEVEL> <message>` on stdout (UTF-8).

use std::io::Write;
use time::OffsetDateTime;
use time::format_description::well_known::Rfc3339;

/// Current time with the local offset; falls back to UTC when the offset is unknown.
fn now() -> OffsetDateTime {
    OffsetDateTime::now_local().unwrap_or_else(|_| OffsetDateTime::now_utc())
}

pub fn format_line(at: OffsetDateTime, level: &str, msg: &str) -> String {
    let ts = at
        .format(&Rfc3339)
        .unwrap_or_else(|_| at.unix_timestamp().to_string());
    format!("{ts} {level} {msg}")
}

fn emit(level: &str, msg: &str) {
    let line = format_line(now(), level, msg);
    let stdout = std::io::stdout();
    let mut out = stdout.lock();
    // A closed stdout must never take the worker down.
    let _ = writeln!(out, "{line}");
    let _ = out.flush();
}

pub fn info(msg: &str) {
    emit("INFO", msg);
}

pub fn warn(msg: &str) {
    emit("WARN", msg);
}

pub fn error(msg: &str) {
    emit("ERROR", msg);
}

#[cfg(test)]
mod tests {
    use super::*;
    use time::macros::datetime;

    #[test]
    fn line_has_time_level_and_message() {
        let line = format_line(datetime!(2026-11-28 09:30:00 +09:00), "INFO", "안녕 hello");
        assert_eq!(line, "2026-11-28T09:30:00+09:00 INFO 안녕 hello");
    }

    #[test]
    fn utc_is_written_as_z() {
        let line = format_line(datetime!(2026-11-28 00:00:00 UTC), "WARN", "x");
        assert_eq!(line, "2026-11-28T00:00:00Z WARN x");
    }

    #[test]
    fn emit_does_not_panic() {
        info("i");
        warn("w");
        error("e");
    }
}
