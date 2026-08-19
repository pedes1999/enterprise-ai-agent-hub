package com.enterprisehub.gateway.credential;

import com.enterprisehub.core.llm.LlmProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns "model 'llama3.1' not found" into something the reader can act on.
 *
 * A self-hosted server only serves the tags its operator actually pulled, and
 * tags match exactly -- so the single most common LOCAL failure is naming a
 * model this machine does not have. The app already knows the answer
 * (VendorModelCatalogService lists them), it just never said so, leaving the
 * operator to go and run `ollama list` themselves to learn something the
 * failure could have told them.
 *
 * Deliberately a hint, NOT a fallback. Silently re-pointing a run at whatever
 * model happened to be installed would be worse than failing: every execution
 * here is audited and costed, so the model that ran has to be the model
 * somebody chose. It is also not safely guessable -- the OpenAI-compatible
 * /models route these servers share reports no capabilities, so an
 * embedding-only model (nomic-embed-text) is indistinguishable from a chat
 * model in that listing, and picking the wrong one fails later and more
 * confusingly than the original error did. Distinguishing them needs Ollama's
 * native /api/tags, which LM Studio and vLLM do not serve.
 */
@Component
public class LocalModelHint {

    /**
     * Matched against the vendor's own error text rather than a status code,
     * because an OpenAI-compatible error body is all these servers give us and
     * they do not agree on its shape (Ollama sets type "not_found_error";
     * others just put prose in "message"). Broad enough to catch the phrasings
     * in use, narrow enough that an unrelated failure -- a context-length
     * overflow, a refused connection -- does not collect an irrelevant model
     * list.
     */
    private static final List<String> MODEL_MISSING_MARKERS = List.of("not found", "does not exist", "no such model");

    private final VendorModelCatalogService catalogService;

    public LocalModelHint(VendorModelCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * Returns {@code message} unchanged unless this was a LOCAL run that
     * failed on a missing model AND the endpoint is reachable enough to say
     * what it does serve. Every other case -- a hosted provider, a different
     * failure, an endpoint that is simply down -- passes straight through, so
     * enriching can never make a message worse or turn one failure into two.
     */
    public String enrich(LlmProvider provider, String message) {
        if (provider != LlmProvider.LOCAL || message == null || !looksLikeMissingModel(message)) {
            return message;
        }
        List<String> available = catalogService.listLocalModelIds();
        if (available.isEmpty()) {
            // Nothing pulled at all, or the server went away between the
            // failed call and this one. Either way there is no list worth
            // appending, and inventing guidance would be noise.
            return message;
        }
        return message + " This local endpoint currently serves: " + String.join(", ", available)
                + ". Set one as the tenant's preferredModelName (PUT /tenant-settings) or as LLM_LOCAL_MODEL_NAME.";
    }

    private boolean looksLikeMissingModel(String message) {
        String lower = message.toLowerCase();
        return MODEL_MISSING_MARKERS.stream().anyMatch(lower::contains);
    }
}
