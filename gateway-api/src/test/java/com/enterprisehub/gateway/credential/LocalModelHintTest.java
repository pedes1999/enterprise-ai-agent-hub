package com.enterprisehub.gateway.credential;

import com.enterprisehub.core.llm.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The enrichment must be strictly additive: it either appends something
 * genuinely useful, or it hands the original message straight back. A hint
 * that swallowed or replaced a provider error would make debugging worse
 * than saying nothing at all.
 */
class LocalModelHintTest {

    private VendorModelCatalogService catalogService;
    private LocalModelHint hint;

    @BeforeEach
    void setUp() {
        catalogService = mock(VendorModelCatalogService.class);
        hint = new LocalModelHint(catalogService);
    }

    @Test
    void listsWhatIsActuallyServedWhenTheNamedModelIsMissing() {
        when(catalogService.listLocalModelIds()).thenReturn(List.of("qwen2.5-coder:7b", "nomic-embed-text:latest"));

        String enriched = hint.enrich(LlmProvider.LOCAL, "model 'llama3.1' not found");

        // The original text survives -- this appends, never rewrites.
        assertThat(enriched).startsWith("model 'llama3.1' not found");
        assertThat(enriched).contains("qwen2.5-coder:7b", "nomic-embed-text:latest");
        // And says what to do with that list, since knowing the names is only
        // half the answer.
        assertThat(enriched).contains("preferredModelName");
    }

    @Test
    void leavesHostedProviderFailuresAlone() {
        // A missing Anthropic model is not something a local /models route can
        // say anything useful about, so it must not even be consulted.
        String message = "model 'claude-nonexistent' not found";

        assertThat(hint.enrich(LlmProvider.ANTHROPIC, message)).isEqualTo(message);
        verifyNoInteractions(catalogService);
    }

    @Test
    void leavesUnrelatedLocalFailuresAlone() {
        // A refused connection or a context overflow gains nothing from a
        // model list, and appending one would be noise on an already-confusing
        // failure.
        String message = "Connection refused: localhost/127.0.0.1:11434";

        assertThat(hint.enrich(LlmProvider.LOCAL, message)).isEqualTo(message);
        verifyNoInteractions(catalogService);
    }

    @Test
    void staysSilentWhenTheEndpointCannotSayWhatItServes() {
        // The server went away between the failed call and this one, or has
        // nothing pulled. Either way there is no list worth appending.
        when(catalogService.listLocalModelIds()).thenReturn(List.of());
        String message = "model 'llama3.1' not found";

        assertThat(hint.enrich(LlmProvider.LOCAL, message)).isEqualTo(message);
    }

    @Test
    void toleratesTheOtherPhrasingsSelfHostedServersUse() {
        // Ollama, LM Studio and vLLM do not agree on the wording, and the
        // error body is all we get -- there is no status code to match on.
        when(catalogService.listLocalModelIds()).thenReturn(List.of("qwen2.5-coder:7b"));

        assertThat(hint.enrich(LlmProvider.LOCAL, "The model does not exist")).contains("qwen2.5-coder:7b");
        assertThat(hint.enrich(LlmProvider.LOCAL, "no such model: foo")).contains("qwen2.5-coder:7b");
        assertThat(hint.enrich(LlmProvider.LOCAL, "Model 'X' Not Found")).contains("qwen2.5-coder:7b");
    }

    @Test
    void handlesANullMessageWithoutThrowing() {
        // A RuntimeException with no message is rare but legal, and enriching
        // must never be the thing that fails.
        assertThat(hint.enrich(LlmProvider.LOCAL, null)).isNull();
    }
}
