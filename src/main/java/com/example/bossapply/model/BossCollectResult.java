package com.example.bossapply.model;

/**
 * 批量采集结果统计。
 */
public record BossCollectResult(
        int received,
        int newJobs,
        int duplicates,
        int excluded,
        int manualReview,
        String capturedAt
) {
}
