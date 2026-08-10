package com.enterprisehub.gateway.config;

import com.enterprisehub.runtime.sandbox.SandboxClient;
import com.enterprisehub.runtime.sandbox.http.SandboxClientHttpImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * Wires SandboxClientHttpImpl as the SandboxClient implementation, pointed
 * at the sidecar via app.sandbox.sidecar-url. Swapping to a different
 * SandboxClient implementation later (see that interface's javadoc for the
 * native-Java alternative) means changing only this bean method.
 */
@Configuration
public class SandboxConfig {

    @Bean
    public SandboxClient sandboxClient(SandboxProperties properties) {
        return new SandboxClientHttpImpl(URI.create(properties.sidecarUrl()));
    }
}
