package com.enterprisehub.gateway.repository;

import com.enterprisehub.gateway.entity.AgentDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentDefinitionRepository extends JpaRepository<AgentDefinition, UUID> {

    Optional<AgentDefinition> findBySlugAndActiveTrue(String slug);

    List<AgentDefinition> findByActiveTrue();
}
