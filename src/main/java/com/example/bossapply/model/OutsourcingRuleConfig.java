package com.example.bossapply.model;

import java.util.List;

/**
 * 管理端可编辑的外包规则配置。
 */
public record OutsourcingRuleConfig(
        List<String> excludedCompanies,
        List<OutsourcingConditionRule> conditions
) {
}
