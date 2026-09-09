package com.example.bossapply.model;

/**
 * 内置浏览器职位采集结果。
 */
public record EmbeddedBrowserCollectResult(
        String state,
        int jobsFound,
        BossCollectResult persisted,
        String message,
        String capturedAt
) {
}
