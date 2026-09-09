package com.example.bossapply.model;

/**
 * 已持久化的一条外包自定义条件。
 */
public record OutsourcingConditionRule(
        String field,
        String matchType,
        String keyword,
        String action,
        boolean enabled
) {
}
