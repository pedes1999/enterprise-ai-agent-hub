package com.enterprisehub.gateway.agent.tools;

import com.enterprisehub.core.tool.AgentTool;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * A trivial, stateless tool that exists purely to prove the tool-calling
 * loop works end to end (ToolSpecification built from an AgentTool -> the
 * model decides to call it -> arguments parsed from its JSON response ->
 * this executes -> result fed back for a final answer). Not a stand-in for
 * anything agent-runtime will provide -- filesystem/terminal/git tools
 * (Weeks 6-8) are the real payload.
 */
public class CurrentDateTimeTool implements AgentTool {

    @Override
    public String name() {
        return "get_current_date_time";
    }

    @Override
    public String description() {
        return "Returns the current date and time for a given IANA timezone id. "
                + "Use this whenever asked what the current date or time is.";
    }

    @Override
    public Map<String, String> parameterDescriptions() {
        return Map.of("timezone", "IANA timezone id, e.g. 'UTC' or 'Europe/Athens'. Defaults to UTC if omitted or invalid.");
    }

    @Override
    public String execute(Map<String, String> arguments) {
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(arguments.getOrDefault("timezone", "UTC"));
        } catch (Exception e) {
            zoneId = ZoneOffset.UTC;
        }
        return ZonedDateTime.now(zoneId).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
