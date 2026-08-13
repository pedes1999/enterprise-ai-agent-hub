package com.enterprisehub.gateway.auth;

import java.util.regex.Pattern;

/**
 * The one password-strength rule enforced everywhere a user picks their
 * own password (self-registration's admin, and the forced first-login
 * change for an admin-invited user) -- see AuthService.register() and
 * AuthService.changePassword(). Server-generated temporary passwords
 * (TempPasswordGenerator) don't need to satisfy this: nobody types them in,
 * they exist only to be immediately replaced.
 */
final class PasswordPolicy {

    static final String REQUIREMENTS_MESSAGE =
            "Password must be at least 8 characters and include at least one number and one special character.";

    private static final int MIN_LENGTH = 8;
    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final Pattern SPECIAL_CHARACTER = Pattern.compile("[^A-Za-z0-9]");

    private PasswordPolicy() {
    }

    static boolean isValid(String password) {
        return password != null
                && password.length() >= MIN_LENGTH
                && DIGIT.matcher(password).find()
                && SPECIAL_CHARACTER.matcher(password).find();
    }
}
