package com.enterprisehub.gateway.auth;

import java.security.SecureRandom;

/**
 * Generates the one-time password UserService.create() emails to a newly
 * created team member -- never supplied by the caller (see
 * CreateUserRequest's javadoc). Excludes visually ambiguous characters
 * (0/O, 1/l/I) since this has to be read off an email and typed back in.
 */
final class TempPasswordGenerator {

    private static final String CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TempPasswordGenerator() {
    }

    static String generate() {
        StringBuilder password = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            password.append(CHARSET.charAt(RANDOM.nextInt(CHARSET.length())));
        }
        return password.toString();
    }
}
