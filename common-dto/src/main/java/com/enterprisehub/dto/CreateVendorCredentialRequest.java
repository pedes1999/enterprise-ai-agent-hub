package com.enterprisehub.dto;

public record CreateVendorCredentialRequest(
        String provider,
        String token
) {
}
