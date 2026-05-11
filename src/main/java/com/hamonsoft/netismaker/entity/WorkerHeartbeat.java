package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

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

    @Column(name = "vpn_status", length = 20)
    private String vpnStatus;
}
