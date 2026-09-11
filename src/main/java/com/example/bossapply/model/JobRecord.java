package com.example.bossapply.model;

import java.util.List;

/**
 * 本地职位记录，用于去重、外包过滤结果和投递状态追踪。
 */
public record JobRecord(
        long id,
        String source,
        String sourceJobId,
        String companyName,
        String companyIntroduction,
        String jobName,
        String jobDescription,
        String city,
        String salary,
        String jobUrl,
        String publishedAt,
        String experienceRequirement,
        String educationRequirement,
        String companySize,
        String companyIndustry,
        List<String> welfareTags,
        List<String> jobTags,
        Boolean urgent,
        Boolean online,
        String applyStatus,
        boolean outsourcingExcluded,
        boolean outsourcingManualReview,
        String outsourcingLevel,
        String outsourcingReason,
        String outsourcingKeyword,
        String firstSeenAt,
        String lastSeenAt
) {
}
