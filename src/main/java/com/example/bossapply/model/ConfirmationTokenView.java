package com.example.bossapply.model;

/**
 * 一次性人工确认令牌，仅用于紧随其后的明确敏感操作。
 */
public record ConfirmationTokenView(String token, String expiresAt) {
}
