package com.enterprisehub.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * maxConcurrentPerTenant caps how many QUEUED+RUNNING agent_executions rows
 * one tenant can have at once -- see AgentExecutionService.enqueue(). A low
 * default (5) protects a metered E2B/Anthropic account from a bug, a
 * misbehaving agent loop, or a user clicking repeatedly, not from a
 * deliberately hostile tenant (RLS, not this, is the isolation boundary).
 * A single flat number today, not yet a per-tenant-tier setting -- reusing
 * app.execution's existing (previously unused) namespace rather than
 * inventing a new one keeps that upgrade path open without building
 * tenant-tier infrastructure now.
 */
@ConfigurationProperties(prefix = "app.execution")
public record ExecutionLimitProperties(int maxConcurrentPerTenant) {
}
