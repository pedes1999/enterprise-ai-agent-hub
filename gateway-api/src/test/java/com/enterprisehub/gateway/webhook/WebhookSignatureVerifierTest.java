package com.enterprisehub.gateway.webhook;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureVerifierTest {

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier();

    private static final String SECRET = "whsec_test-secret";
    private static final byte[] BODY = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void acceptsASignatureComputedWithTheSameSecret() {
        String header = verifier.computeSignatureHeader(BODY, SECRET);

        assertThat(verifier.isValid(BODY, SECRET, header)).isTrue();
    }

    /**
     * GitHub's own published example secret/payload/signature triple, pinned
     * verbatim. This is the test that matters most in this class: every
     * other one would still pass if the algorithm were subtly wrong (a
     * different digest, the wrong encoding), because they only check this
     * implementation against itself. This one checks it against GitHub.
     */
    @Test
    void matchesGitHubsDocumentedExampleSignature() {
        byte[] body = "Hello, World!".getBytes(StandardCharsets.UTF_8);

        String header = verifier.computeSignatureHeader(body, "It's a Secret to Everybody");

        assertThat(header).isEqualTo("sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17");
        assertThat(verifier.isValid(body, "It's a Secret to Everybody", header)).isTrue();
    }

    @Test
    void rejectsASignatureComputedWithADifferentSecret() {
        String header = verifier.computeSignatureHeader(BODY, "whsec_some-other-secret");

        assertThat(verifier.isValid(BODY, SECRET, header)).isFalse();
    }

    /** The whole point of signing the body: a tampered payload must not verify. */
    @Test
    void rejectsABodyThatChangedByASingleByte() {
        String header = verifier.computeSignatureHeader(BODY, SECRET);
        byte[] tampered = "{\"action\":\"closed\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.isValid(tampered, SECRET, header)).isFalse();
    }

    @Test
    void rejectsAMissingHeader() {
        assertThat(verifier.isValid(BODY, SECRET, null)).isFalse();
    }

    @Test
    void rejectsAHeaderWithoutTheSha256Prefix() {
        String header = verifier.computeSignatureHeader(BODY, SECRET);
        String withoutPrefix = header.substring("sha256=".length());

        assertThat(verifier.isValid(BODY, SECRET, withoutPrefix)).isFalse();
    }

    /** Malformed hex must be answered like any other mismatch, never with an exception escaping to a 500. */
    @Test
    void rejectsAHeaderThatIsNotValidHex() {
        assertThat(verifier.isValid(BODY, SECRET, "sha256=not-hex-at-all")).isFalse();
    }
}
