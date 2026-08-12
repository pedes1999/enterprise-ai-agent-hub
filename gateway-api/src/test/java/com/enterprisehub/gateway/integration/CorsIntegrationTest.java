package com.enterprisehub.gateway.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real preflight (OPTIONS) requests against the actual Spring Security
 * filter chain -- the thing that would catch a config regression (e.g. a
 * wildcard origin silently creeping back in) that a unit test on
 * CorsProperties alone couldn't. See SecurityConfig.corsConfigurationSource()
 * for why a wildcard origin specifically must never happen here: this
 * platform's whole tenant-isolation story (RLS, scoped credential
 * resolution) would be undercut by any origin being able to read a
 * tenant's authenticated responses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CorsIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void preflight_fromConfiguredOrigin_isAllowed() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://localhost:4200"); // matches application.yml's default app.cors.allowed-origin
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type");

        ResponseEntity<Void> response = restTemplate.exchange(
                url("/agents/execute"), HttpMethod.OPTIONS, new HttpEntity<>(headers), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo("http://localhost:4200");
    }

    @Test
    void preflight_fromUnknownOrigin_isRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://not-the-real-frontend.example.com");
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");

        ResponseEntity<Void> response = restTemplate.exchange(
                url("/agents/execute"), HttpMethod.OPTIONS, new HttpEntity<>(headers), Void.class);

        // Spring's CORS handling denies by omitting the allow-origin header
        // (and, depending on version, a 403) rather than echoing the
        // untrusted origin back -- either way, no CORS header naming that
        // origin should ever appear.
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void configuredOrigin_isNeverAWildcard() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://localhost:4200");
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");

        ResponseEntity<Void> response = restTemplate.exchange(
                url("/agents/definitions"), HttpMethod.OPTIONS, new HttpEntity<>(headers), Void.class);

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNotEqualTo("*");
    }
}
