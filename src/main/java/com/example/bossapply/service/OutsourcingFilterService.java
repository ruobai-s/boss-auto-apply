package com.example.bossapply.service;

import com.example.bossapply.model.JobSnapshot;
import com.example.bossapply.model.OutsourcingConditionRule;
import com.example.bossapply.model.OutsourcingDecision;
import com.example.bossapply.model.OutsourcingRuleConfig;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 外包公司和外包岗位识别服务。
 */
@Service
public class OutsourcingFilterService {

    private static final List<String> CONFIRMED_KEYWORDS = List.of(
            "劳务派遣", "人才派遣", "第三方派遣", "人力外包", "it外包", "软件外包",
            "服务外包", "外包公司", "驻场服务", "派驻客户", "与第三方签约",
            "与甲方无直接劳动关系", "客户现场办公"
    );

    private static final List<String> SUSPECTED_KEYWORDS = List.of(
            "项目制", "客户现场", "现场支持", "技术服务", "人力资源服务",
            "人才服务", "项目交付", "根据客户安排"
    );

    private final OutsourcingRuleStoreService ruleStoreService;

    public OutsourcingFilterService(OutsourcingRuleStoreService ruleStoreService) {
        this.ruleStoreService = ruleStoreService;
    }

    /**
     * 使用当前已保存规则判断单个职位。
     */
    public OutsourcingDecision decide(JobSnapshot job) {
        return decide(job, ruleStoreService.get());
    }

    /**
     * 使用同一份规则快照批量判断，避免批量重新检查时重复读取数据库。
     */
    public OutsourcingDecision decide(JobSnapshot job, OutsourcingRuleConfig config) {
        String companyName = normalize(job.companyName());
        for (String company : config.excludedCompanies()) {
            if (companyName.equals(normalize(company))) {
                return new OutsourcingDecision(true, false, "CUSTOM_COMPANY_EXCLUSION",
                        "命中自定义公司排除名单", company);
            }
        }

        for (OutsourcingConditionRule condition : config.conditions()) {
            if (!condition.enabled() || !matches(job, condition)) {
                continue;
            }
            if ("EXCLUDE".equals(condition.action())) {
                return new OutsourcingDecision(true, false, "CUSTOM_CONDITION_EXCLUSION",
                        "命中自定义外包排除条件", condition.keyword());
            }
            return new OutsourcingDecision(false, true, "CUSTOM_CONDITION_REVIEW",
                    "命中自定义人工复核条件", condition.keyword());
        }

        String content = String.join(" ", safe(job.companyName()), safe(job.companyIntroduction()),
                        safe(job.jobName()), safe(job.jobDescription()))
                .toLowerCase(Locale.ROOT);
        for (String keyword : CONFIRMED_KEYWORDS) {
            if (content.contains(keyword)) {
                return new OutsourcingDecision(true, false, "CONFIRMED_OUTSOURCING",
                        "命中明确外包规则", keyword);
            }
        }
        for (String keyword : SUSPECTED_KEYWORDS) {
            if (content.contains(keyword)) {
                return new OutsourcingDecision(false, true, "SUSPECTED_OUTSOURCING",
                        "命中疑似外包规则，需要人工复核", keyword);
            }
        }
        return new OutsourcingDecision(false, false, "NORMAL", "未命中外包规则", null);
    }

    /**
     * 返回当前规则快照，供已有职位批量重新检查使用。
     */
    public OutsourcingRuleConfig currentConfig() {
        return ruleStoreService.get();
    }

    private boolean matches(JobSnapshot job, OutsourcingConditionRule condition) {
        String source = switch (condition.field()) {
            case "COMPANY_NAME" -> safe(job.companyName());
            case "COMPANY_INTRODUCTION" -> safe(job.companyIntroduction());
            case "JOB_NAME" -> safe(job.jobName());
            case "JOB_DESCRIPTION" -> safe(job.jobDescription());
            case "ALL_TEXT" -> String.join(" ", safe(job.companyName()), safe(job.companyIntroduction()),
                    safe(job.jobName()), safe(job.jobDescription()));
            default -> "";
        };
        String normalizedSource = normalize(source);
        String normalizedKeyword = normalize(condition.keyword());
        return "EQUALS".equals(condition.matchType())
                ? normalizedSource.equals(normalizedKeyword)
                : normalizedSource.contains(normalizedKeyword);
    }

    private String normalize(String value) {
        return safe(value).trim().toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
