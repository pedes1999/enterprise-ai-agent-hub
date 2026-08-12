package com.enterprisehub.dto;

/**
 * POST /vendor-credentials/test and POST /tool-credentials/test both
 * return this shape. valid=false covers both "the provider rejected the
 * credential" and "test-connection isn't supported for this kind yet" --
 * message always explains which.
 */
public record CredentialTestResult(boolean valid, String message) {
}
