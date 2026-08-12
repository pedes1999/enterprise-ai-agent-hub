package com.enterprisehub.dto;

/** No password field -- a temporary password is always generated server-side and emailed, never supplied by the caller (see UserService). */
public record CreateUserRequest(
        String email,
        String name,
        String role
) {
}
