package com.example.bossapply.model;

/**
 * 扩展心跳处理结果。
 */
public record ExtensionHeartbeatResult(
        boolean accepted,
        String state,
        String serverTime
) {
}
