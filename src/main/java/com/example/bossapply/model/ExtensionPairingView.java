package com.example.bossapply.model;

/**
 * 管理端展示的一次性扩展配对信息。
 */
public record ExtensionPairingView(
        String pairingCode,
        String expiresAt
) {
}
