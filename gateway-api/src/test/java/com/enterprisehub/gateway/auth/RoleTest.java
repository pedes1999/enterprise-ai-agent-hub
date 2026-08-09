package com.enterprisehub.gateway.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoleTest {

    @Test
    void parse_validUpperCase_returnsRole() {
        assertThat(Role.parse("ADMIN")).contains(Role.ADMIN);
        assertThat(Role.parse("DEVELOPER")).contains(Role.DEVELOPER);
        assertThat(Role.parse("READONLY")).contains(Role.READONLY);
    }

    @Test
    void parse_caseInsensitive() {
        assertThat(Role.parse("admin")).contains(Role.ADMIN);
    }

    @Test
    void parse_unknownValue_returnsEmpty() {
        assertThat(Role.parse("SUPERUSER")).isEmpty();
    }

    @Test
    void parse_null_returnsEmpty() {
        assertThat(Role.parse(null)).isEmpty();
    }
}
