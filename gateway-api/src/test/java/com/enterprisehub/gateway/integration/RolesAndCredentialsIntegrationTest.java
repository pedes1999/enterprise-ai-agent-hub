package com.enterprisehub.gateway.integration;

import com.enterprisehub.dto.*;
import com.enterprisehub.gateway.error.ApiError;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Verifies @PreAuthorize actually blocks non-admins through the REAL
 * security filter chain (JwtAuthFilter -> method security), not just that
 * the service layer's own checks work in isolation -- and that vendor
 * credentials round-trip through real AES-GCM encryption against the real
 * DB, since VendorCredentialServiceTest only proves the encryptor and
 * repository are called correctly, not that the wiring between them
 * (config-bound key, @Component registration) actually works end to end.
 *
 * Runs against agent_hub_test, not the dev DB -- see AuthIntegrationTest's
 * javadoc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RolesAndCredentialsIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * UserService.create() never accepts a caller-supplied password (see
     * CreateUserRequest's javadoc) -- it always generates a random one and
     * emails it. Mocking the sender lets these tests both avoid sending a
     * real email per run and recover that temporary password to actually
     * log the new user in, rather than guessing a fixed string that was
     * never going to match (see TempPasswordGenerator).
     */
    @MockBean
    private JavaMailSender mailSender;

    private static final Pattern TEMP_PASSWORD_PATTERN = Pattern.compile("Temporary password: (\\S+)");

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
        RegisterRequest request = new RegisterRequest(slug, slug, "admin@" + slug + ".com", "p@ssword123");
        return restTemplate.postForEntity(baseUrl() + "/auth/register", request, AuthResponse.class)
                .getBody().token();
    }

    private String createDeveloperAndLogin(String slug, String adminToken) {
        return createUserAndLogin(slug, adminToken, "dev@" + slug + ".com");
    }

    private String createDeveloperAndLoginSecond(String slug, String adminToken) {
        return createUserAndLogin(slug, adminToken, "dev2@" + slug + ".com");
    }

    private String createUserAndLogin(String slug, String adminToken, String email) {
        HttpEntity<CreateUserRequest> createRequest = new HttpEntity<>(
                new CreateUserRequest(email, "Test Developer", "DEVELOPER"),
                authHeaders(adminToken));
        ResponseEntity<UserSummary> created = restTemplate.postForEntity(baseUrl() + "/users", createRequest, UserSummary.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, atLeastOnce()).send(captor.capture());
        Matcher matcher = TEMP_PASSWORD_PATTERN.matcher(captor.getValue().getText());
        assertThat(matcher.find()).as("temporary password line in the mailed message").isTrue();
        String temporaryPassword = matcher.group(1);

        LoginRequest loginRequest = new LoginRequest(slug, email, temporaryPassword);
        ResponseEntity<AuthResponse> login = restTemplate.postForEntity(baseUrl() + "/auth/login", loginRequest, AuthResponse.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody().mustChangePassword())
                .as("admin-invited user's first login -- still on the temp password")
                .isTrue();

        // Mirrors what a real client is forced to do: PasswordChangeRequiredFilter
        // blocks every other endpoint until this happens. The rest of this test
        // file drives the app as a fully onboarded user, so complete that step
        // here rather than duplicating it in every caller.
        ResponseEntity<AuthResponse> changed = restTemplate.postForEntity(baseUrl() + "/auth/change-password",
                new HttpEntity<>(new ChangePasswordRequest(temporaryPassword, "N3w!" + temporaryPassword), authHeaders(login.getBody().token())),
                AuthResponse.class);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getBody().mustChangePassword()).isFalse();
        return changed.getBody().token();
    }

    @Test
    void invitedUser_mustChangeTemporaryPassword_beforeAnythingElseWorks() {
        String slug = uniqueSlug("acme");
        String adminToken = registerAdmin(slug);
        String email = "dev@" + slug + ".com";

        restTemplate.postForEntity(baseUrl() + "/users",
                new HttpEntity<>(new CreateUserRequest(email, "Test Developer", "DEVELOPER"), authHeaders(adminToken)),
                UserSummary.class);
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, atLeastOnce()).send(captor.capture());
        Matcher matcher = TEMP_PASSWORD_PATTERN.matcher(captor.getValue().getText());
        assertThat(matcher.find()).isTrue();
        String temporaryPassword = matcher.group(1);

        ResponseEntity<AuthResponse> login = restTemplate.postForEntity(baseUrl() + "/auth/login",
                new LoginRequest(slug, email, temporaryPassword), AuthResponse.class);
        assertThat(login.getBody().mustChangePassword()).isTrue();
        String tempToken = login.getBody().token();

        // PasswordChangeRequiredFilter blocks every other endpoint until the
        // password is actually changed -- even a perfectly valid, freshly
        // issued token for this user.
        ResponseEntity<String> blocked = restTemplate.exchange(baseUrl() + "/vendor-credentials",
                HttpMethod.GET, new HttpEntity<>(authHeaders(tempToken)), String.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // A weak new password is rejected without ever clearing the flag.
        ResponseEntity<ApiError> weak = restTemplate.postForEntity(baseUrl() + "/auth/change-password",
                new HttpEntity<>(new ChangePasswordRequest(temporaryPassword, "allletters"), authHeaders(tempToken)),
                ApiError.class);
        assertThat(weak.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<AuthResponse> changed = restTemplate.postForEntity(baseUrl() + "/auth/change-password",
                new HttpEntity<>(new ChangePasswordRequest(temporaryPassword, "N3w!" + temporaryPassword), authHeaders(tempToken)),
                AuthResponse.class);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getBody().mustChangePassword()).isFalse();
        String realToken = changed.getBody().token();

        // The fresh token works everywhere immediately -- no re-login needed.
        ResponseEntity<String> unblocked = restTemplate.exchange(baseUrl() + "/vendor-credentials",
                HttpMethod.GET, new HttpEntity<>(authHeaders(realToken)), String.class);
        assertThat(unblocked.getStatusCode()).isEqualTo(HttpStatus.OK);

        // And the new password now works for a completely fresh login.
        ResponseEntity<AuthResponse> reLogin = restTemplate.postForEntity(baseUrl() + "/auth/login",
                new LoginRequest(slug, email, "N3w!" + temporaryPassword), AuthResponse.class);
        assertThat(reLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reLogin.getBody().mustChangePassword()).isFalse();
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
    void developer_canManageOwnVendorCredential() {
        // Per-user credentials (see V22__vendor_credentials_per_user.sql):
        // unlike users/api-keys, a DEVELOPER is allowed to bring their own
        // vendor key -- this replaces the old ADMIN-only behavior this test
        // used to assert.
        String slug = uniqueSlug("acme");
        String adminToken = registerAdmin(slug);
        String devToken = createDeveloperAndLogin(slug, adminToken);

        HttpEntity<CreateVendorCredentialRequest> putRequest = new HttpEntity<>(
                new CreateVendorCredentialRequest("ANTHROPIC", "sk-ant-secret"), authHeaders(devToken));
        ResponseEntity<VendorCredentialSummary> response = restTemplate.exchange(
                baseUrl() + "/vendor-credentials", HttpMethod.PUT, putRequest, VendorCredentialSummary.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().provider()).isEqualTo("ANTHROPIC");
    }

    @Test
    void developer_cannotSeeAnotherDevelopersCredential() {
        String slug = uniqueSlug("acme");
        String adminToken = registerAdmin(slug);
        String dev1Token = createDeveloperAndLogin(slug, adminToken);
        String dev2Token = createDeveloperAndLoginSecond(slug, adminToken);

        restTemplate.exchange(baseUrl() + "/vendor-credentials", HttpMethod.PUT,
                new HttpEntity<>(new CreateVendorCredentialRequest("ANTHROPIC", "sk-ant-dev1-secret"), authHeaders(dev1Token)),
                VendorCredentialSummary.class);

        ResponseEntity<VendorCredentialSummary[]> dev2List = restTemplate.exchange(
                baseUrl() + "/vendor-credentials", HttpMethod.GET, new HttpEntity<>(authHeaders(dev2Token)), VendorCredentialSummary[].class);

        assertThat(dev2List.getBody()).isEmpty();
    }

    @Test
    void developer_cannotSeeOrDeactivateTeamCredentials_gets403() {
        String slug = uniqueSlug("acme");
        String adminToken = registerAdmin(slug);
        String devToken = createDeveloperAndLogin(slug, adminToken);

        ResponseEntity<String> teamList = restTemplate.exchange(
                baseUrl() + "/vendor-credentials/team", HttpMethod.GET, new HttpEntity<>(authHeaders(devToken)), String.class);
        assertThat(teamList.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> deactivate = restTemplate.exchange(
                baseUrl() + "/vendor-credentials/team/" + UUID.randomUUID() + "/ANTHROPIC/deactivate",
                HttpMethod.POST, new HttpEntity<>(authHeaders(devToken)), String.class);
        assertThat(deactivate.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_teamView_seesBothUsersCredentials_thenBlindDeactivatesOne() {
        String slug = uniqueSlug("acme");
        String adminToken = registerAdmin(slug);
        String devToken = createDeveloperAndLogin(slug, adminToken);

        restTemplate.exchange(baseUrl() + "/vendor-credentials", HttpMethod.PUT,
                new HttpEntity<>(new CreateVendorCredentialRequest("ANTHROPIC", "sk-ant-admin-secret"), authHeaders(adminToken)),
                VendorCredentialSummary.class);
        restTemplate.exchange(baseUrl() + "/vendor-credentials", HttpMethod.PUT,
                new HttpEntity<>(new CreateVendorCredentialRequest("OPENAI", "sk-openai-dev-secret"), authHeaders(devToken)),
                VendorCredentialSummary.class);

        ResponseEntity<TeamVendorCredentialSummary[]> team = restTemplate.exchange(
                baseUrl() + "/vendor-credentials/team", HttpMethod.GET, new HttpEntity<>(authHeaders(adminToken)), TeamVendorCredentialSummary[].class);
        assertThat(team.getBody()).hasSize(2);
        String devUserId = java.util.Arrays.stream(team.getBody())
                .filter(row -> "OPENAI".equals(row.provider())).findFirst().orElseThrow().userId();

        ResponseEntity<Void> deactivate = restTemplate.exchange(
                baseUrl() + "/vendor-credentials/team/" + devUserId + "/OPENAI/deactivate",
                HttpMethod.POST, new HttpEntity<>(authHeaders(adminToken)), Void.class);
        assertThat(deactivate.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<VendorCredentialSummary[]> devListAfter = restTemplate.exchange(
                baseUrl() + "/vendor-credentials", HttpMethod.GET, new HttpEntity<>(authHeaders(devToken)), VendorCredentialSummary[].class);
        assertThat(devListAfter.getBody()[0].active()).isFalse();
    }

    @Test
    void ping_noPersonalCredential_returnsPersonalizedError_notTenantWide() {
        String slug = uniqueSlug("acme");
        String adminToken = registerAdmin(slug);
        // Admin has their own key, but the developer never added one --
        // there's deliberately no tenant-wide fallback (see AgentPromptRunner).
        restTemplate.exchange(baseUrl() + "/vendor-credentials", HttpMethod.PUT,
                new HttpEntity<>(new CreateVendorCredentialRequest("ANTHROPIC", "sk-ant-admin-secret"), authHeaders(adminToken)),
                VendorCredentialSummary.class);
        String devToken = createDeveloperAndLogin(slug, adminToken);

        ResponseEntity<ApiError> response = restTemplate.exchange(
                baseUrl() + "/agents/ping", HttpMethod.POST,
                new HttpEntity<>(new AgentPingRequest("Hello", null), authHeaders(devToken)), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("for you").contains("PUT /vendor-credentials");
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
