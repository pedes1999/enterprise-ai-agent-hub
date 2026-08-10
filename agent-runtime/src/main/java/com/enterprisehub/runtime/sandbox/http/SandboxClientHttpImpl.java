package com.enterprisehub.runtime.sandbox.http;

import com.enterprisehub.runtime.sandbox.CommandResult;
import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.SandboxException;
import com.enterprisehub.runtime.sandbox.SandboxHandle;
import com.enterprisehub.runtime.sandbox.SandboxSpec;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * Calls the internal sidecar service (see this package's contract doc /
 * SandboxClient's own javadoc for why a sidecar exists at all). Pure
 * translation layer: no timeout/output-cap enforcement of its own, no
 * retry logic -- the sidecar is the one process that actually knows E2B's
 * real limits and is where that enforcement belongs.
 *
 * Sidecar HTTP contract (see agent-runtime/sidecar/ for the reference
 * implementation):
 *   POST   /sandboxes                     {tenantId, executionId, credentials, maxLifetimeSeconds, maxOutputBytes} -> {sandboxId}
 *   POST   /sandboxes/{id}/commands        {command, timeoutSeconds}                                               -> {exitCode, stdout, stderr, truncated, durationMs}
 *   PUT    /sandboxes/{id}/files           {path, contentBase64}                                                   -> 204
 *   GET    /sandboxes/{id}/files?path=...  ->                                                                         {contentBase64}
 *   DELETE /sandboxes/{id}                 ->                                                                         204 (idempotent)
 *
 * Uses java.net.http.HttpClient (JDK built-in) rather than adding an HTTP
 * client library dependency -- agent-runtime stays as dependency-light as
 * agent-core.
 */
public class SandboxClientHttpImpl implements SandboxClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final URI baseUri;

    public SandboxClientHttpImpl(URI sidecarBaseUri) {
        this.baseUri = sidecarBaseUri;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public SandboxHandle create(SandboxSpec spec) {
        var requestBody = new CreateSandboxRequest(
                spec.tenantId(), spec.executionId(), spec.credentials(),
                spec.maxLifetime().toSeconds(), spec.maxOutputBytes());

        CreateSandboxResponse response = send("POST", "/sandboxes", requestBody, CreateSandboxResponse.class);
        return new SandboxHandle(response.sandboxId());
    }

    @Override
    public CommandResult runCommand(SandboxHandle handle, String command, Duration timeout) {
        var requestBody = new RunCommandRequest(command, timeout.toSeconds());
        RunCommandResponse response = send("POST", "/sandboxes/" + handle.id() + "/commands",
                requestBody, RunCommandResponse.class);

        return new CommandResult(
                response.exitCode(), response.stdout(), response.stderr(),
                response.truncated(), Duration.ofMillis(response.durationMs()));
    }

    @Override
    public void writeFile(SandboxHandle handle, String path, byte[] content) {
        var requestBody = new WriteFileRequest(path, Base64.getEncoder().encodeToString(content));
        send("PUT", "/sandboxes/" + handle.id() + "/files", requestBody, Void.class);
    }

    @Override
    public byte[] readFile(SandboxHandle handle, String path) {
        String encodedPath = java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8);
        ReadFileResponse response = send("GET", "/sandboxes/" + handle.id() + "/files?path=" + encodedPath,
                null, ReadFileResponse.class);
        return Base64.getDecoder().decode(response.contentBase64());
    }

    @Override
    public void destroy(SandboxHandle handle) {
        try {
            send("DELETE", "/sandboxes/" + handle.id(), null, Void.class);
        } catch (SandboxException e) {
            // Idempotent by contract -- destroy() never throws. SandboxRunner
            // already logs failures; a second destroy on an already-gone
            // sandbox (or a sidecar hiccup) shouldn't propagate.
        }
    }

    private <T> T send(String method, String path, Object requestBody, Class<T> responseType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(baseUri.resolve(path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json");

            HttpRequest.BodyPublisher bodyPublisher = requestBody == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody));
            builder.method(method, bodyPublisher);

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 300) {
                throw new SandboxException("Sidecar returned " + response.statusCode() + " for " + method + " " + path
                        + ": " + response.body());
            }
            if (responseType == Void.class || response.body() == null || response.body().isBlank()) {
                return null;
            }
            return objectMapper.readValue(response.body(), responseType);
        } catch (IOException e) {
            throw new SandboxException("Failed to reach sandbox sidecar for " + method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxException("Interrupted while calling sandbox sidecar for " + method + " " + path, e);
        }
    }

    private record CreateSandboxRequest(String tenantId, String executionId, java.util.Map<String, String> credentials,
                                         long maxLifetimeSeconds, long maxOutputBytes) {
    }

    private record CreateSandboxResponse(String sandboxId) {
    }

    private record RunCommandRequest(String command, long timeoutSeconds) {
    }

    private record RunCommandResponse(int exitCode, String stdout, String stderr, boolean truncated, long durationMs) {
    }

    private record WriteFileRequest(String path, String contentBase64) {
    }

    private record ReadFileResponse(String contentBase64) {
    }
}
