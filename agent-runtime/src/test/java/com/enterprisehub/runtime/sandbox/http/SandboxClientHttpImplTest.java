package com.enterprisehub.runtime.sandbox.http;

import com.enterprisehub.runtime.sandbox.CommandResult;
import com.enterprisehub.runtime.sandbox.SandboxException;
import com.enterprisehub.runtime.sandbox.SandboxHandle;
import com.enterprisehub.runtime.sandbox.SandboxSpec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against a real, local, embedded HTTP server (JDK built-in
 * com.sun.net.httpserver.HttpServer, no external dependency or real E2B
 * sidecar needed) to verify SandboxClientHttpImpl actually produces and
 * parses the documented wire contract correctly -- not just that it compiles
 * against mocked responses.
 */
class SandboxClientHttpImplTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private URI startServerReturning(String path, int status, String responseBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(path, exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return URI.create("http://localhost:" + server.getAddress().getPort() + "/");
    }

    @Test
    void create_sendsSpecAsJson_parsesSandboxId() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/sandboxes", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"sandboxId\":\"abc-123\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        URI baseUri = URI.create("http://localhost:" + server.getAddress().getPort() + "/");

        var client = new SandboxClientHttpImpl(baseUri);
        SandboxSpec spec = new SandboxSpec("tenant-1", "exec-1", Map.of("GIT_TOKEN", "secret"), Duration.ofMinutes(2), 65536);

        SandboxHandle handle = client.create(spec);

        assertThat(handle.id()).isEqualTo("abc-123");
        assertThat(capturedBody.get()).contains("\"tenantId\":\"tenant-1\"")
                .contains("\"executionId\":\"exec-1\"")
                .contains("\"GIT_TOKEN\":\"secret\"")
                .contains("\"maxLifetimeSeconds\":120")
                .contains("\"maxOutputBytes\":65536");
    }

    @Test
    void runCommand_parsesFullResult() throws Exception {
        URI baseUri = startServerReturning("/sandboxes/abc/commands", 200,
                "{\"exitCode\":0,\"stdout\":\"hello\",\"stderr\":\"\",\"truncated\":false,\"durationMs\":150}");

        CommandResult result = new SandboxClientHttpImpl(baseUri)
                .runCommand(new SandboxHandle("abc"), "echo hello", Duration.ofSeconds(10));

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout()).isEqualTo("hello");
        assertThat(result.outputTruncated()).isFalse();
        assertThat(result.duration()).isEqualTo(Duration.ofMillis(150));
    }

    @Test
    void writeFile_base64EncodesContent() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/sandboxes/abc/files", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        URI baseUri = URI.create("http://localhost:" + server.getAddress().getPort() + "/");

        new SandboxClientHttpImpl(baseUri).writeFile(new SandboxHandle("abc"), "/tmp/x.txt", "hello world".getBytes(StandardCharsets.UTF_8));

        String expectedBase64 = Base64.getEncoder().encodeToString("hello world".getBytes(StandardCharsets.UTF_8));
        assertThat(capturedBody.get()).contains("\"path\":\"/tmp/x.txt\"").contains(expectedBase64);
    }

    @Test
    void readFile_base64DecodesResponse() throws Exception {
        String encoded = Base64.getEncoder().encodeToString("file contents".getBytes(StandardCharsets.UTF_8));
        URI baseUri = startServerReturning("/sandboxes/abc/files", 200, "{\"contentBase64\":\"" + encoded + "\"}");

        byte[] content = new SandboxClientHttpImpl(baseUri).readFile(new SandboxHandle("abc"), "/tmp/x.txt");

        assertThat(new String(content, StandardCharsets.UTF_8)).isEqualTo("file contents");
    }

    @Test
    void destroy_neverThrows_evenOnServerError() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/sandboxes/abc", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        URI baseUri = URI.create("http://localhost:" + server.getAddress().getPort() + "/");

        new SandboxClientHttpImpl(baseUri).destroy(new SandboxHandle("abc")); // must not throw
    }

    @Test
    void create_serverError_throwsSandboxException() throws Exception {
        URI baseUri = startServerReturning("/sandboxes", 500, "{\"error\":\"internal\"}");

        assertThatThrownBy(() -> new SandboxClientHttpImpl(baseUri)
                .create(new SandboxSpec("t1", "e1", Map.of(), Duration.ofMinutes(1), 1024)))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("500");
    }

    @Test
    void sidecarUnreachable_throwsSandboxException_notRawIOException() {
        // Nothing listening on this port.
        URI unreachable = URI.create("http://localhost:1/");

        assertThatThrownBy(() -> new SandboxClientHttpImpl(unreachable)
                .create(new SandboxSpec("t1", "e1", Map.of(), Duration.ofMinutes(1), 1024)))
                .isInstanceOf(SandboxException.class);
    }
}
