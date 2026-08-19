package com.enterprisehub.gateway.webhook;

import com.enterprisehub.gateway.agent.EnqueueExecutionCommand;
import com.enterprisehub.gateway.entity.WebhookEndpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubEventMapperTest {

    private final GitHubEventMapper mapper = new GitHubEventMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID RUN_AS_USER_ID = UUID.randomUUID();

    /**
     * Trimmed from a real GitHub delivery -- the shape, field names and
     * nesting are GitHub's, only the irrelevant several hundred lines are
     * gone. Parsed through a real ObjectMapper rather than hand-built
     * records, so this also covers the @JsonProperty mapping.
     */
    private static final String PULL_REQUEST_PAYLOAD = """
            {
              "action": "opened",
              "number": 42,
              "pull_request": {
                "title": "Fix the flaky retry test",
                "body": "The retry helper sleeps for a fixed 100ms.",
                "html_url": "https://github.com/acme/widgets/pull/42",
                "head": { "ref": "fix-flaky-retry" },
                "base": { "ref": "main" },
                "draft": false
              },
              "repository": {
                "full_name": "acme/widgets",
                "clone_url": "https://github.com/acme/widgets.git",
                "html_url": "https://github.com/acme/widgets"
              },
              "sender": { "login": "someone" }
            }
            """;

    private WebhookEndpoint endpoint() {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setId(UUID.randomUUID());
        endpoint.setTenantId(TENANT_ID);
        endpoint.setAgentSlug("test-fixer");
        endpoint.setRunAsUserId(RUN_AS_USER_ID);
        return endpoint;
    }

    private GitHubPullRequestEvent parse(String json) {
        try {
            return objectMapper.readValue(json.getBytes(StandardCharsets.UTF_8), GitHubPullRequestEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void mapsAnOpenedPullRequestOntoTheEndpointsAgent() {
        Optional<EnqueueExecutionCommand> command = mapper.toCommand(parse(PULL_REQUEST_PAYLOAD), endpoint());

        assertThat(command).isPresent();
        EnqueueExecutionCommand actual = command.get();
        assertThat(actual.tenantId()).isEqualTo(TENANT_ID);
        assertThat(actual.agentSlug()).isEqualTo("test-fixer");
        // clone_url, not html_url or ssh_url -- the only one GitCloneTool can use.
        assertThat(actual.repositoryUrl()).isEqualTo("https://github.com/acme/widgets.git");
        // head.ref: the branch the PR proposes, not the branch it targets.
        assertThat(actual.repositoryBranch()).isEqualTo("fix-flaky-retry");
    }

    /**
     * The attribution decision the schema is built around: an unattended run
     * spends the endpoint's recorded user's vendor credential, because
     * AgentPromptRunner.resolveApiKey() rejects a null user outright.
     */
    @Test
    void attributesTheRunToTheEndpointsRunAsUserAndMarksItAsWebhookTriggered() {
        EnqueueExecutionCommand command = mapper.toCommand(parse(PULL_REQUEST_PAYLOAD), endpoint()).orElseThrow();

        assertThat(command.triggeredBy()).isEqualTo(RUN_AS_USER_ID);
        assertThat(command.triggerSource()).isEqualTo("WEBHOOK");
    }

    @Test
    void promptCarriesTheFactsOfTheEvent() {
        EnqueueExecutionCommand command = mapper.toCommand(parse(PULL_REQUEST_PAYLOAD), endpoint()).orElseThrow();

        assertThat(command.prompt())
                .contains("acme/widgets")
                .contains("#42")
                .contains("Fix the flaky retry test")
                .contains("fix-flaky-retry")
                .contains("https://github.com/acme/widgets/pull/42")
                .contains("The retry helper sleeps for a fixed 100ms.");
    }

    /**
     * GitHub fires pull_request for a dozen actions. Running an agent every
     * time someone adds a label would be both expensive and useless.
     */
    @Test
    void ignoresActionsThatDoNotChangeTheCode() {
        String labeled = PULL_REQUEST_PAYLOAD.replace("\"action\": \"opened\"", "\"action\": \"labeled\"");

        assertThat(mapper.toCommand(parse(labeled), endpoint())).isEmpty();
    }

    @Test
    void actsOnReopenedAndSynchronizeToo() {
        for (String action : new String[]{"reopened", "synchronize"}) {
            String payload = PULL_REQUEST_PAYLOAD.replace("\"action\": \"opened\"", "\"action\": \"" + action + "\"");

            assertThat(mapper.toCommand(parse(payload), endpoint()))
                    .as("action %s should trigger a run", action)
                    .isPresent();
        }
    }

    /** GitHub adds fields to these payloads over time; a strict mapping would break on the next one. */
    @Test
    void toleratesUnknownFieldsInThePayload() {
        String withNewField = PULL_REQUEST_PAYLOAD.replace(
                "\"number\": 42,", "\"number\": 42, \"some_field_github_added_later\": {\"nested\": true},");

        assertThat(mapper.toCommand(parse(withNewField), endpoint())).isPresent();
    }

    @Test
    void rejectsAnActionablePayloadMissingTheRepository() {
        String noRepository = """
                { "action": "opened", "number": 1, "pull_request": { "title": "x", "head": { "ref": "b" } } }
                """;

        assertThatThrownBy(() -> mapper.toCommand(parse(noRepository), endpoint()))
                .isInstanceOf(WebhookException.class)
                .hasMessageContaining("missing");
    }
}
