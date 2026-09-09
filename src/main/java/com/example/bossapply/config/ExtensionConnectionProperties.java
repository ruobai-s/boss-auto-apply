package com.example.bossapply.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Chrome 扩展连接配置。
 */
@Component
@ConfigurationProperties(prefix = "app.extension")
public class ExtensionConnectionProperties {

    private int pairingTtlSeconds = 300;
    private int heartbeatTimeoutSeconds = 75;
    private int heartbeatIntervalSeconds = 30;

    public int getPairingTtlSeconds() {
        return Math.max(60, pairingTtlSeconds);
    }

    public void setPairingTtlSeconds(int pairingTtlSeconds) {
        this.pairingTtlSeconds = pairingTtlSeconds;
    }

    public int getHeartbeatTimeoutSeconds() {
        return Math.max(30, heartbeatTimeoutSeconds);
    }

    public void setHeartbeatTimeoutSeconds(int heartbeatTimeoutSeconds) {
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
    }

    public int getHeartbeatIntervalSeconds() {
        return Math.max(15, heartbeatIntervalSeconds);
    }

    public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }
}
