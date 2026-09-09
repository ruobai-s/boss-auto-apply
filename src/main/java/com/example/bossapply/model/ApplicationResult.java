package com.example.bossapply.model;

/**
 * 单条投递动作的安全执行结果。
 */
public record ApplicationResult(
        long queueId,
        String status,
        String message,
        String jobUrl
) {
}
