//! Hides `user:secret@` in URLs. Same behavior as the Java mask
//! `(?<=://)[^/@\s:]+:[^/@\s]+@` -> `***@`.
//!
//! Rust `regex` has no lookbehind, so `://` is captured and put back. Java `\s` is
//! ASCII-only, so the whitespace set is spelled out instead of using Unicode `\s`.

use regex::Regex;
use std::sync::OnceLock;

fn pattern() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(r"(://)[^/@ \t\n\x0B\x0C\r:]+:[^/@ \t\n\x0B\x0C\r]+@").expect("valid mask regex")
    })
}

pub fn mask(text: &str) -> String {
    pattern().replace_all(text, "${1}***@").into_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mask_cases_from_contract() {
        let raw = include_str!("../contract/mask-cases.json");
        let cases: Vec<serde_json::Value> = serde_json::from_str(raw).unwrap();
        assert!(!cases.is_empty());
        for case in cases {
            let input = case["input"].as_str().unwrap();
            let output = case["output"].as_str().unwrap();
            assert_eq!(mask(input), output, "input: {input:?}");
        }
    }
}
