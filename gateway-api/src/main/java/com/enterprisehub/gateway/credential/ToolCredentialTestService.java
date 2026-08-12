package com.enterprisehub.gateway.credential;

import com.enterprisehub.dto.CredentialTestResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

/**
 * Backs POST /tool-credentials/test. Only GITHUB is supported in v1 -- GIT
 * is a generic clone-auth credential with no fixed target to validate
 * against (no specific repo known at test-connection time), so it's
 * skipped entirely rather than half-validated.
 *
 * The check hits GET https://api.github.com/user -- confirms the token is
 * valid and unrevoked, nothing more. It deliberately does NOT confirm
 * repo/PR scope: fine-grained PATs (what this platform's docs already
 * assume GITHUB credentials are) don't return the X-OAuth-Scopes header
 * classic PATs do, so scope verification isn't cheaply possible here --
 * only actually using the token for a real git/PR operation proves that.
 */
@Service
public class ToolCredentialTestService {

    private static final String GITHUB_USER_URL = "https://api.github.com/user";

    private final ToolCredentialService toolCredentialService;
    private final RestClient restClient;

    public ToolCredentialTestService(ToolCredentialService toolCredentialService, RestClient.Builder restClientBuilder) {
        this.toolCredentialService = toolCredentialService;
        this.restClient = restClientBuilder.build();
    }

    public CredentialTestResult test(UUID tenantId, String credentialKindValue) {
        ToolCredentialKind kind = ToolCredentialKind.parse(credentialKindValue)
                .orElseThrow(() -> new ToolCredentialException(HttpStatus.BAD_REQUEST,
                        "credentialKind must be one of GIT, GITHUB"));

        if (kind != ToolCredentialKind.GITHUB) {
            return new CredentialTestResult(false, "Test connection is not supported for " + kind.name() + " yet.");
        }

        String token = toolCredentialService.decryptActiveValue(tenantId, kind.name())
                .orElseThrow(() -> new ToolCredentialException(HttpStatus.NOT_FOUND,
                        "No active credential stored for kind " + kind.name()));

        try {
            restClient.get()
                    .uri(GITHUB_USER_URL)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            return new CredentialTestResult(false,
                    "GitHub rejected this token (status " + e.getStatusCode().value() + ").");
        } catch (RestClientException e) {
            return new CredentialTestResult(false, "Could not reach GitHub: " + e.getMessage());
        }

        toolCredentialService.markValidated(tenantId, kind.name());
        return new CredentialTestResult(true, "GitHub token is valid.");
    }
}
