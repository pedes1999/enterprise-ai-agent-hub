package com.enterprisehub.dto;

/** topK is nullable -- null means "use the server default" (see RetrievalQueryController). */
public record RetrievalQueryRequest(String query, Integer topK) {
}
