package com.example.bossapply.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 注册职位快照请求，字段来源于职位列表或详情页采集结果。
 */
public record JobRecordRequest(
        @NotBlank(message = "职位来源不能为空") @Size(max = 32, message = "职位来源长度不能超过32个字符") String source,
        @NotBlank(message = "来源职位编号不能为空") @Size(max = 128, message = "职位编号长度不能超过128个字符") String sourceJobId,
        @Size(max = 200, message = "公司名称长度不能超过200个字符") String companyName,
        @Size(max = 5000, message = "公司介绍长度不能超过5000个字符") String companyIntroduction,
        @Size(max = 200, message = "职位名称长度不能超过200个字符") String jobName,
        @Size(max = 10000, message = "职位描述长度不能超过10000个字符") String jobDescription,
        @Size(max = 100, message = "城市长度不能超过100个字符") String city,
        @Size(max = 100, message = "薪资长度不能超过100个字符") String salary,
        @Size(max = 1000, message = "职位链接长度不能超过1000个字符") String jobUrl,
        @Size(max = 100, message = "发布时间长度不能超过100个字符") String publishedAt,
        @Size(max = 100, message = "工作经验长度不能超过100个字符") String experienceRequirement,
        @Size(max = 100, message = "学历要求长度不能超过100个字符") String educationRequirement,
        @Size(max = 100, message = "公司规模长度不能超过100个字符") String companySize,
        @Size(max = 200, message = "公司行业长度不能超过200个字符") String companyIndustry,
        @Size(max = 20, message = "福利标签不能超过20个") List<@Size(max = 50, message = "单个福利标签不能超过50个字符") String> welfareTags,
        @Size(max = 20, message = "职位标签不能超过20个") List<@Size(max = 50, message = "单个职位标签不能超过50个字符") String> jobTags,
        Boolean urgent,
        Boolean online
) {

    /**
     * 保留详情字段扩展前的调用方式，便于已有导入和测试平滑升级。
     */
    public JobRecordRequest(String source, String sourceJobId, String companyName, String companyIntroduction,
                            String jobName, String jobDescription, String city, String salary,
                            String jobUrl, String publishedAt) {
        this(source, sourceJobId, companyName, companyIntroduction, jobName, jobDescription, city, salary,
                jobUrl, publishedAt, null, null, null, null, List.of(), List.of(), null, null);
    }

    /**
     * 保留旧版列表卡片调用方式，便于已有采集适配器平滑升级。
     */
    public JobRecordRequest(String source, String sourceJobId, String companyName, String jobName,
                            String city, String salary, String jobUrl) {
        this(source, sourceJobId, companyName, null, jobName, null, city, salary, jobUrl, null);
    }
}
