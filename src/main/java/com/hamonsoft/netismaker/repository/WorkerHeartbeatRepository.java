package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.WorkerHeartbeat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkerHeartbeatRepository extends JpaRepository<WorkerHeartbeat, String> {
}
