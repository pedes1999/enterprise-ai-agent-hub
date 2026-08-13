package com.enterprisehub.gateway.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    @Test
    void isValid_strongPassword_true() {
        assertThat(PasswordPolicy.isValid("p@ssword123")).isTrue();
    }

    @Test
    void isValid_null_false() {
        assertThat(PasswordPolicy.isValid(null)).isFalse();
    }

    @Test
    void isValid_shorterThanEightCharacters_false() {
        assertThat(PasswordPolicy.isValid("p@ss1")).isFalse();
    }

    @Test
    void isValid_exactlyEightCharacters_true() {
        assertThat(PasswordPolicy.isValid("p@ssw0rd")).isTrue();
    }

    @Test
    void isValid_noDigit_false() {
        assertThat(PasswordPolicy.isValid("p@ssword!!")).isFalse();
    }

    @Test
    void isValid_noSpecialCharacter_false() {
        assertThat(PasswordPolicy.isValid("password123")).isFalse();
    }

    @Test
    void isValid_onlyLetters_false() {
        assertThat(PasswordPolicy.isValid("onlyletters")).isFalse();
    }

    @Test
    void isValid_spaceCountsAsSpecialCharacter() {
        // Deliberately permissive -- anything outside [A-Za-z0-9] satisfies
        // the "special character" requirement, a space included.
        assertThat(PasswordPolicy.isValid("pass word1")).isTrue();
    }
}
