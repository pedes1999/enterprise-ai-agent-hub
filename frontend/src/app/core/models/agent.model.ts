export interface AgentDefinitionSummary {
  slug: string;
  name: string;
  description: string;
  toolNames: string[];
}

export interface AgentDefinitionDetail {
  slug: string;
  name: string;
  description: string;
  systemPrompt: string;
  toolNames: string[];
  inputSourceType: string | null;
  requiredInputs: string[];
}

export interface TriggerAgentExecutionRequest {
  prompt: string | null;
  agentSlug: string | null;
  repositoryUrl: string | null;
  inputParameters: Record<string, string> | null;
}

export interface AgentExecutionAccepted {
  executionId: string;
  status: ExecutionStatus;
}

export type ExecutionStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';

export interface AgentExecutionStatusResponse {
  id: string;
  status: ExecutionStatus;
  llmProvider: string;
  agentSlug: string;
  prompt: string | null;
  repositoryUrl: string | null;
  inputParameters: Record<string, string> | null;
  reply: string | null;
  toolWasUsed: boolean | null;
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
}

export interface ToolExecutionRecord {
  toolName: string;
  durationMs: number;
  outcome: 'SUCCESS' | 'FAILURE';
  errorMessage: string | null;
  createdAt: string;
}

export interface ExecutionUsage {
  active: number;
  limit: number;
}

/** Mirrors Spring's PagedModel<T> JSON shape -- {content, page: {size, number, totalElements, totalPages}}. */
export interface PagedModel<T> {
  content: T[];
  page: {
    size: number;
    number: number;
    totalElements: number;
    totalPages: number;
  };
}
