package com.example.bossapply.security;

import com.example.bossapply.config.AppSecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 保护本地管理接口，阻止匿名局域网访问、跨站请求、超大请求和高频敏感操作。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminAccessFilter extends OncePerRequestFilter {

    public static final String REQUEST_HEADER = "X-Boss-Requested-With";
    public static final String REQUEST_HEADER_VALUE = "BossAutoApply";
    public static final String TOKEN_HEADER = "X-Boss-Admin-Token";

    private static final long RATE_WINDOW_MILLIS = 60_000L;
    private static final Set<String> SENSITIVE_PATHS = Set.of(
            "/api/filter/rules",
            "/api/queue/rebuild",
            "/api/queue/confirm",
            "/api/queue/confirmation-token",
            "/api/extension/pairing/start",
            "/api/extension/unpair"
    );

    private final AppSecurityProperties securityProperties;
    private final ConcurrentHashMap<String, RateWindow> rateWindows = new ConcurrentHashMap<>();
    private final AtomicLong lastRateCleanupAt = new AtomicLong();

    public AdminAccessFilter(AppSecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 扩展客户端使用独立来源和独立令牌，由 ExtensionAccessFilter 单独保护。
        return request.getRequestURI().startsWith("/api/extension/client/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        addSecurityHeaders(response);
        if (!request.getRequestURI().startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!isSameOrigin(request)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "ORIGIN_REJECTED", "请求来源不受信任");
            return;
        }

        if (isStateChanging(request.getMethod())
                && !REQUEST_HEADER_VALUE.equals(request.getHeader(REQUEST_HEADER))) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "REQUEST_HEADER_REQUIRED", "缺少管理端操作标识");
            return;
        }

        if (!isLoopback(request.getRemoteAddr())) {
            if (!securityProperties.isAllowLan()) {
                reject(response, HttpServletResponse.SC_FORBIDDEN, "LAN_ACCESS_DISABLED", "局域网访问默认关闭");
                return;
            }
            String configuredToken = securityProperties.getAdminToken();
            if (configuredToken.isBlank()) {
                reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ADMIN_TOKEN_NOT_CONFIGURED",
                        "局域网访问已开启，但尚未配置管理令牌");
                return;
            }
            if (!constantTimeEquals(configuredToken, request.getHeader(TOKEN_HEADER))) {
                reject(response, HttpServletResponse.SC_UNAUTHORIZED, "ADMIN_TOKEN_REQUIRED", "需要有效的管理令牌");
                return;
            }
        }

        if (!allowByRateLimit(request, response)) {
            return;
        }

        HttpServletRequest boundedRequest = bufferBoundedRequestBody(request, response);
        if (boundedRequest == null) {
            return;
        }
        filterChain.doFilter(boundedRequest, response);
    }

    private void addSecurityHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
    }

    private boolean isStateChanging(String method) {
        return !("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method));
    }

    private boolean allowByRateLimit(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long now = Instant.now().toEpochMilli();
        cleanupExpiredRateWindows(now);
        boolean sensitive = isSensitivePath(request.getRequestURI());
        int limit = sensitive
                ? securityProperties.getSensitiveRequestsPerMinute()
                : securityProperties.getRequestsPerMinute();
        // 只使用容器提供的远端地址，不信任可伪造的 X-Forwarded-For。
        String key = request.getRemoteAddr() + (sensitive ? ":S" : ":N");
        RateWindow window = rateWindows.compute(key, (ignored, existing) -> {
            if (existing == null || now - existing.startedAt() >= RATE_WINDOW_MILLIS) {
                return new RateWindow(now, 1);
            }
            return new RateWindow(existing.startedAt(), existing.count() + 1);
        });
        if (window.count() <= limit) {
            return true;
        }
        long retryAfterSeconds = Math.max(1L,
                (RATE_WINDOW_MILLIS - (now - window.startedAt()) + 999L) / 1000L);
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        reject(response, 429, "RATE_LIMITED", "请求过于频繁，请稍后重试");
        return false;
    }

    private boolean isSensitivePath(String requestUri) {
        if (requestUri == null) {
            return false;
        }
        return SENSITIVE_PATHS.contains(requestUri);
    }

    private void cleanupExpiredRateWindows(long now) {
        long lastCleanup = lastRateCleanupAt.get();
        if (now - lastCleanup < RATE_WINDOW_MILLIS
                || !lastRateCleanupAt.compareAndSet(lastCleanup, now)) {
            return;
        }
        rateWindows.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= RATE_WINDOW_MILLIS * 2);
    }

    private HttpServletRequest bufferBoundedRequestBody(HttpServletRequest request,
                                                        HttpServletResponse response) throws IOException {
        if (!isStateChanging(request.getMethod()) || !hasRequestBody(request)) {
            return request;
        }
        long maximum = securityProperties.getMaxRequestBodyBytes();
        if (request.getContentLengthLong() > maximum) {
            reject(response, 413, "REQUEST_BODY_TOO_LARGE", "请求内容超过允许上限");
            return null;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0L;
        int read;
        while ((read = request.getInputStream().read(buffer)) >= 0) {
            total += read;
            if (total > maximum) {
                reject(response, 413, "REQUEST_BODY_TOO_LARGE", "请求内容超过允许上限");
                return null;
            }
            output.write(buffer, 0, read);
        }
        return new CachedBodyRequest(request, output.toByteArray());
    }

    private boolean hasRequestBody(HttpServletRequest request) {
        String transferEncoding = request.getHeader("Transfer-Encoding");
        return request.getContentLengthLong() > 0
                || (transferEncoding != null && !transferEncoding.isBlank());
    }

    private boolean isLoopback(String remoteAddress) {
        try {
            return remoteAddress != null && InetAddress.getByName(remoteAddress).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isSameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }
        try {
            URI originUri = URI.create(origin);
            int originPort = originUri.getPort() >= 0 ? originUri.getPort() : defaultPort(originUri.getScheme());
            int requestPort = request.getServerPort();
            return originUri.getScheme() != null
                    && originUri.getScheme().equalsIgnoreCase(request.getScheme())
                    && originUri.getHost() != null
                    && originUri.getHost().equalsIgnoreCase(request.getServerName())
                    && originPort == requestPort;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    private record RateWindow(long startedAt, int count) {
    }

    /**
     * 缓存经过字节上限校验的请求体，供 Spring 后续反序列化重复读取。
     */
    private static class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // 当前应用使用同步 Servlet 读取，不注册异步监听器。
                }

                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) {
                    return input.read(bytes, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}

