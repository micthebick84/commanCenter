package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.DbType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * DB 접속정보 API DTO (스펙 2026-10-02 §5.1). 브라우저로 나가는 View/ListResponse/Chip에는 비밀번호·암호문 필드가 없다.
 * host/databaseName/username 패턴은 JDBC URL 파라미터 주입(`db?allowLoadLocalInfile=true`, `host/evil`)을 막는다 —
 * DbType.jdbcUrl이 문자열 연결로 URL을 만든다. 요청 레코드의 toString은 비밀번호를 뺀다.
 */
public final class DbConnectionDto {

    public static final String HOST_PATTERN = "^[A-Za-z0-9._-]+$";
    public static final String DB_NAME_PATTERN = "^[A-Za-z0-9_$#.-]+$";
    public static final String USERNAME_PATTERN = "^[A-Za-z0-9_$#.@-]+$";

    private DbConnectionDto() {}

    public record View(long id, String scope, long repoCatalogId, String name, String dbType, String host, int port,
                       String databaseName, String username, boolean enabled, boolean mine) {
        public static View of(DbConnection c, String viewerId) {
            return new View(c.getId(), c.getScope().name(), c.getRepoCatalogId(), c.getName(), c.getDbType().name(),
                    c.getHost(), c.getPort(), c.getDatabaseName(), c.getUsername(), c.isEnabled(), c.isOwnedBy(viewerId));
        }
    }

    /** enabled=false면 기능 꺼짐(키 미설정) — 프론트가 DB 버튼을 숨긴다. */
    public record ListResponse(boolean enabled, List<View> items) {}

    /** 수정(PUT)에서는 scope·repoCatalogId를 무시한다(이동 불가). password는 생성 시 필수, 수정 시 blank = 기존 유지. */
    public record UpsertRequest(
            @NotNull DbConnectionScope scope,
            @NotNull Long repoCatalogId,
            @NotBlank @Size(max = 100) String name,
            @NotNull DbType dbType,
            @NotBlank @Size(max = 255) @Pattern(regexp = HOST_PATTERN, message = "host에는 영문·숫자·.-_만 쓸 수 있습니다") String host,
            @NotNull @Min(1) @Max(65535) Integer port,
            @NotBlank @Size(max = 255) @Pattern(regexp = DB_NAME_PATTERN, message = "DB/서비스명에 쓸 수 없는 문자가 있습니다") String databaseName,
            @NotBlank @Size(max = 255) @Pattern(regexp = USERNAME_PATTERN, message = "사용자명에 쓸 수 없는 문자가 있습니다") String username,
            @Size(max = 1000) String password,
            Boolean enabled
    ) {
        @Override
        public String toString() {
            return "UpsertRequest[scope=" + scope + ", repo=" + repoCatalogId + ", name=" + name + ", " + dbType
                    + " " + host + ":" + port + "/" + databaseName + " user=" + username + "]";
        }
    }

    /** id가 있으면 저장된 값에 폼 값을 덮어쓴다(password blank = 저장값). id가 없으면 모든 필드 필수(서비스 검증). */
    public record TestRequest(
            Long id,
            DbType dbType,
            @Size(max = 255) @Pattern(regexp = HOST_PATTERN, message = "host에는 영문·숫자·.-_만 쓸 수 있습니다") String host,
            @Min(1) @Max(65535) Integer port,
            @Size(max = 255) @Pattern(regexp = DB_NAME_PATTERN, message = "DB/서비스명에 쓸 수 없는 문자가 있습니다") String databaseName,
            @Size(max = 255) @Pattern(regexp = USERNAME_PATTERN, message = "사용자명에 쓸 수 없는 문자가 있습니다") String username,
            @Size(max = 1000) String password
    ) {
        @Override
        public String toString() {
            return "TestRequest[id=" + id + ", " + dbType + " " + host + ":" + port + "/" + databaseName + " user=" + username + "]";
        }
    }

    public record TestResult(boolean ok, String message) {}

    /** 질문 화면 칩 — 현재 행 기준(삭제된 id는 빠진다). dbType은 enum 이름. */
    public record Chip(long id, String name, String dbType) {}
}
