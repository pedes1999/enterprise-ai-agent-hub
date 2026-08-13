package com.enterprisehub.dto;

public record ChangePasswordRequest(
        String currentPassword,
        String newPassword
) {
}
