package com.enterprisehub.dto;

public record CreateToolCredentialRequest(
        String credentialKind,
        String value
) {
}
