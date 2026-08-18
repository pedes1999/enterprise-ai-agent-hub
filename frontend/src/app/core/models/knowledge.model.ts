export interface KnowledgeSourceSummary {
  id: string;
  name: string;
  sourceType: string;
  createdAt: string;
}

export interface CreateKnowledgeSourceRequest {
  name: string;
  sourceType: string;
}

export interface IngestDocumentResponse {
  knowledgeSourceId: string;
  documentName: string;
  chunkCount: number;
}

export interface RetrievalQueryRequest {
  query: string;
  /** Null means "use the server default (5)". */
  topK: number | null;
}

/** One retrieval hit -- content plus enough to cite it (documentName) and judge it (score, higher is more relevant). */
export interface RetrievedChunkResult {
  chunkId: string;
  documentName: string;
  content: string;
  score: number;
}

/** What knowledge source (if any) is currently attached to a given agent for this tenant -- see KnowledgeService.getBindingForAgent(). */
export interface AgentKnowledgeSourceBindingSummary {
  knowledgeSourceId: string;
  knowledgeSourceName: string;
}
