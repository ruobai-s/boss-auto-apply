package com.example.bossapply.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 外包公司名单和自定义条件的批量保存请求。
 */
public record OutsourcingRuleConfigRequest(
        @NotNull(message = "公司排除名单不能为空")
        @Size(max = 100, message = "最多配置100家公司")
        List<@Size(max = 120, message = "公司名称不能超过120个字符") String> excludedCompanies,
        @NotNull(message = "自定义条件不能为空")
        @Size(max = 100, message = "最多配置100条自定义条件")
        List<@Valid OutsourcingConditionRequest> conditions
) {
}
