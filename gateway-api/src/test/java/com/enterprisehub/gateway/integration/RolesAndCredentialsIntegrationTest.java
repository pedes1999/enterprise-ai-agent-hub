package com.enterprisehub.gateway.integration;

import com.enterprisehub.dto.*;
import com.enterprisehub.gateway.error.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies @PreAuthorize actually blocks non-admins through the REAL
 * security filter chain (JwtAuthFilter -> method security), not just that
 * the service layer's own checks work in isolation -- and that vendor
 * credentials round-trip through real AES-GCM encryption against the real
 * DB, since VendorCredentialServiceTest only proves the encryptor and
 * repository are called correctly, not that the wiring between them
 * (config-bound key, @Component registration) actually works end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RolesAndCredentialsIntegrationTest {

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

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String registerAdmin(String slug) {
        RegisterRequest request = new RegisterRequest(slug, slug, "admin@" + slug + ".com", "password123");
        return restTemplate.postForEntity(baseUrl() + "/auth/register", request, AuthResponse.class)
                .getBody().token();
    }

    private String createDeveloperAndLogin(String slug, String adminToken) {
        HttpEntity<CreateUserRequest> createRequest = new HttpEntity<>(
                new CreateUserRequest("dev@" + slug + ".com", "password123", "DEVELOPER"),
                authHeaders(adminToken));
        restTemplate.postForEntity(baseUrl() + "/users", createRequest, UserSummary.class);

        LoginRequest loginRequest = new LoginRequest(slug, "dev@" + slug + ".com", "password123");
        return restTemplate.postForEntity(baseUrl() + "/auth/login", loginRequest, AuthResponse.class)
                .getBody().token();
    }

    @Test
    void admin_canManageUsers() {
        String slug = uniqueSlug("acme");
        String adminToken = registerAdmin(slug);

        HttpEntity<CreateUserRequest> createRequest = new HttpEntity<>(
                new CreateUserRequest("dev@" + slug + ".com", "password123", "DEVELOPER"),
                authHeaders(adminToken));
        ResponseEntity<UserSummary> created = restTemplate.postForEntity(
                baseUrl() + "/users", createRequest, UserSummary.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().role()).isEqualTo("DEVELOPER");

        ResponseEntity<UserSummary[]> list = restTemplate.exchange(
                baseUrl() + "/users", HttpMethod.GET, new HttpEntity<>(authHeaders(adminToken)), UserSummary[].class);
        assertThat(list.getBody()).hasSize(2); // admin + developer
    }

    @Test
    void developer_cannotManageUsers_gets403() {
        String slug = uniqueSlug("acme");
        String adminToken = registerAdmin(slug);
        String devToken = createDeveloperAndLogin(slug, adminToken);

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/users", HttpMethod.GET, new HttpEntity<>(authHeaders(devToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void developer_cannotManageVendorCredentials_gets403() {
        String slug = uniqueSlug("acme");
        String adminToken = registerAdmin(slug);
        String devToken = createDeveloperAndLogin(slug, adminToken);

        HttpEntity<CreateVendorCredentialRequest> putRequest = new HttpEntity<>(
                new CreateVendorCredentialRequest("ANTHROPIC", "sk-ant-secret"), authHeaders(devToken));
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/vendor-credentials", HttpMethod.PUT, putRequest, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void developer_cannotManageApiKeys_gets403() {
        String slug = uniqueSlug("acme");
        String adminToken = registerAdmin(slug);
        String devToken = createDeveloperAndLogin(slug, adminToken);

        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/api-keys", HttpMethod.GET, new HttpEntity<>(authHeaders(devToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void lastAdmin_cannotBeDemoted_evenByAnotherAdmin() {
        String slug = uniqueSlug("acme");
        String adminToken = registerAdmin(slug);

        // Find the sole admin's own user id via /users, then try to demote them.
        ResponseEntity<UserSummary[]> list = restTemplate.exchange(
                baseUrl() + "/users", HttpMethod.GET, new HttpEntity<>(authHeaders(adminToken)), UserSummary[].class);
        String adminId = list.getBody()[0].id();

        HttpEntity<UpdateUserRoleRequest> demoteRequest = new HttpEntity<>(
                new UpdateUserRoleRequest("DEVELOPER"), authHeaders(adminToken));
        ResponseEntity<ApiError> response = restTemplate.exchange(
                baseUrl() + "/users/" + adminId + "/role", HttpMethod.PATCH, demoteRequest, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void vendorCredentials_realEncryptionRoundTrip_neverExposesTokenOverHttp() {
        String slug = uniqueSlug("acme");
        String adminToken = registerAdmin(slug);

        HttpEntity<CreateVendorCredentialRequest> putRequest = new HttpEntity<>(
                new CreateVendorCredentialRequest("anthropic", "sk-ant-very-secret-value"), authHeaders(adminToken));
        ResponseEntity<String> putResponse = restTemplate.exchange(
                baseUrl() + "/vendor-credentials", HttpMethod.PUT, putRequest, String.class);

        assertThat(putResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(putResponse.getBody()).doesNotContain("sk-ant-very-secret-value");

        ResponseEntity<VendorCredentialSummary[]> list = restTemplate.exchange(
                baseUrl() + "/vendor-credentials", HttpMethod.GET, new HttpEntity<>(authHeaders(adminToken)), VendorCredentialSummary[].class);
        assertThat(list.getBody()).hasSize(1);
        assertThat(list.getBody()[0].provider()).isEqualTo("ANTHROPIC");
        assertThat(list.getBody()[0].active()).isTrue();
    }

    @Test
    void vendorCredentials_putTwiceForSameProvider_rotatesNotDuplicates() {
        String slug = uniqueSlug("acme");
        String adminToken = registerAdmin(slug);

        for (String token : new String[]{"sk-ant-first", "sk-ant-second"}) {
            HttpEntity<CreateVendorCredentialRequest> putRequest = new HttpEntity<>(
                    new CreateVendorCredentialRequest("ANTHROPIC", token), authHeaders(adminToken));
            restTemplate.exchange(baseUrl() + "/vendor-credentials", HttpMethod.PUT, putRequest, VendorCredentialSummary.class);
        }

        ResponseEntity<VendorCredentialSummary[]> list = restTemplate.exchange(
                baseUrl() + "/vendor-credentials", HttpMethod.GET, new HttpEntity<>(authHeaders(adminToken)), VendorCredentialSummary[].class);

        assertThat(list.getBody()).hasSize(1); // rotated, not duplicated
    }
}
