package com.example.bossapply.model;

/**
 * 外包识别结果。
 */
public record OutsourcingDecision(
        boolean excluded,
        boolean manualReview,
        String level,
        String reason,
        String matchedKeyword
) {
}
