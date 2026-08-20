package com.devloom.integrations;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * Timeouts for calls out to a source's own server.
 *
 * <p>RestClient's default request factory has none, which is not "wait a long time" but "wait
 * forever". A host that silently accepts no connection — a corporate server routable only from
 * the host, a VPN that dropped, a firewall that blackholes container traffic to private ranges —
 * therefore never produces an error. The call simply never returns, and the screen waiting on it
 * sits on its progress message indefinitely, which reads as a hung app rather than an
 * unreachable server.
 *
 * <p>Fail fast instead and let the caller say what happened. Same reasoning as
 * {@code HostAgentClient} and {@code OllamaLlm}, which already do this.
 */
final class SourceHttp {
    private SourceHttp() {}

    static SimpleClientHttpRequestFactory factory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5_000);  // a reachable source answers in milliseconds; 5s is already generous
        f.setReadTimeout(20_000);    // small JSON reads, not model generation
        return f;
    }
}
