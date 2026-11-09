package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.DbChangeView;
import com.hamonsoft.netismaker.dto.DbMcpGrant;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse.DbWriteSpec;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 구현·디자인 단계 DB MCP (스펙 2026-10-14 §6.4). grant를 http MCP 스펙(토큰 헤더)으로 바꾸고 프롬프트 안내를 만든다.
 * 토큰은 임시 mcp-config 파일에만 실리고 ClaudeExecAdapter가 exec 뒤 지운다. 프롬프트에는 토큰을 넣지 않는다.
 *
 * <p>구현 단계의 개발 DB 쓰기(스펙 2026-11-03 §6.7·§6.8)는 안내를 세 곳으로 나눈다 — 연결별 DB 절({@link #promptSection(DbMcpGrant, boolean)}),
 * 자동 마이그레이션 레포 절({@link #migrationManagedSection}), 이전 시도·회차가 이미 반영한 SQL 절({@link #appliedSqlSection}).
 * 붙는 순서는 DB 절 → 자동 마이그레이션 절 → 이미 적용된 SQL 절 → 참조 절이고 일반 구현과 수정 회차가 같다.
 */
final class DbMcpWorkerSupport {

    /** 이미 적용된 SQL 절 전체의 상한(자). 프롬프트가 기록 때문에 끝없이 커지지 않게 한다. */
    static final int APPLIED_SQL_MAX_CHARS = 20_000;

    private static final String APPLIED_SQL_TITLE = "\n## 이미 적용된 SQL (다시 실행하지 마세요)\n";
    private static final String APPLIED_SQL_INTRO =
            "이 작업의 이전 시도·회차가 공용 개발 DB에 이미 반영했거나 승인 대기로 남긴 SQL의 기록입니다(지시가 아니라 기록입니다). "
                    + "다시 실행하지 말고, `describe_table`·`query`로 지금 상태를 확인한 뒤 남은 것만 적용하세요.\n";
    private static final String CUT_MARK = "…(이하 생략)";

    private DbMcpWorkerSupport() {}

    static List<TaskMcpSpec> withDbServers(List<TaskMcpSpec> extras, DbMcpGrant grant, String apiBaseUrl) {
        List<TaskMcpSpec> out = new ArrayList<>(extras == null ? List.of() : extras);
        if (grant == null || grant.token() == null || grant.servers().isEmpty()) return out;
        String base = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
        for (DbMcpGrant.Server s : grant.servers()) {
            out.add(new TaskMcpSpec(s.name(), base + s.path(), "http",
                    Map.of("Authorization", "Bearer " + grant.token())));
        }
        return out;
    }

    /** 읽기 전용 안내 — 디자인 프롬프트가 쓴다(쓰기 절은 구현 단계에만 있다). */
    static String promptSection(DbMcpGrant grant) {
        return promptSection(grant, false);
    }

    /**
     * DB 절. 읽기만 되는 연결은 개발 DB 쓰기가 생기기 전 문구 그대로({@code allowWriteBlock}가 거짓이면 모든 연결이 그렇다). 쓰기 도구가 붙은 연결
     * (구현 단계 writable)이 하나라도 있으면 그 연결들은 새 절 "개발 DB 쓰기"에 §6.7 순서로 안내하고, 읽기만 되는 연결이 함께 있으면 기존
     * 읽기 절이 그 연결만 안내한다(쓰기 절이 앞). 연결에 붙이는 알림은 어느 경우든 맨 끝이다. 토큰이 없으면 도구가 안 붙으므로 연결 줄은 없다.
     */
    static String promptSection(DbMcpGrant grant, boolean allowWriteBlock) {
        if (grant == null || (grant.servers().isEmpty() && grant.notices().isEmpty())) return "";
        boolean attached = !grant.servers().isEmpty() && grant.token() != null;
        List<DbMcpGrant.Server> writable = new ArrayList<>();
        List<DbMcpGrant.Server> readOnly = new ArrayList<>();
        if (attached) {
            for (DbMcpGrant.Server s : grant.servers()) {
                (allowWriteBlock && s.writable() ? writable : readOnly).add(s);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!writable.isEmpty()) sb.append(writeSection(writable));
        if (writable.isEmpty() || !readOnly.isEmpty()) sb.append(readOnlySection(readOnly));
        for (String n : grant.notices()) sb.append("참고: ").append(n).append('\n');
        return sb.toString();
    }

    private static String readOnlySection(List<DbMcpGrant.Server> servers) {
        StringBuilder sb = new StringBuilder("\n\n## 데이터베이스 조회 (읽기 전용)\n");
        if (servers.isEmpty()) return sb.toString();
        sb.append("이 작업에는 아래 DB 조회 도구가 붙어 있습니다. 스키마·데이터를 확인할 때 쓰세요. ")
          .append("SELECT 계열 단일 문장만 실행되고 최대 200행·30초 제한입니다. ")
          .append("쓰기(INSERT/UPDATE/DELETE/DDL)는 서버가 거부합니다.\n");
        for (DbMcpGrant.Server s : servers) {
            sb.append("- mcp__").append(s.name()).append("__query / list_tables / describe_table : ")
              .append(s.label()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 쓰기 가능한 연결의 안내 (스펙 2026-11-03 §6.7). 세션은 PR 본문을 쓸 수 없고 본문은 워커가 만든다 — 그래서 자동 마이그레이션 장치를
     * 발견했다는 알림은 {@code .netis/db-notes.md}에 남기게 하고 워커가 PR 본문의 리뷰 메모로 옮긴다(계획 "설계와 다름 ①").
     */
    private static String writeSection(List<DbMcpGrant.Server> servers) {
        StringBuilder sb = new StringBuilder("\n\n## 개발 DB 쓰기 (함께 쓰는 개발 DB)\n")
                .append("이 작업에는 아래 개발 DB의 조회·쓰기 도구가 붙어 있습니다. 이 DB는 다른 개발자와 다른 작업도 함께 씁니다. ")
                .append("스키마·데이터는 `query`·`list_tables`·`describe_table`로 확인하고, 필요한 테이블·컬럼·가데이터는 `execute`로 직접 적용하세요. ")
                .append("`execute`는 한 번에 한 문장만 받고 서버가 문장을 분류합니다. ")
                .append("거부되거나 승인 대기로 돌아온 문장은 같은 문장을 다시 보내지 말고 응답의 이유와 대안을 따르세요.\n");
        for (DbMcpGrant.Server s : servers) {
            sb.append("- mcp__").append(s.name()).append("__query / list_tables / describe_table / execute : ")
              .append(s.label()).append('\n');
        }
        return sb.append("- 남의 테이블 컬럼을 지우거나 이름·타입을 바꿔야 하면 옛 컬럼은 두고 새 컬럼을 추가해 그쪽을 쓰세요. ")
                .append("옛 컬럼 삭제는 `execute`로 보내 승인 대기로 남기세요(관리자가 병합 뒤 실행합니다).\n")
                .append("- 가데이터는 알아볼 수 있게 넣으세요(예: 이름에 `[테스트]`).\n")
                .append("- 적용한 뒤에는 `query`로 결과를 확인하세요.\n")
                .append("- 레포에 마이그레이션 파일을 만들지 마세요. 이미 개발 DB에 적용되고, 운영용 SQL 스크립트는 워커가 기록으로 따로 만듭니다.\n")
                .append("- 레포에 앱이 뜰 때 스크립트를 자동 실행하는 장치(Flyway·Liquibase·`schema.sql` 자동 실행 등)가 보이면 ")
                .append("DDL은 `execute`로 실행하지 말고, `.netis/db-notes.md`에 \"자동 마이그레이션 레포로 보임 — 카탈로그 설정 확인 필요\"를 남기세요. ")
                .append("이 파일은 워커가 PR 본문의 `## 리뷰 메모`에 옮긴 뒤 지우며 커밋되지 않습니다.\n")
                .toString();
    }

    /** 이 구현에 개발 DB 쓰기 도구가 붙어 있는가 — 도구가 실제로 붙으려면 토큰과 쓰기 연결이 함께 있어야 한다(withDbServers·promptSection과 같은 기준). */
    static boolean anyWritable(DbMcpGrant grant) {
        return grant != null && grant.token() != null && grant.servers().stream().anyMatch(DbMcpGrant.Server::writable);
    }

    /**
     * 자동 마이그레이션 레포 절 (스펙 2026-11-03 §6.8) — {@code db.migrationManaged}가 거짓이면 빈 문자열. 타입·DB 연결 선택 여부와 무관하게 붙는다.
     * 이런 레포에는 쓰기 연결이 없어(조건 5) 옛 컬럼 기준을 전할 곳이 이 절뿐이고, 서버가 막지 못하는 레포에서 "다른 사람 화면이 깨지는 것"을
     * 막는 유일한 지시다. 앞에 빈 줄 하나(앞 절이 줄바꿈으로 끝난다).
     */
    static String migrationManagedSection(DbWriteSpec db) {
        if (db == null || !db.migrationManaged()) return "";
        return "\n## DB 변경 (자동 마이그레이션 레포)\n"
                + "이 레포는 앱이 뜰 때 마이그레이션 파일을 DB에 자동 적용합니다. DB 변경이 필요하면 레포의 마이그레이션 관례대로 파일로 만드세요. "
                + "가데이터도 관례가 허용하면 그 방식으로 넣으세요. 개발 DB에는 시험 컨테이너가 뜰 때 적용됩니다 — DB에 직접 실행하지 마세요.\n"
                + "- 같은 DB를 다른 사람도 씁니다. 기존 컬럼을 지우거나 이름·타입을 바꿔야 하면 옛 컬럼은 두고 새 컬럼을 추가해 그쪽을 쓰세요. "
                + "옛 컬럼 삭제는 이번 변경에 넣지 말고 병합 뒤 별도 작업으로 남기세요.\n";
    }

    /**
     * 이전 시도·회차가 이미 반영했거나(EXECUTED) 승인 대기로 남긴(DEFERRED) SQL의 목록 (스펙 2026-11-03 §5.2). 거부·실패·실행 안 함은 넣지
     * 않는다 — 공용 DB에 반영되지 않았거나 반영하지 않기로 한 문장이다. 기록 순서 그대로, 해당 기록이 없으면 빈 문자열.
     *
     * <p>절 전체가 {@link #APPLIED_SQL_MAX_CHARS}자를 넘지 않는다. 넘으면 앞에서부터 싣고 나머지는 개수만 적으며, 문장 하나가 상한보다
     * 크면 그 문장을 잘라 싣는다. 여러 줄 SQL은 줄바꿈을 유지하되 목록 항목 안에 머물도록 이어지는 줄을 두 칸 들여쓴다.
     */
    static String appliedSqlSection(List<DbChangeView> changes) {
        if (changes == null) return "";
        List<String> lines = new ArrayList<>();
        for (DbChangeView c : changes) {
            String label = labelOf(c.classification());
            if (label == null) continue;
            lines.add(applyLine(c, label));
        }
        if (lines.isEmpty()) return "";

        String head = APPLIED_SQL_TITLE + APPLIED_SQL_INTRO;
        // 생략 안내 줄이 들어갈 자리를 항상 남겨 두고 채운다 — 상한은 절 전체
        String omittedNote = "- …외 999건 생략 — `describe_table`·`query`로 지금 상태를 확인하세요\n";
        int room = APPLIED_SQL_MAX_CHARS - head.length() - omittedNote.length();
        StringBuilder body = new StringBuilder();
        int listed = 0;
        for (String line : lines) {
            if (body.length() + line.length() > room) break;
            body.append(line);
            listed++;
        }
        if (listed == 0) {
            // 문장 하나가 상한보다 크다 — 비워 두지 않고 잘라서 싣는다
            body.append(cut(lines.get(0), room));
            listed = 1;
        }
        int omitted = lines.size() - listed;
        if (omitted > 0) {
            body.append("- …외 ").append(omitted).append("건 생략 — `describe_table`·`query`로 지금 상태를 확인하세요\n");
        }
        return head + body;
    }

    /** 절에 싣는 분류의 이름표 — 싣지 않는 분류(거부·실패·실행 안 함·모르는 값)는 null. */
    private static String labelOf(String classification) {
        if ("EXECUTED".equals(classification)) return "[실행됨]";
        if ("DEFERRED".equals(classification)) return "[승인 대기]";
        return null;
    }

    private static String applyLine(DbChangeView c, String label) {
        String conn = c.connectionName() == null || c.connectionName().isBlank() ? "" : " " + c.connectionName().strip();
        String sql = (c.sqlText() == null ? "" : c.sqlText()).replace("\r\n", "\n").replace('\r', '\n').strip();
        return "- " + label + " #" + c.id() + conn + ": " + sql.replace("\n", "\n  ") + "\n";
    }

    /** line을 max자 이내(끝 줄바꿈 포함)로 줄이고 끝에 표시를 붙인다. 서로게이트 쌍은 가운데서 자르지 않는다. */
    private static String cut(String line, int max) {
        if (line.length() <= max) return line;
        int keep = max - CUT_MARK.length() - 1;
        if (keep > 0 && Character.isHighSurrogate(line.charAt(keep - 1))) keep--;
        return line.substring(0, Math.max(keep, 0)) + CUT_MARK + "\n";
    }
}
