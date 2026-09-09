package com.example.bossapply.service;

import com.example.bossapply.model.BrowserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 本地浏览器连接探测服务，只读取调试端点的版本、标签页地址和标题。
 */
@Service
public class BrowserConnectionService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String cdpEndpoint;

    public BrowserConnectionService(ObjectMapper objectMapper,
                                    @Value("${app.browser.cdp-url:http://127.0.0.1:9222}") String cdpEndpoint) {
        this.objectMapper = objectMapper;
        this.cdpEndpoint = trimTrailingSlash(cdpEndpoint);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * 探测浏览器调试端点和 BOSS 页面登录态，不读取 Cookie、密码或验证码。
     */
    public BrowserStatus probe() {
        try {
            HttpResponse<String> version = get("/json/version");
            if (version.statusCode() < 200 || version.statusCode() >= 300) {
                return disconnected("浏览器调试端点返回异常状态：" + version.statusCode());
            }
            HttpResponse<String> targets = get("/json/list");
            if (targets.statusCode() < 200 || targets.statusCode() >= 300) {
                return new BrowserStatus(true, false, false, cdpEndpoint,
                        "浏览器已连接，但无法读取标签页列表");
            }
            JsonNode targetArray = objectMapper.readTree(targets.body());
            boolean bossPage = false;
            for (JsonNode target : targetArray) {
                if (!"page".equalsIgnoreCase(target.path("type").asText())) {
                    continue;
                }
                String url = target.path("url").asText("");
                String title = target.path("title").asText("");
                if (isBossPage(url, title)) {
                    bossPage = true;
                    break;
                }
            }
            if (bossPage) {
                return new BrowserStatus(true, true, false, cdpEndpoint,
                        "已检测到 BOSS 页面；职位采集可通过桥接接口导入，投递执行仍需人工确认");
            }
            return new BrowserStatus(true, false, false, cdpEndpoint,
                    "浏览器已连接，但未检测到 BOSS 页面或当前登录态无法确认");
        } catch (Exception exception) {
            return disconnected("无法连接本地浏览器调试端点，请确认已启动受支持的浏览器连接器");
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(cdpEndpoint + path))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private boolean isBossPage(String url, String title) {
        String normalizedUrl = url.toLowerCase();
        String normalizedTitle = title.toLowerCase();
        boolean verificationPage = normalizedUrl.contains("/verify")
                || normalizedUrl.contains("captcha")
                || normalizedUrl.contains("/passport/")
                || normalizedTitle.contains("安全验证")
                || normalizedTitle.contains("验证码");
        return normalizedUrl.contains("zhipin.com")
                && !normalizedUrl.contains("/login")
                && !verificationPage
                && (normalizedUrl.contains("/web/") || normalizedTitle.contains("boss"));
    }

    private BrowserStatus disconnected(String message) {
        return new BrowserStatus(false, false, false, cdpEndpoint, message);
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://127.0.0.1:9222";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}

