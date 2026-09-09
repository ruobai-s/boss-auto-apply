package com.example.bossapply.security;

import com.example.bossapply.config.AppSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证管理接口的本机、局域网令牌和跨站请求防护。
 */
class AdminAccessFilterTest {

    @Test
    void shouldAllowLoopbackReadRequest() throws Exception {
        AppSecurityProperties properties = new AppSecurityProperties();
        AdminAccessFilter filter = new AdminAccessFilter(properties);
        MockHttpServletRequest request = request("GET", "127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
    }

    @Test
    void shouldRejectLoopbackPostWithoutOperationHeader() throws Exception {
        AppSecurityProperties properties = new AppSecurityProperties();
        AdminAccessFilter filter = new AdminAccessFilter(properties);
        MockHttpServletRequest request = request("POST", "127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
        assertEquals(true, response.getContentAsString().contains("REQUEST_HEADER_REQUIRED"));
    }

    @Test
    void shouldRejectLanRequestByDefault() throws Exception {
        AppSecurityProperties properties = new AppSecurityProperties();
        AdminAccessFilter filter = new AdminAccessFilter(properties);
        MockHttpServletRequest request = request("GET", "192.168.1.20");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
        assertEquals(true, response.getContentAsString().contains("LAN_ACCESS_DISABLED"));
    }

    @Test
    void shouldRequireValidTokenWhenLanAccessEnabled() throws Exception {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setAllowLan(true);
        properties.setAdminToken("safe-token");
        AdminAccessFilter filter = new AdminAccessFilter(properties);
        MockHttpServletRequest request = request("GET", "192.168.1.20");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertEquals(true, response.getContentAsString().contains("ADMIN_TOKEN_REQUIRED"));
    }

    @Test
    void shouldAllowLanRequestWithValidToken() throws Exception {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setAllowLan(true);
        properties.setAdminToken("safe-token");
        AdminAccessFilter filter = new AdminAccessFilter(properties);
        MockHttpServletRequest request = request("POST", "192.168.1.20");
        request.addHeader(AdminAccessFilter.REQUEST_HEADER, AdminAccessFilter.REQUEST_HEADER_VALUE);
        request.addHeader(AdminAccessFilter.TOKEN_HEADER, "safe-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void shouldRejectCrossOriginRequest() throws Exception {
        AppSecurityProperties properties = new AppSecurityProperties();
        AdminAccessFilter filter = new AdminAccessFilter(properties);
        MockHttpServletRequest request = request("POST", "127.0.0.1");
        request.addHeader("Origin", "https://evil.example");
        request.addHeader(AdminAccessFilter.REQUEST_HEADER, AdminAccessFilter.REQUEST_HEADER_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
        assertEquals(true, response.getContentAsString().contains("ORIGIN_REJECTED"));
    }

    @Test
    void shouldRejectRequestBodyLargerThanConfiguredLimit() throws Exception {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setMaxRequestBodyBytes(4);
        AdminAccessFilter filter = new AdminAccessFilter(properties);
        MockHttpServletRequest request = request("POST", "127.0.0.1");
        request.addHeader(AdminAccessFilter.REQUEST_HEADER, AdminAccessFilter.REQUEST_HEADER_VALUE);
        request.setContent("12345".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(413, response.getStatus());
        assertEquals(true, response.getContentAsString().contains("REQUEST_BODY_TOO_LARGE"));
    }

    @Test
    void shouldRejectChunkedBodyAfterReadingPastLimit() throws Exception {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setMaxRequestBodyBytes(4);
        AdminAccessFilter filter = new AdminAccessFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/jobs/register") {
            @Override
            public long getContentLengthLong() {
                return -1L;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setRemoteAddr("127.0.0.1");
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(18080);
        request.addHeader("Transfer-Encoding", "chunked");
        request.addHeader(AdminAccessFilter.REQUEST_HEADER, AdminAccessFilter.REQUEST_HEADER_VALUE);
        request.setContent("12345".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(413, response.getStatus());
    }

    @Test
    void shouldRateLimitSensitiveOperationsWithoutTrustingForwardedAddress() throws Exception {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setSensitiveRequestsPerMinute(2);
        AdminAccessFilter filter = new AdminAccessFilter(properties);

        MockHttpServletResponse lastResponse = null;
        for (int index = 0; index < 3; index++) {
            MockHttpServletRequest request = request("POST", "127.0.0.1");
            request.setRequestURI("/api/browser/embedded/collect");
            request.addHeader("X-Forwarded-For", "192.168.1." + index);
            request.addHeader(AdminAccessFilter.REQUEST_HEADER, AdminAccessFilter.REQUEST_HEADER_VALUE);
            lastResponse = new MockHttpServletResponse();
            filter.doFilter(request, lastResponse, new MockFilterChain());
        }

        assertEquals(429, lastResponse.getStatus());
        assertEquals(true, lastResponse.getHeader("Retry-After") != null);
    }

    private MockHttpServletRequest request(String method, String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/system/status");
        request.setRemoteAddr(remoteAddress);
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(18080);
        return request;
    }
}
