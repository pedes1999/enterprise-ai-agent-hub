package com.enterprisehub.dto;

/**
 * GET /agents/executions/usage -- lets the frontend show "3 / 5 executions
 * running" before a caller submits a trigger request. active mirrors
 * exactly what AgentExecutionService.enqueue()'s own concurrency check
 * counts (QUEUED + RUNNING); limit is ExecutionLimitProperties.maxConcurrentPerTenant().
 */
public record ExecutionUsage(long active, int limit) {
}
