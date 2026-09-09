package com.example.bossapply.model;

/**
 * 用于执行外包识别的职位数据快照。
 */
public record JobSnapshot(
        String companyName,
        String companyIntroduction,
        String jobName,
        String jobDescription
) {
}
