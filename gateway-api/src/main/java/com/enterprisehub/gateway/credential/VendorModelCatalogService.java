package com.enterprisehub.gateway.credential;

import com.enterprisehub.core.llm.LlmProvider;
import com.enterprisehub.dto.ModelOption;
import com.enterprisehub.gateway.config.LlmProperties;
import com.enterprisehub.gateway.entity.VendorCredential;
import com.enterprisehub.gateway.repository.VendorCredentialRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Backs GET /vendor-credentials/{provider}/models -- lets the Credentials
 * page show a real, live list of models the tenant can actually pick
 * (see TenantSettingsService.preferredModelName) instead of a free-text
 * guess. Every vendor exposes its own model-listing API; this class is
 * the one place per-vendor response shapes get normalized into a common
 * ModelOption(id, label).
 *
 * Deliberately covers all four VendorProvider values, not just
 * ANTHROPIC/LOCAL (the only two LlmEngineFactory can actually run agents
 * against today) -- browsing OPENAI/GEMINI's model catalog is harmless and
 * consistent even before their chat-completion clients are implemented; a
 * tenant can save a preference for one now and it'll simply do nothing
 * until that provider's execution support lands.
 */
@Service
public class VendorModelCatalogService {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** Mirrors LlmEngineFactory.LOCAL_DEFAULT_BASE_URL -- kept in sync by convention, same as LlmProvider/VendorProvider themselves. */
    private static final String LOCAL_DEFAULT_BASE_URL = "http://localhost:11434/v1";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final VendorCredentialRepository vendorCredentialRepository;
    private final VendorCredentialService vendorCredentialService;
    private final LlmProperties llmProperties;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VendorModelCatalogService(VendorCredentialRepository vendorCredentialRepository,
                                      VendorCredentialService vendorCredentialService,
                                      LlmProperties llmProperties) {
        this.vendorCredentialRepository = vendorCredentialRepository;
        this.vendorCredentialService = vendorCredentialService;
        this.llmProperties = llmProperties;
    }

    public List<ModelOption> list(UUID tenantId, String providerValue) {
        VendorProvider provider = VendorProvider.parse(providerValue)
                .orElseThrow(() -> new VendorCredentialException(HttpStatus.BAD_REQUEST,
                        "provider must be one of ANTHROPIC, OPENAI, GEMINI, LOCAL"));

        VendorCredential credential = vendorCredentialRepository.findByTenantIdAndProvider(tenantId, provider.name())
                .filter(VendorCredential::isActive)
                .orElseThrow(() -> new VendorCredentialException(HttpStatus.NOT_FOUND,
                        "No active credential stored for provider " + provider.name()));
        String apiKey = vendorCredentialService.decryptToken(credential);

        return switch (provider) {
            case ANTHROPIC -> listAnthropic(apiKey);
            case OPENAI -> listOpenAiCompatible("https://api.openai.com/v1", apiKey, "OpenAI");
            case GEMINI -> listGemini(apiKey);
            case LOCAL -> listOpenAiCompatible(resolveLocalBaseUrl(), null, "Local");
        };
    }

    private String resolveLocalBaseUrl() {
        String configured = llmProperties.baseUrl(LlmProvider.LOCAL);
        return (configured != null && !configured.isBlank()) ? configured : LOCAL_DEFAULT_BASE_URL;
    }

    private List<ModelOption> listAnthropic(String apiKey) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/models?limit=100"))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .timeout(TIMEOUT)
                .GET()
                .build();
        JsonNode root = sendAndParse(request, "Anthropic");
        List<ModelOption> options = new ArrayList<>();
        for (JsonNode model : root.path("data")) {
            String id = model.path("id").asText();
            options.add(new ModelOption(id, model.path("display_name").asText(id)));
        }
        return options;
    }

    /** Also used for LOCAL -- Ollama/LM Studio/vLLM all speak this same OpenAI-compatible /models shape, see LlmEngineFactory. */
    private List<ModelOption> listOpenAiCompatible(String baseUrl, String apiKey, String vendorLabel) {
        String url = (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl) + "/models";
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        JsonNode root = sendAndParse(builder.build(), vendorLabel);
        List<ModelOption> options = new ArrayList<>();
        for (JsonNode model : root.path("data")) {
            String id = model.path("id").asText();
            options.add(new ModelOption(id, id));
        }
        return options;
    }

    private List<ModelOption> listGemini(String apiKey) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200&key="
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .GET()
                .build();
        JsonNode root = sendAndParse(request, "Gemini");
        List<ModelOption> options = new ArrayList<>();
        for (JsonNode model : root.path("models")) {
            String rawName = model.path("name").asText();
            String id = rawName.startsWith("models/") ? rawName.substring("models/".length()) : rawName;
            options.add(new ModelOption(id, model.path("displayName").asText(id)));
        }
        return options;
    }

    private JsonNode sendAndParse(HttpRequest request, String vendorLabel) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new VendorCredentialException(HttpStatus.BAD_GATEWAY, vendorLabel + " model list request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VendorCredentialException(HttpStatus.BAD_GATEWAY, vendorLabel + " model list request was interrupted");
        }
        if (response.statusCode() / 100 != 2) {
            throw new VendorCredentialException(HttpStatus.BAD_GATEWAY,
                    vendorLabel + " model list request failed with HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new VendorCredentialException(HttpStatus.BAD_GATEWAY, vendorLabel + " returned a response that could not be parsed: " + e.getMessage());
        }
    }

    private String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }
}
