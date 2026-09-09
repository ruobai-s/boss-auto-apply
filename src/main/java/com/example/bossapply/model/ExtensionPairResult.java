package com.example.bossapply.model;

/**
 * 扩展完成配对后的凭据，只在配对成功时返回一次。
 */
public record ExtensionPairResult(
        String extensionToken,
        String pairedAt,
        int heartbeatIntervalSeconds
) {
}
