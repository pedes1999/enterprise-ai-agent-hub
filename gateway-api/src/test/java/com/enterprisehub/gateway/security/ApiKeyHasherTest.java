package com.enterprisehub.gateway.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyHasherTest {

    private final ApiKeyHasher hasher = new ApiKeyHasher();

    @Test
    void generateRawKey_hasExpectedPrefix() {
        assertThat(hasher.generateRawKey()).startsWith("ahk_");
    }

    @Test
    void generateRawKey_isRandomEachCall() {
        assertThat(hasher.generateRawKey()).isNotEqualTo(hasher.generateRawKey());
    }

    @Test
    void hash_isDeterministicForSameInput() {
        String raw = hasher.generateRawKey();
        assertThat(hasher.hash(raw)).isEqualTo(hasher.hash(raw));
    }

    @Test
    void hash_differsForDifferentInputs() {
        assertThat(hasher.hash(hasher.generateRawKey())).isNotEqualTo(hasher.hash(hasher.generateRawKey()));
    }

    @Test
    void hash_isHexEncodedSha256Length() {
        String hash = hasher.hash("some-raw-key");
        assertThat(hash).hasSize(64); // 32 bytes -> 64 hex chars
        assertThat(hash).matches("[0-9a-f]+");
    }

    @Test
    void hash_neverContainsTheRawKeyItself() {
        String raw = "ahk_super-secret-value";
        assertThat(hasher.hash(raw)).doesNotContain(raw);
    }
}
