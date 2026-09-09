package com.example.bossapply.security;

import com.example.bossapply.service.ExtensionConnectionService;
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
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 单独保护 Chrome 扩展接口，管理端令牌与扩展令牌互不复用。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ExtensionAccessFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Boss-Extension-Token";
    private static final String CLIENT_PATH = "/api/extension/client/";
    private static final String PAIR_PATH = "/api/extension/client/pair";
    private static final int MAX_BODY_BYTES = 32 * 1024;
    private static final Pattern EXTENSION_ID_PATTERN = Pattern.compile("[a-p]{32}");

    private final ExtensionConnectionService extensionConnectionService;

    public ExtensionAccessFilter(ExtensionConnectionService extensionConnectionService) {
        this.extensionConnectionService = extensionConnectionService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(CLIENT_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        addSecurityHeaders(response);
        if (!isLoopback(request.getRemoteAddr())) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "EXTENSION_LOCAL_ONLY", "扩展接口仅允许本机访问");
            return;
        }

        String origin = request.getHeader("Origin");
        if (!isChromeExtensionOrigin(origin)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "EXTENSION_ORIGIN_REJECTED", "扩展请求来源不受信任");
            return;
        }
        addCorsHeaders(response, origin);
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            reject(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "EXTENSION_METHOD_REJECTED", "扩展接口只接受POST请求");
            return;
        }
        if (!PAIR_PATH.equals(request.getRequestURI())
                && !extensionConnectionService.authenticate(origin, request.getHeader(TOKEN_HEADER))) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "EXTENSION_TOKEN_REQUIRED", "需要有效的扩展令牌");
            return;
        }

        HttpServletRequest boundedRequest = bufferBoundedRequestBody(request, response);
        if (boundedRequest != null) {
            filterChain.doFilter(boundedRequest, response);
        }
    }

    private void addSecurityHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    private void addCorsHeaders(HttpServletResponse response, String origin) {
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Vary", "Origin");
        response.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, " + TOKEN_HEADER);
        response.setHeader("Access-Control-Max-Age", "600");
    }

    private boolean isChromeExtensionOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            return "chrome-extension".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && EXTENSION_ID_PATTERN.matcher(host.toLowerCase(Locale.ROOT)).matches();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isLoopback(String remoteAddress) {
        try {
            return remoteAddress != null && InetAddress.getByName(remoteAddress).isLoopbackAddress();
        } catch (Exception exception) {
            return false;
        }
    }

    private HttpServletRequest bufferBoundedRequestBody(HttpServletRequest request,
                                                        HttpServletResponse response) throws IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES) {
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "EXTENSION_BODY_TOO_LARGE", "扩展请求体超过允许上限");
            return null;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = request.getInputStream().read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            total += read;
            if (total > MAX_BODY_BYTES) {
                reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                        "EXTENSION_BODY_TOO_LARGE", "扩展请求体超过允许上限");
                return null;
            }
            output.write(buffer, 0, read);
        }
        return new CachedBodyRequest(request, output.toByteArray());
    }

    private void reject(HttpServletResponse response,
                        int status,
                        String code,
                        String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    /**
     * 缓存经过大小检查的扩展请求体，供 Spring MVC 后续读取。
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
                    // 当前扩展接口使用同步 Servlet 读取，不注册异步监听器。
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
