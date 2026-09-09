package com.example.bossapply.model;

/**
 * Chrome 扩展连接中心状态，不包含扩展令牌、Cookie 或账号明文。
 */
public record ExtensionConnectionStatus(
        String state,
        boolean paired,
        boolean online,
        boolean ready,
        String extensionId,
        String instanceId,
        String extensionVersion,
        boolean bossTabFound,
        boolean contentScriptReady,
        boolean loginValid,
        boolean securityBlocked,
        String pageType,
        String loginState,
        String securityState,
        String message,
        String pairedAt,
        String lastHeartbeatAt
) {
}
