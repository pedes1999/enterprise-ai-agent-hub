package com.enterprisehub.runtime.sandbox;

/**
 * Opaque -- callers never parse or construct the id themselves, and never
 * assume anything about its format. Whatever SandboxClient implementation
 * issued it is the only thing that interprets it.
 */
public record SandboxHandle(String id) {
}
