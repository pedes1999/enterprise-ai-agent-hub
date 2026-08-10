package com.enterprisehub.gateway.audit;

import com.enterprisehub.gateway.entity.ToolExecution;
import com.enterprisehub.gateway.repository.ToolExecutionRepository;
import com.enterprisehub.runtime.audit.ToolExecutionAuditRecord;
import com.enterprisehub.runtime.audit.ToolExecutionOutcome;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JpaToolExecutionListenerTest {

    @Test
    void onToolExecuted_success_mapsEveryFieldOntoTheEntity() {
        ToolExecutionRepository repository = mock(ToolExecutionRepository.class);
        JpaToolExecutionListener listener = new JpaToolExecutionListener(repository);
        UUID tenantId = UUID.randomUUID();

        listener.onToolExecuted(new ToolExecutionAuditRecord(
                tenantId.toString(), "exec-1", "run_shell_command",
                Duration.ofMillis(250), ToolExecutionOutcome.SUCCESS, null));

        ArgumentCaptor<ToolExecution> captor = ArgumentCaptor.forClass(ToolExecution.class);
        verify(repository).save(captor.capture());
        ToolExecution saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getExecutionId()).isEqualTo("exec-1");
        assertThat(saved.getToolName()).isEqualTo("run_shell_command");
        assertThat(saved.getDurationMs()).isEqualTo(250);
        assertThat(saved.getOutcome()).isEqualTo("SUCCESS");
        assertThat(saved.getErrorMessage()).isNull();
    }

    @Test
    void onToolExecuted_failure_persistsErrorMessage() {
        ToolExecutionRepository repository = mock(ToolExecutionRepository.class);
        JpaToolExecutionListener listener = new JpaToolExecutionListener(repository);

        listener.onToolExecuted(new ToolExecutionAuditRecord(
                UUID.randomUUID().toString(), "exec-2", "run_shell_command",
                Duration.ofMillis(10), ToolExecutionOutcome.FAILURE, "sidecar unreachable"));

        ArgumentCaptor<ToolExecution> captor = ArgumentCaptor.forClass(ToolExecution.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getOutcome()).isEqualTo("FAILURE");
        assertThat(captor.getValue().getErrorMessage()).isEqualTo("sidecar unreachable");
    }
}
