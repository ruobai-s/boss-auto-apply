package com.example.bossapply.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 本地管理端安全配置。
 */
@Component
@ConfigurationProperties(prefix = "app.security")
public class AppSecurityProperties {

    private boolean allowLan;
    private String adminToken = "";
    private int requestsPerMinute = 120;
    private int sensitiveRequestsPerMinute = 20;
    private long maxRequestBodyBytes = 1_048_576L;
    private int confirmationTokenTtlSeconds = 60;

    public boolean isAllowLan() {
        return allowLan;
    }

    public void setAllowLan(boolean allowLan) {
        this.allowLan = allowLan;
    }

    public String getAdminToken() {
        return adminToken;
    }

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    public int getRequestsPerMinute() {
        return Math.max(1, requestsPerMinute);
    }

    public void setRequestsPerMinute(int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    public int getSensitiveRequestsPerMinute() {
        return Math.max(1, sensitiveRequestsPerMinute);
    }

    public void setSensitiveRequestsPerMinute(int sensitiveRequestsPerMinute) {
        this.sensitiveRequestsPerMinute = sensitiveRequestsPerMinute;
    }

    public long getMaxRequestBodyBytes() {
        return Math.max(1L, maxRequestBodyBytes);
    }

    public void setMaxRequestBodyBytes(long maxRequestBodyBytes) {
        this.maxRequestBodyBytes = maxRequestBodyBytes;
    }

    public int getConfirmationTokenTtlSeconds() {
        return Math.max(10, confirmationTokenTtlSeconds);
    }

    public void setConfirmationTokenTtlSeconds(int confirmationTokenTtlSeconds) {
        this.confirmationTokenTtlSeconds = confirmationTokenTtlSeconds;
    }
}

