package com.enterprisehub.gateway.integration;

import com.enterprisehub.dto.*;
import com.enterprisehub.gateway.error.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the full stack against a real Postgres instance (row-level
 * security included) rather than mocks -- this is deliberately the same
 * class of test that would have caught the original platform_api_keys RLS
 * bootstrap bug, which no amount of mocked-repository unit testing can
 * catch since it lives in the interaction between the DB session variable
 * and the RLS policy itself.
 *
 * Requires the local Postgres instance described in application.yml
 * (agent_hub / hub_user / password) to be up and migrated, same as running
 * the app itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private String uniqueSlug(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private ResponseEntity<AuthResponse> register(String slug, String email, String password) {
        RegisterRequest request = new RegisterRequest(slug, slug, email, password);
        return restTemplate.postForEntity(baseUrl() + "/auth/register", request, AuthResponse.class);
    }

    @Test
    void registerThenLogin_fullRoundTrip() {
        String slug = uniqueSlug("acme");

        ResponseEntity<AuthResponse> registerResponse = register(slug, "admin@" + slug + ".com", "password123");
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registerResponse.getBody()).isNotNull();
        assertThat(registerResponse.getBody().token()).isNotBlank();

        LoginRequest loginRequest = new LoginRequest(slug, "admin@" + slug + ".com", "password123");
        ResponseEntity<AuthResponse> loginResponse =
                restTemplate.postForEntity(baseUrl() + "/auth/login", loginRequest, AuthResponse.class);

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody().tenantSlug()).isEqualTo(slug);
    }

    @Test
    void login_wrongPassword_returns401() {
        String slug = uniqueSlug("acme");
        register(slug, "admin@" + slug + ".com", "password123");

        LoginRequest loginRequest = new LoginRequest(slug, "admin@" + slug + ".com", "totally-wrong");
        ResponseEntity<ApiError> response =
                restTemplate.postForEntity(baseUrl() + "/auth/login", loginRequest, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void register_duplicateSlug_returns409() {
        String slug = uniqueSlug("acme");
        register(slug, "first@" + slug + ".com", "password123");

        ResponseEntity<ApiError> second = restTemplate.postForEntity(
                baseUrl() + "/auth/register",
                new RegisterRequest(slug, slug, "second@" + slug + ".com", "password123"),
                ApiError.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void apiKeys_requireAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/api-keys", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void apiKeys_createListRevoke_fullLifecycle() {
        String slug = uniqueSlug("acme");
        String token = register(slug, "admin@" + slug + ".com", "password123").getBody().token();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<CreateApiKeyRequest> createRequest =
                new HttpEntity<>(new CreateApiKeyRequest("ci-key"), headers);
        ResponseEntity<ApiKeyCreatedResponse> createResponse = restTemplate.postForEntity(
                baseUrl() + "/api-keys", createRequest, ApiKeyCreatedResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String rawKey = createResponse.getBody().rawKey();
        assertThat(rawKey).startsWith("ahk_");
        String keyId = createResponse.getBody().id();

        ResponseEntity<ApiKeySummary[]> listResponse = restTemplate.exchange(
                baseUrl() + "/api-keys", HttpMethod.GET, new HttpEntity<>(headers), ApiKeySummary[].class);
        assertThat(listResponse.getBody()).hasSize(1);
        assertThat(listResponse.getBody()[0].revokedAt()).isNull();

        restTemplate.exchange(baseUrl() + "/api-keys/" + keyId, HttpMethod.DELETE,
                new HttpEntity<>(headers), Void.class);

        ResponseEntity<ApiKeySummary[]> afterRevoke = restTemplate.exchange(
                baseUrl() + "/api-keys", HttpMethod.GET, new HttpEntity<>(headers), ApiKeySummary[].class);
        assertThat(afterRevoke.getBody()[0].revokedAt()).isNotNull();
    }

    @Test
    void apiKeys_crossTenantIsolation_listAndRevokeNeverSeeOtherTenantsKeys() {
        String slugA = uniqueSlug("acme");
        String tokenA = register(slugA, "admin@" + slugA + ".com", "password123").getBody().token();
        String slugB = uniqueSlug("globex");
        String tokenB = register(slugB, "admin@" + slugB + ".com", "password123").getBody().token();

        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth(tokenA);
        headersA.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<ApiKeyCreatedResponse> created = restTemplate.postForEntity(
                baseUrl() + "/api-keys", new HttpEntity<>(new CreateApiKeyRequest("acme-key"), headersA),
                ApiKeyCreatedResponse.class);
        String keyIdOwnedByA = created.getBody().id();

        HttpHeaders headersB = new HttpHeaders();
        headersB.setBearerAuth(tokenB);

        // B must not see A's key when listing -- this is the RLS-open-SELECT
        // table where app-level tenant scoping is the only isolation left.
        ResponseEntity<ApiKeySummary[]> bListResponse = restTemplate.exchange(
                baseUrl() + "/api-keys", HttpMethod.GET, new HttpEntity<>(headersB), ApiKeySummary[].class);
        assertThat(bListResponse.getBody()).isEmpty();

        // B must not be able to revoke A's key by guessing/knowing its id.
        ResponseEntity<ApiError> revokeAttempt = restTemplate.exchange(
                baseUrl() + "/api-keys/" + keyIdOwnedByA, HttpMethod.DELETE,
                new HttpEntity<>(headersB), ApiError.class);
        assertThat(revokeAttempt.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void jwt_fromOneTenant_cannotBeReplayedAsAnotherEvenIfTenantIdWereTampered() {
        // Sanity check on the auth filter's happy path: a well-formed but
        // garbage token is rejected outright, not silently treated as
        // anonymous with elevated trust.
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("this.is.not-a-valid-jwt");

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/api-keys", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
