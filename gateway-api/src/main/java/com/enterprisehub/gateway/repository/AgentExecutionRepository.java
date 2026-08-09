package com.enterprisehub.gateway.repository;

import com.enterprisehub.gateway.entity.AgentExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AgentExecutionRepository extends JpaRepository<AgentExecution, UUID> {
}
