package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "worker_heartbeat", schema = "com")
@Getter
@Setter
@NoArgsConstructor
public class WorkerHeartbeat {

    @Id
    @Column(name = "worker_id", length = 50)
    private String workerId;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(length = 100)
    private String hostname;

    @Column(length = 50)
    private String version;

    @Column(name = "claude_session_ok")
    private Boolean claudeSessionOk;

    /** claude 로그인 판정 방식 ENV_TOKEN|CREDENTIALS|CLI (V37). */
    @Column(name = "claude_auth_method", length = 20)
    private String claudeAuthMethod;

    /** CLI 로그인의 refresh token 만료 시각 — setup-token·CLI 확인이면 null (V37). */
    @Column(name = "claude_auth_expires_at")
    private OffsetDateTime claudeAuthExpiresAt;

    @Column(name = "vpn_status", length = 20)
    private String vpnStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mcps", nullable = false, columnDefinition = "jsonb")
    private List<String> mcps = new ArrayList<>();

    @Column(name = "lost_report_count", nullable = false)
    private Integer lostReportCount = 0;

    /** 재전송을 포기해 워커 PC의 .dead.jsonl에 보관만 된 결과 보고 수 — 사람이 확인해야 한다. */
    @Column(name = "dead_report_count", nullable = false)
    private Integer deadReportCount = 0;
}
