package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewTurn;
import com.hamonsoft.netismaker.entity.QuestionAttachment;
import com.hamonsoft.netismaker.entity.RefRepo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 질문 세션의 대화 전문(.md) — "이 대화로 작업 등록" 때 작업 첨부로 남는다 (스펙 2026-10-24 §4.1).
 *
 * <pre>
 * # 질문 #{id} 대화 전문 — {제목}
 *
 * - 대상: {별칭} · {브랜치}
 * - 참조 repo(질문 당시):            ← 없으면 같은 줄에 "없음"
 *   - {별칭} · {브랜치}@{sha7}       ← 고정 커밋이 없으면 "{별칭} · {브랜치} — 받지 못함"
 *
 * ## 첫 질문                          ← 세션 description (턴이 아님) + 등록 때 첨부 이름
 * ## 대화                             ← 턴 순서대로: 답변 / 추가 질문(+ 그 턴 첨부 이름) / 시스템 메모
 * </pre>
 *
 * 첨부는 원래 이름만 적고 내용·저장 경로는 싣지 않는다. 시각은 넣지 않는다(같은 대화면 같은 전문).
 * 제목·별칭·브랜치·첨부 이름처럼 한 줄에 들어가는 값은 줄바꿈을 공백으로 바꿔 머리·턴 구조를 깨지 못하게 한다.
 * 질문·답변 본문은 그대로 싣는다.
 */
final class QuestionTranscript {

    static final String CONTENT_TYPE = "text/markdown";

    private QuestionTranscript() {}

    static String fileName(long questionId) {
        return "question-" + questionId + "-대화.md";
    }

    static String render(InterviewSession q, List<InterviewTurn> turns, List<QuestionAttachment> attachments) {
        List<String> kickoff = new ArrayList<>();
        Map<Integer, List<String>> byTurn = new LinkedHashMap<>();
        for (QuestionAttachment a : attachments) {
            if (a.getTurnSeq() == null) kickoff.add(a.getOriginalFilename());
            else byTurn.computeIfAbsent(a.getTurnSeq(), k -> new ArrayList<>()).add(a.getOriginalFilename());
        }

        StringBuilder md = new StringBuilder();
        md.append("# 질문 #").append(q.getId()).append(" 대화 전문 — ").append(oneLine(q.getTitle())).append("\n\n");
        String target = q.getRepoAlias() == null ? q.getGithubRepo() : q.getRepoAlias();
        md.append("- 대상: ").append(oneLine(target)).append(" · ").append(oneLine(q.getGithubBranch())).append('\n');
        appendRefs(md, q.getRefRepos());

        md.append("\n## 첫 질문\n\n").append(body(q.getDescription())).append('\n');
        appendAttachmentLine(md, kickoff);

        md.append("\n## 대화\n");
        if (turns.isEmpty()) md.append("\n없음\n");
        for (InterviewTurn t : turns) {
            switch (t.getRole()) {
                case "assistant" -> md.append("\n### 답변 (턴 ").append(t.getSeq()).append(")\n\n")
                        .append(body(t.getContent())).append('\n');
                case "user" -> md.append("\n### 추가 질문 (턴 ").append(t.getSeq()).append(")\n\n")
                        .append(body(t.getContent())).append('\n');
                default -> md.append('\n').append(quoted("시스템 메모: " + body(t.getContent()))).append('\n');
            }
            appendAttachmentLine(md, byTurn.getOrDefault(t.getSeq(), List.of()));
        }
        return md.toString();
    }

    private static void appendRefs(StringBuilder md, List<RefRepo> refs) {
        if (refs == null || refs.isEmpty()) {
            md.append("- 참조 repo(질문 당시): 없음\n");
            return;
        }
        md.append("- 참조 repo(질문 당시):\n");
        for (RefRepo r : refs) {
            String alias = r.alias() == null ? r.path() : r.alias();
            md.append("  - ").append(oneLine(alias)).append(" · ").append(oneLine(r.branch()));
            if (r.sha() == null || r.sha().isBlank()) {
                md.append(" — 받지 못함");
            } else {
                md.append('@').append(oneLine(r.sha().substring(0, Math.min(7, r.sha().length()))));
            }
            md.append('\n');
        }
    }

    /** "첨부: a, b" — 이름만. 없으면 줄을 쓰지 않는다. */
    private static void appendAttachmentLine(StringBuilder md, List<String> names) {
        if (names.isEmpty()) return;
        md.append("\n첨부: ")
                .append(String.join(", ", names.stream().map(QuestionTranscript::oneLine).toList()))
                .append('\n');
    }

    /** 마크다운 인용 — 여러 줄이어도 줄마다 "> "를 붙여 인용 밖으로 새지 않게 한다. */
    private static String quoted(String text) {
        return "> " + String.join("\n> ", text.split("\r\n|[\r\n]", -1));
    }

    /** 한 줄에 들어가는 값 — 줄바꿈(\r\n·\r·\n)을 공백 하나로. 마크다운에서 줄을 나누는 것은 이 셋뿐이다. */
    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\r\n|[\r\n]", " ");
    }

    private static String body(String value) {
        return value == null ? "" : value;
    }
}
