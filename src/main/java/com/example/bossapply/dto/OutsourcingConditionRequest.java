package com.example.bossapply.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 用户自定义的一条外包识别条件。
 */
public record OutsourcingConditionRequest(
        @NotBlank(message = "规则字段不能为空")
        @Pattern(regexp = "COMPANY_NAME|COMPANY_INTRODUCTION|JOB_NAME|JOB_DESCRIPTION|ALL_TEXT",
                message = "规则字段不受支持")
        String field,
        @NotBlank(message = "匹配方式不能为空")
        @Pattern(regexp = "CONTAINS|EQUALS", message = "匹配方式不受支持")
        String matchType,
        @NotBlank(message = "匹配内容不能为空")
        @Size(max = 120, message = "单条匹配内容不能超过120个字符")
        String keyword,
        @NotBlank(message = "处理动作不能为空")
        @Pattern(regexp = "EXCLUDE|REVIEW", message = "处理动作不受支持")
        String action,
        boolean enabled
) {
}
