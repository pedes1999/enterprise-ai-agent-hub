package com.enterprisehub.gateway.webhook;

import com.enterprisehub.gateway.agent.EnqueueExecutionCommand;
import com.enterprisehub.gateway.entity.WebhookEndpoint;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Turns a verified GitHub pull_request event into the same
 * {@link EnqueueExecutionCommand} the authenticated /agents/execute path
 * builds. That reuse is the point: a webhook-triggered run is an ordinary
 * queued execution in every respect -- same validation, same per-tenant
 * concurrency cap, same worker, same cancellation and streaming -- and
 * differs only in trigger_source and in who it runs as.
 */
@Component
public class GitHubEventMapper {

    /**
     * GitHub fires pull_request for a dozen actions (labeled, assigned,
     * review_requested, closed, ...). Acting on all of them would mean an
     * agent run every time someone adds a label. These three are the ones
     * where the code under review actually changed:
     *   opened      -- the PR now exists
     *   reopened    -- it's live again
     *   synchronize -- new commits were pushed to the head branch
     * Anything else is acknowledged and ignored, not rejected: it IS a
     * legitimate delivery, there is just nothing to do with it.
     */
    private static final Set<String> ACTIONABLE = Set.of("opened", "reopened", "synchronize");

    /**
     * @return empty when the event is well-formed but not one this endpoint
     *         acts on -- the caller answers 200, since a 4xx would make
     *         GitHub's delivery log show failures for perfectly normal events.
     */
    public Optional<EnqueueExecutionCommand> toCommand(GitHubPullRequestEvent event, WebhookEndpoint endpoint) {
        if (event == null || event.action() == null || !ACTIONABLE.contains(event.action())) {
            return Optional.empty();
        }

        GitHubPullRequestEvent.PullRequest pullRequest = event.pullRequest();
        GitHubPullRequestEvent.Repository repository = event.repository();
        if (pullRequest == null || repository == null || repository.cloneUrl() == null) {
            // Signature verified, so this genuinely came from GitHub -- a
            // payload missing these is a shape this app doesn't understand
            // rather than an attack. 400 (not 500) and GitHub shows it as a
            // failed delivery the repo admin can see.
            throw new WebhookException(HttpStatus.BAD_REQUEST,
                    "pull_request payload is missing pull_request/repository details");
        }

        return Optional.of(EnqueueExecutionCommand.forAgent(endpoint.getTenantId(), endpoint.getAgentSlug())
                .prompt(buildPrompt(event, pullRequest, repository))
                .repository(repository.cloneUrl(), headRef(pullRequest))
                // Not the caller's choice -- the endpoint records whose vendor
                // credential pays for unattended runs. See WebhookEndpoint.
                .triggeredBy(endpoint.getRunAsUserId())
                .triggerSource("WEBHOOK")
                .build());
    }

    private String headRef(GitHubPullRequestEvent.PullRequest pullRequest) {
        return pullRequest.head() == null ? null : pullRequest.head().ref();
    }

    /**
     * Deliberately plain description rather than instructions: WHAT the agent
     * should do with a pull request is the agent definition's system prompt
     * (see V16/V18 for test-fixer's), and duplicating task instructions here
     * would mean two places to change and two chances to contradict each other.
     * This supplies only the facts of the event.
     */
    private String buildPrompt(GitHubPullRequestEvent event,
                                GitHubPullRequestEvent.PullRequest pullRequest,
                                GitHubPullRequestEvent.Repository repository) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("A pull request was ").append(event.action())
                .append(" on ").append(repository.fullName() == null ? repository.cloneUrl() : repository.fullName())
                .append(".\n\n");

        if (event.number() != null) {
            prompt.append("Pull request: #").append(event.number()).append('\n');
        }
        if (pullRequest.title() != null) {
            prompt.append("Title: ").append(pullRequest.title()).append('\n');
        }
        String head = headRef(pullRequest);
        if (head != null) {
            prompt.append("Head branch: ").append(head).append('\n');
        }
        if (pullRequest.base() != null && pullRequest.base().ref() != null) {
            prompt.append("Base branch: ").append(pullRequest.base().ref()).append('\n');
        }
        if (pullRequest.htmlUrl() != null) {
            prompt.append("URL: ").append(pullRequest.htmlUrl()).append('\n');
        }
        if (pullRequest.body() != null && !pullRequest.body().isBlank()) {
            prompt.append("\nDescription:\n").append(pullRequest.body().strip()).append('\n');
        }
        return prompt.toString();
    }
}
