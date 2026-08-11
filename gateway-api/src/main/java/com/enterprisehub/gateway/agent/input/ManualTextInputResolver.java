package com.enterprisehub.gateway.agent.input;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * The simplest possible InputSourceResolver: the "resolved" text is just
 * whatever the caller passed directly in inputParameters -- no external
 * fetch, no API call. Exists so InputSourceResolver/AgentPromptRunner's
 * wiring can be built and tested end to end today, without waiting on a
 * real Jira (or similar) integration.
 */
@Component
public class ManualTextInputResolver implements InputSourceResolver {

    private static final String TEXT_PARAM = "text";

    @Override
    public String sourceType() {
        return "MANUAL_TEXT";
    }

    @Override
    public String resolve(UUID tenantId, Map<String, String> parameters) {
        String text = parameters == null ? null : parameters.get(TEXT_PARAM);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(
                    "MANUAL_TEXT input source requires inputParameters['" + TEXT_PARAM + "']");
        }
        return text;
    }
}
