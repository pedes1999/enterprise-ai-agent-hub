package com.enterprisehub.gateway.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The handful of fields this app actually uses out of GitHub's pull_request
 * payload, which is several hundred lines of mostly-irrelevant JSON.
 *
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} everywhere is
 * load-bearing rather than defensive tidiness: GitHub adds fields to these
 * payloads over time, and a strict mapping would turn a routine upstream
 * addition into every delivery failing. Fields are named explicitly with
 * @JsonProperty rather than relying on a global snake_case strategy, so this
 * mapping doesn't depend on ObjectMapper configuration that other parts of
 * the app might reasonably change.
 *
 * Parsed only AFTER the signature verifies -- see WebhookIngestService.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubPullRequestEvent(
        String action,
        Integer number,
        @JsonProperty("pull_request") PullRequest pullRequest,
        Repository repository) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequest(
            String title,
            String body,
            @JsonProperty("html_url") String htmlUrl,
            Ref head,
            Ref base) {
    }

    /** Both head and base are the same shape in GitHub's payload; only head.ref is used today. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ref(String ref) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(
            @JsonProperty("full_name") String fullName,
            /*
             * clone_url, not ssh_url or html_url: GitCloneTool clones over
             * HTTPS with a token, and this is the only one of the three that
             * is directly usable for that.
             */
            @JsonProperty("clone_url") String cloneUrl) {
    }
}
