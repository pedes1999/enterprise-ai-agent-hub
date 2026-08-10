package com.enterprisehub.gateway.audit;

import com.enterprisehub.runtime.audit.ToolExecutionAuditRecord;
import com.enterprisehub.runtime.audit.ToolExecutionListener;
import com.enterprisehub.gateway.entity.ToolExecution;
import com.enterprisehub.gateway.repository.ToolExecutionRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * gateway-api's implementation of agent-runtime's ToolExecutionListener SPI
 * -- persists to tool_executions. Called synchronously, on the same thread
 * (and therefore same request/TenantContext) that handled the original
 * HTTP request, since today's callers (AgentPingService) invoke tools
 * synchronously within one request. Once tool execution goes async (a job
 * queue, Weeks 9-10), this needs the tenant context set explicitly before
 * the save -- it will NOT be picked up automatically the way it is today,
 * exactly the class of bug TenantAwareDataSource's javadoc warns about.
 */
@Component
public class JpaToolExecutionListener implements ToolExecutionListener {

    private final ToolExecutionRepository repository;

    public JpaToolExecutionListener(ToolExecutionRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onToolExecuted(ToolExecutionAuditRecord record) {
        ToolExecution entity = new ToolExecution();
        entity.setTenantId(UUID.fromString(record.tenantId()));
        entity.setExecutionId(record.executionId());
        entity.setToolName(record.toolName());
        entity.setDurationMs(record.duration().toMillis());
        entity.setOutcome(record.outcome().name());
        entity.setErrorMessage(record.errorMessage());
        repository.save(entity);
    }
}
