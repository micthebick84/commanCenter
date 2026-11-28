//! Analysis result parser. Port of the Java `PromptResultParser`.
//!
//! Java regex semantics that differ from Rust `regex` and are reproduced here:
//! * `\s` / `\d` are ASCII-only, so the classes are spelled out.
//! * MULTILINE `^` and `.` treat `\n`, `\r` (`\r\n` as one), U+0085, U+2028 and U+2029 as
//!   line terminators. `.` is an explicit negated class, and `^` is checked by hand: every
//!   pattern is matched unanchored, and a match whose start is not a Java line start is
//!   discarded and the search resumes one character later. This equals Java's scan, which
//!   tries each start position in order. (`(?:^|\n)` in the property patterns is subsumed
//!   by `^`, because the position after a `\n` is always a line start.)
//! * `String.trim()` strips every char <= U+0020 only, and `String.isBlank()` uses
//!   `Character.isWhitespace` (no-break spaces are not whitespace).
//! * `String.chars()` yields UTF-16 units, so letters outside the BMP are never counted,
//!   and `Character.isLetter` is the Unicode general category L (`\p{L}`).

use regex::{Captures, Regex};
use serde::Serialize;
use std::collections::HashMap;
use std::sync::OnceLock;

#[derive(Debug, Clone, PartialEq)]
pub struct Parsed {
    pub markdown: String,
    pub subtasks_json: String,
    pub warnings: Vec<String>,
}

/// Field order matches the Jackson `ObjectNode` insertion order of the Java parser.
#[derive(Serialize)]
struct Subtask<'a> {
    title: &'a str,
    summary: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    files: Option<&'a str>,
    #[serde(rename = "estimatedLoc", skip_serializing_if = "Option::is_none")]
    estimated_loc: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    risk: Option<&'a str>,
}

// Java `\s` (ASCII) and Java `.` (no line terminator).
const WS: &str = r"[ \t\n\x0B\x0C\r]";
const DOT: &str = r"[^\n\r\x{85}\x{2028}\x{2029}]";

struct Patterns {
    section_header: Regex,
    subtask_header: Regex,
    prop_files: Regex,
    prop_loc: Regex,
    prop_risk: Regex,
    letter: Regex,
}

fn patterns() -> &'static Patterns {
    static P: OnceLock<Patterns> = OnceLock::new();
    P.get_or_init(|| {
        let re = |s: String| Regex::new(&s).expect("valid parser regex");
        Patterns {
            section_header: re(format!(r"##{WS}+([0-9]+)\.{WS}*({DOT}*)")),
            subtask_header: re(format!(r"-{WS}*\*\*([^*]+?)\*\*{WS}*:{WS}*({DOT}*)")),
            prop_files: re(format!(r"{WS}*-{WS}*대상{WS}*파일{WS}*:{WS}*({DOT}+)")),
            prop_loc: re(format!(r"{WS}*-{WS}*예상{WS}*LoC{WS}*:{WS}*({DOT}+)")),
            prop_risk: re(format!(r"{WS}*-{WS}*위험도{WS}*:{WS}*([LMH])")),
            letter: re(r"\p{L}".to_string()),
        }
    })
}

/// Java MULTILINE `^` at byte offset `pos` of `text` (never matches at the end of input).
fn is_line_start(text: &str, pos: usize) -> bool {
    if pos == 0 {
        return true;
    }
    if pos >= text.len() {
        return false;
    }
    let prev = text[..pos].chars().next_back().expect("pos > 0");
    match prev {
        '\n' | '\u{85}' | '\u{2028}' | '\u{2029}' => true,
        '\r' => text.as_bytes()[pos] != b'\n',
        _ => false,
    }
}

/// First match of `re` (which has no leading `^`) that starts at a Java line start,
/// searching from byte offset `from`.
fn find_at_line_start<'t>(re: &Regex, text: &'t str, from: usize) -> Option<Captures<'t>> {
    let mut at = from;
    while at <= text.len() {
        let caps = re.captures_at(text, at)?;
        let start = caps.get(0).expect("group 0").start();
        if is_line_start(text, start) {
            return Some(caps);
        }
        at = start + text[start..].chars().next().map_or(1, char::len_utf8);
    }
    None
}

/// Java `String.trim()`: removes chars up to and including U+0020.
fn java_trim(s: &str) -> &str {
    s.trim_matches(|c: char| c <= ' ')
}

/// Java `Character.isWhitespace` for a code point.
fn java_is_whitespace(c: char) -> bool {
    matches!(
        c,
        '\t' | '\n'
            | '\u{B}'
            | '\u{C}'
            | '\r'
            | '\u{1C}'..='\u{1F}'
            | ' '
            | '\u{1680}'
            | '\u{2000}'..='\u{2006}'
            | '\u{2008}'..='\u{200A}'
            | '\u{2028}'
            | '\u{2029}'
            | '\u{205F}'
            | '\u{3000}'
    )
}

pub fn parse(raw: &str) -> Result<Parsed, String> {
    if raw.chars().all(java_is_whitespace) {
        return Err("출력이 비어있음".to_string());
    }
    let p = patterns();

    // section number -> (start, header end); a repeated number keeps the last header.
    let mut sections: HashMap<i32, (usize, usize)> = HashMap::new();
    let mut from = 0;
    while let Some(caps) = find_at_line_start(&p.section_header, raw, from) {
        let whole = caps.get(0).expect("group 0");
        let digits = &caps[1];
        // Java Integer.parseInt overflow throws NumberFormatException.
        let num: i32 = digits
            .parse()
            .map_err(|_| format!("For input string: \"{digits}\""))?;
        sections.insert(num, (whole.start(), whole.end()));
        from = whole.end();
    }

    for required in [1, 2, 3] {
        if !sections.contains_key(&required) {
            return Err(format!("섹션 ## {required}. 누락"));
        }
    }

    let mut warnings = Vec::new();
    let mut subtasks_json = "[]".to_string();

    if sections.contains_key(&4) {
        let section4 = extract_section(raw, &sections, 4);
        let items = parse_subtasks(section4);
        if items.is_empty() {
            warnings.push("섹션 ## 4. 본문에 subtask 0개".to_string());
        }
        subtasks_json =
            serde_json::to_string(&items).map_err(|e| format!("subtasks JSON 직렬화 실패: {e}"))?;
    } else {
        warnings.push("섹션 ## 4. 누락 — subtasks 0개로 처리".to_string());
    }

    if !sections.contains_key(&5) {
        warnings.push("섹션 ## 5. 누락".to_string());
    }

    let korean = raw
        .chars()
        .filter(|&c| matches!(c, '\u{AC00}'..='\u{D7A3}' | '\u{1100}'..='\u{11FF}'))
        .count();
    let alpha = p
        .letter
        .find_iter(raw)
        .filter(|m| {
            m.as_str()
                .chars()
                .next()
                .is_some_and(|c| u32::from(c) <= 0xFFFF)
        })
        .count();
    if alpha > 0 && (korean as f64) / (alpha as f64) < 0.5 {
        warnings.push(format!("한국어 비율 50% 미만 ({korean}/{alpha})"));
    }

    Ok(Parsed {
        markdown: raw.to_string(),
        subtasks_json,
        warnings,
    })
}

/// Body of section `num`: from the end of its header up to the nearest later-numbered
/// section start (or the end of the text).
fn extract_section<'t>(
    text: &'t str,
    sections: &HashMap<i32, (usize, usize)>,
    num: i32,
) -> &'t str {
    let start = sections[&num].1;
    let mut end = text.len();
    for (&key, &(cand_start, _)) in sections {
        if key > num && cand_start > start && cand_start < end {
            end = cand_start;
        }
    }
    &text[start..end]
}

fn parse_subtasks(section4: &str) -> Vec<Subtask<'_>> {
    let p = patterns();
    let mut headers = Vec::new(); // (start, end, title, summary)
    let mut from = 0;
    while let Some(caps) = find_at_line_start(&p.subtask_header, section4, from) {
        let whole = caps.get(0).expect("group 0");
        headers.push((
            whole.start(),
            whole.end(),
            java_trim(caps.get(1).expect("title").as_str()),
            java_trim(caps.get(2).expect("summary").as_str()),
        ));
        from = whole.end();
    }

    let mut out = Vec::with_capacity(headers.len());
    for (i, &(_, body_start, title, summary)) in headers.iter().enumerate() {
        let body_end = headers.get(i + 1).map_or(section4.len(), |h| h.0);
        let body = &section4[body_start..body_end];
        let prop = |re: &Regex| {
            find_at_line_start(re, body, 0).map(|c| c.get(1).expect("group 1").as_str())
        };
        out.push(Subtask {
            title,
            summary,
            files: prop(&p.prop_files).map(java_trim),
            estimated_loc: prop(&p.prop_loc).map(java_trim),
            risk: prop(&p.prop_risk),
        });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parser_cases_from_contract() {
        let raw = include_str!("../contract/parser-cases.json");
        let cases: Vec<serde_json::Value> = serde_json::from_str(raw).unwrap();
        assert!(!cases.is_empty());
        for case in cases {
            let name = case["name"].as_str().unwrap();
            let input = case["input"].as_str().unwrap();
            let result = parse(input);
            if case["ok"].as_bool().unwrap() {
                let parsed = result.unwrap_or_else(|e| panic!("{name}: unexpected error {e}"));
                let expected: serde_json::Value =
                    serde_json::from_str(case["subtasksJson"].as_str().unwrap()).unwrap();
                let actual: serde_json::Value =
                    serde_json::from_str(&parsed.subtasks_json).unwrap();
                assert_eq!(actual, expected, "{name}: subtasks");
                let warnings: Vec<String> = case["warnings"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .map(|w| w.as_str().unwrap().to_string())
                    .collect();
                assert_eq!(parsed.warnings, warnings, "{name}: warnings");
                assert_eq!(parsed.markdown, input, "{name}: markdown");
            } else {
                assert_eq!(
                    result.unwrap_err(),
                    case["error"].as_str().unwrap(),
                    "{name}: error"
                );
            }
        }
    }

    const HEAD: &str = "## 1. a\nx\n## 2. b\nx\n## 3. c\nx\n## 4. d\n";

    #[test]
    fn subtask_fields_are_optional() {
        let parsed = parse(&format!(
            "{HEAD}- **t**: s\n  - 대상 파일: `a.rs`\n## 5. e\n"
        ))
        .unwrap();
        let v: serde_json::Value = serde_json::from_str(&parsed.subtasks_json).unwrap();
        let item = v[0].as_object().unwrap();
        assert_eq!(item["files"], "`a.rs`");
        assert!(!item.contains_key("estimatedLoc"));
        assert!(!item.contains_key("risk"));
        // Jackson key order: title, summary, files
        assert_eq!(
            parsed.subtasks_json,
            r#"[{"title":"t","summary":"s","files":"`a.rs`"}]"#
        );
    }

    #[test]
    fn java_line_terminators_and_trim() {
        // U+2028 is a Java line terminator: `^` matches after it.
        let text = "## 1. a\u{2028}## 2. b\n## 3. c\n## 5. e\n";
        let parsed = parse(text).unwrap();
        assert_eq!(parsed.warnings[0], "섹션 ## 4. 누락 — subtasks 0개로 처리");
        // U+0085 is not `\s` for Java.
        assert_eq!(
            parse("## 1. a\n## 2. b\n##\u{85}3. c\n").unwrap_err(),
            "섹션 ## 3. 누락"
        );
        // String.trim() keeps no-break space.
        let parsed = parse(&format!("{HEAD}- **t**:\u{a0}s\u{a0}\n")).unwrap();
        let v: serde_json::Value = serde_json::from_str(&parsed.subtasks_json).unwrap();
        assert_eq!(v[0]["summary"], "\u{a0}s\u{a0}");
    }

    #[test]
    fn blank_and_overflow_inputs() {
        assert_eq!(parse("  \n\t").unwrap_err(), "출력이 비어있음");
        assert_eq!(parse("\u{a0}").unwrap_err(), "섹션 ## 1. 누락");
        assert_eq!(
            parse("## 99999999999. x\n").unwrap_err(),
            "For input string: \"99999999999\""
        );
    }
}
