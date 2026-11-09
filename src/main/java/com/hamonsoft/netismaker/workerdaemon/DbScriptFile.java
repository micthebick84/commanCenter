package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.DbChangeView;
import com.hamonsoft.netismaker.entity.TaskType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 워커가 커밋 직전에 만드는 PR SQL 스크립트 파일 (스펙 2026-11-03 §6.6) — 세션에게 맡기지 않고 API의 쓰기 기록으로 만든다(기록과 파일이
 * 어긋나지 않게). 재시도·수정 회차가 있어도 늘 그 작업의 <b>누적 기록 전체</b>로 다시 만든다.
 *
 * <p>내용은 두 부분이다 — ① 개발 DB에 실행된 문장(기록 순서 = 시간순, 시도 번호·연결 이름 주석) ② "병합 뒤 실행(승인 대기)" 절의 승인 대기
 * 문장. 거부·실패·실행 안 함은 싣지 않는다. 운영 반영 때 사람이 읽고 쓰는 기록이며 마이그레이션 도구의 자동 실행 폴더가 아니다.
 */
final class DbScriptFile {

    static final String EXECUTED = "EXECUTED";
    static final String DEFERRED = "DEFERRED";

    private static final String DEFERRED_TITLE = "-- 병합 뒤 실행(승인 대기)\n";

    private DbScriptFile() {}

    /**
     * 스크립트 파일 내용 — 실행됐거나(EXECUTED) 승인 대기로 남은(DEFERRED) 기록이 하나도 없으면 empty(그때 워커는 파일을 쓰지 않아 세션이 쓴
     * 파일을 덮어쓰지 않는다). 기록 순서는 API가 준 그대로(작성 시각·id 순)다.
     */
    static Optional<String> render(long taskId, List<DbChangeView> changes) {
        if (changes == null) return Optional.empty();
        StringBuilder executed = new StringBuilder();
        StringBuilder deferred = new StringBuilder();
        for (DbChangeView c : changes) {
            if (EXECUTED.equals(c.classification())) executed.append('\n').append(statement(c));
            else if (DEFERRED.equals(c.classification())) deferred.append('\n').append(statement(c));
        }
        if (executed.isEmpty() && deferred.isEmpty()) return Optional.empty();

        StringBuilder sb = new StringBuilder()
                .append("-- Symphony 작업 #").append(taskId).append(" DB 변경 기록 (워커가 생성 — 직접 고치지 마세요)\n")
                .append("-- 이 작업이 공용 개발 DB에 실행한 SQL(시간순)과 병합 뒤 실행할 승인 대기 SQL입니다. 운영 반영 때 사람이 읽고 적용하는 기록이며\n")
                .append("-- 마이그레이션 도구가 자동 실행하는 파일이 아닙니다. 재시도·수정 요청 때 워커가 이 작업의 전체 기록으로 다시 만듭니다.\n")
                .append(executed);
        if (!deferred.isEmpty()) {
            sb.append('\n').append(DEFERRED_TITLE)
              .append("-- 관리자가 PR 병합 뒤 작업 상세의 \"DB 변경\"에서 실행합니다. 같은 DB를 쓰는 다른 화면이 아직 옛 컬럼을 쓸 수 있습니다.\n")
              .append(deferred);
        }
        return Optional.of(sb.toString());
    }

    /**
     * 문장 하나 — 주석 줄(분류·시도 번호·연결 이름·수정 요청·기록 id)과 SQL. SQL 끝에 {@code ;}가 없으면 붙인다(마지막 줄이 {@code --} 주석이면
     * 다음 줄에). PR 본문 "DB 변경" 절의 승인 대기 목록도 같은 모양을 쓴다.
     */
    static String statement(DbChangeView c) {
        StringBuilder head = new StringBuilder("-- ").append(labelOf(c)).append(" 시도 ").append(c.attempt())
                .append(" · ").append(connectionOf(c));
        if (c.fixRequestId() != null) head.append(" · 수정 요청 #").append(c.fixRequestId());
        head.append(" · 기록 #").append(c.id()).append('\n');
        String sql = (c.sqlText() == null ? "" : c.sqlText()).replace("\r\n", "\n").replace('\r', '\n').strip();
        if (!sql.endsWith(";")) {
            String lastLine = sql.substring(sql.lastIndexOf('\n') + 1);
            sql += lastLine.contains("--") ? "\n;" : ";";
        }
        return head + sql + "\n";
    }

    /**
     * 실행 행인데 실행 시각이 없으면(사전 저장 뒤 마무리 저장이 실패한 행 — Task 11) 실행됐다고 단정하지 않는다. 승인 대기는 그대로.
     */
    private static String labelOf(DbChangeView c) {
        if (DEFERRED.equals(c.classification())) return "[승인 대기]";
        return c.executedAt() == null ? "[실행 여부 확인 필요 — 실행 결과가 기록되지 않음]" : "[실행됨]";
    }

    /** 주석 줄에 싣는 연결 이름 — 줄바꿈은 공백으로(주석을 벗어나 SQL 줄이 되지 않게), 비었으면 연결 id. */
    private static String connectionOf(DbChangeView c) {
        String name = c.connectionName() == null ? "" : c.connectionName().replaceAll("[\\r\\n]+", " ").strip();
        if (!name.isEmpty()) return name;
        return c.connectionId() == null ? "연결 없음" : "연결 #" + c.connectionId();
    }

    /**
     * worktree 안의 relPath에 UTF-8로 쓴다(폴더를 만들고 덮어쓴다). 경로가 worktree 밖이거나(절대 경로·{@code ..}·링크로 빠져나가는 폴더)
     * 그 자리가 링크면 쓰지 않고 IOException {@code 스크립트 경로가 worktree 밖입니다: …} — 세션은 권한 확인 없이 돌므로 링크를 만들어
     * 워커가 worktree 밖 파일을 덮어쓰게 할 수 있다.
     *
     * @return 쓴 파일
     */
    static Path write(Path worktree, String relPath, String content) throws IOException {
        Path root = worktree.toAbsolutePath().normalize();
        Path target = root.resolve(relPath).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw outside(relPath);
        Path realRoot = root.toRealPath();
        // 폴더를 만들기 전에 이미 있는 가장 가까운 상위 폴더부터 확인한다 — 링크 너머(worktree 밖)에 빈 폴더를 만들지 않게
        Path existing = target.getParent();
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null || !existing.toRealPath().startsWith(realRoot)) throw outside(relPath);
        Files.createDirectories(target.getParent());
        if (!target.getParent().toRealPath().startsWith(realRoot) || Files.isSymbolicLink(target)) {
            throw outside(relPath);
        }
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return target;
    }

    private static IOException outside(String relPath) {
        return new IOException("스크립트 경로가 worktree 밖입니다: " + relPath);
    }

    /**
     * 이번 시도의 쓰기가 있는가 — claude 실행 전 기준 id보다 큰 기록 중 실행됐거나(EXECUTED) 승인 대기로 남은(DEFERRED) 것(판정 F13).
     * 거부·실패·실행 안 함은 변경이 아니다. 시도 번호가 아니라 기준 id로 가르는 것은 수정 회차가 시도 번호를 공유하기 때문이다(계획 "설계와 다름").
     */
    static boolean wroteSince(List<DbChangeView> changes, long baselineId) {
        if (changes == null) return false;
        for (DbChangeView c : changes) {
            if (c.id() != null && c.id() > baselineId
                    && (EXECUTED.equals(c.classification()) || DEFERRED.equals(c.classification()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 커밋할 것이 있는가 (스펙 §6.6 성공 판정) — DB 작업은 이번 시도의 코드 변경이나 워커가 쓸 누적 스크립트 파일(1회차에 SQL을 다 적용하고
     * PR 생성에서 실패했으면 재시도가 할 일은 파일뿐이다), 그 밖의 타입·타입 없음은 이번 시도의 코드 변경이나 이번 시도의 쓰기 기록.
     */
    static boolean hasWork(TaskType type, boolean codeChanged, boolean wroteThisAttempt, boolean scriptWritten) {
        return type == TaskType.DB_WORK ? codeChanged || scriptWritten : codeChanged || wroteThisAttempt;
    }
}
