package com.example.bossapply.model;

/**
 * 本地浏览器连接和 BOSS 登录状态探测结果。
 */
public record BrowserStatus(
        boolean browserConnected,
        boolean loginValid,
        boolean automationReady,
        String endpoint,
        String message
) {
}
