package com.enterprisehub.gateway.webhook;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies GitHub's X-Hub-Signature-256 header: the hex HMAC-SHA256 of the
 * exact request body, keyed by the endpoint's shared secret, prefixed
 * "sha256=".
 *
 * Two properties this class exists to guarantee, both easy to lose:
 *
 *  1. It hashes the RAW bytes it is handed. WebhookController takes a
 *     byte[] body specifically so nothing parses and re-serializes the
 *     payload first -- Jackson would happily produce semantically identical
 *     JSON with different bytes, and the HMAC would then never match.
 *
 *  2. The comparison is constant-time (MessageDigest.isEqual), not
 *     String.equals or Arrays.equals-on-hex. A byte-at-a-time comparison
 *     leaks, through timing, how many leading bytes of a guess were right,
 *     which turns forging a signature into a per-byte search instead of a
 *     2^256 one. This is the standard failure mode of hand-rolled webhook
 *     verification and costs nothing to avoid.
 *
 * Nothing here logs the secret, the expected digest, or the supplied one --
 * an attacker who can read logs must not be handed the answer, and the
 * expected digest is as good as the secret for forging a single request.
 */
@Component
public class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    /**
     * @param rawBody         the request body exactly as received
     * @param secret          the endpoint's decrypted shared secret
     * @param suppliedHeader  the X-Hub-Signature-256 value, may be null
     * @return true only if the header is well-formed AND matches
     */
    public boolean isValid(byte[] rawBody, String secret, String suppliedHeader) {
        if (rawBody == null || secret == null || suppliedHeader == null) {
            return false;
        }
        if (!suppliedHeader.startsWith(PREFIX)) {
            return false;
        }

        byte[] supplied;
        try {
            supplied = HexFormat.of().parseHex(suppliedHeader.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            // Not valid hex -- malformed header, not a mismatch. Same answer
            // either way, deliberately: the caller learns only "rejected".
            return false;
        }

        return MessageDigest.isEqual(computeHmac(rawBody, secret), supplied);
    }

    /**
     * Exposed for tests and for the docs' worked example -- callers on the
     * request path should use {@link #isValid} so the constant-time
     * comparison isn't accidentally re-implemented at the call site.
     */
    public String computeSignatureHeader(byte[] rawBody, String secret) {
        return PREFIX + HexFormat.of().formatHex(computeHmac(rawBody, secret));
    }

    private byte[] computeHmac(byte[] rawBody, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(rawBody);
        } catch (Exception e) {
            // HmacSHA256 is required of every JRE, and the key is non-empty
            // by construction (WebhookEndpointService generates it), so this
            // is genuinely unreachable rather than a case worth signalling
            // as a client error.
            throw new IllegalStateException("HMAC-SHA256 is unavailable in this JVM", e);
        }
    }
}
