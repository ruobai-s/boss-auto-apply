package com.example.bossapply.model;

/**
 * 保存外包规则后的结果，包含重新检查的本地职位数量。
 */
public record OutsourcingRuleSaveResult(
        OutsourcingRuleConfig config,
        int reevaluatedJobs
) {
}
