package com.example.bossapply.security;

import com.example.bossapply.service.ExtensionConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 Chrome 扩展接口的本机来源、独立令牌和请求体边界。
 */
class ExtensionAccessFilterTest {

    private static final String ORIGIN = "chrome-extension://abcdefghijklmnopabcdefghijklmnop";

    private ExtensionConnectionService service;
    private ExtensionAccessFilter filter;

    @BeforeEach
    void setUp() {
        service = mock(ExtensionConnectionService.class);
        filter = new ExtensionAccessFilter(service);
    }

    @Test
    void shouldAllowPairingFromValidLocalExtensionOrigin() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/extension/client/pair", "127.0.0.1");
        request.setContent("{}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertEquals(ORIGIN, response.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void shouldRejectNonExtensionOriginAndNonLoopbackAddress() throws Exception {
        MockHttpServletRequest invalidOrigin = request("POST", "/api/extension/client/pair", "127.0.0.1");
        invalidOrigin.removeHeader("Origin");
        invalidOrigin.addHeader("Origin", "http://localhost:18080");
        MockHttpServletResponse invalidOriginResponse = new MockHttpServletResponse();
        filter.doFilter(invalidOrigin, invalidOriginResponse, new MockFilterChain());
        assertEquals(403, invalidOriginResponse.getStatus());

        MockHttpServletRequest remote = request("POST", "/api/extension/client/pair", "192.168.1.10");
        MockHttpServletResponse remoteResponse = new MockHttpServletResponse();
        filter.doFilter(remote, remoteResponse, new MockFilterChain());
        assertEquals(403, remoteResponse.getStatus());
        assertTrue(remoteResponse.getContentAsString().contains("EXTENSION_LOCAL_ONLY"));
    }

    @Test
    void shouldRequireIndependentTokenForHeartbeat() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/extension/client/heartbeat", "127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("EXTENSION_TOKEN_REQUIRED"));
    }

    @Test
    void shouldAllowAuthenticatedHeartbeat() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/extension/client/heartbeat", "127.0.0.1");
        request.addHeader(ExtensionAccessFilter.TOKEN_HEADER, "extension-token");
        request.setContent("{}".getBytes());
        when(service.authenticate(ORIGIN, "extension-token")).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void shouldHandleCorsPreflightWithoutToken() throws Exception {
        MockHttpServletRequest request = request("OPTIONS", "/api/extension/client/heartbeat", "127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(204, response.getStatus());
        assertEquals("POST, OPTIONS", response.getHeader("Access-Control-Allow-Methods"));
    }

    @Test
    void shouldRejectOversizedRequestBody() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/extension/client/pair", "127.0.0.1");
        request.setContent(new byte[32 * 1024 + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("EXTENSION_BODY_TOO_LARGE"));
    }

    private MockHttpServletRequest request(String method, String path, String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddress);
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(18080);
        request.addHeader("Origin", ORIGIN);
        return request;
    }
}
