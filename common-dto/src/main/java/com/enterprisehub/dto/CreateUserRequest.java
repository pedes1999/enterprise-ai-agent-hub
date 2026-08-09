package com.enterprisehub.dto;

public record CreateUserRequest(
        String email,
        String password,
        String role
) {
}
