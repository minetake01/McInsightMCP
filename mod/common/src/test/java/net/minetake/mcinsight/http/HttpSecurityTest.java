package net.minetake.mcinsight.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HttpSecurityTest {

    @Test
    void validatesLoopbackHostsAndBrowserFetchHeaders() {
        assertTrue(InsightHttpServer.isLocalBrowserSafeHeaders("127.0.0.1:45555", null, null));
        assertTrue(InsightHttpServer.isLocalBrowserSafeHeaders("localhost:45555", "", "same-origin"));
        assertTrue(InsightHttpServer.isLocalBrowserSafeHeaders("[::1]:45555", null, "none"));

        assertFalse(InsightHttpServer.isLocalBrowserSafeHeaders("evil.example:45555", null, null));
        assertFalse(InsightHttpServer.isLocalBrowserSafeHeaders("127.0.0.1.evil.example", null, null));
        assertFalse(InsightHttpServer.isLocalBrowserSafeHeaders("localhost:notaport", null, null));
        assertFalse(InsightHttpServer.isLocalBrowserSafeHeaders("localhost:45555", "http://evil.example", null));
        assertFalse(InsightHttpServer.isLocalBrowserSafeHeaders("localhost:45555", null, "cross-site"));
        assertFalse(InsightHttpServer.isLocalBrowserSafeHeaders("localhost:45555", null, "same-site"));
    }

    @Test
    void checksBearerTokensInConstantTime() {
        assertTrue(InsightHttpServer.constantTimeBearerMatch("secret-token", "Bearer secret-token"));
        assertFalse(InsightHttpServer.constantTimeBearerMatch("secret-token", null));
        assertFalse(InsightHttpServer.constantTimeBearerMatch("secret-token", "Bearer secret-toke"));
        assertFalse(InsightHttpServer.constantTimeBearerMatch("secret-token", "secret-token"));
        assertTrue(InsightHttpServer.constantTimeBearerMatch(null, null));
    }
}
