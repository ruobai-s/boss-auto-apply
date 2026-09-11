package com.example.bossapply.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Chrome 扩展用户主动采集职位请求，只接收当前已加载职位的结构化字段。
 */
public record ExtensionJobCollectRequest(
        @NotBlank(message = "扩展实例标识不能为空")
        @Size(max = 80, message = "扩展实例标识不能超过80个字符")
        String instanceId,
        @NotBlank(message = "扩展版本不能为空")
        @Size(max = 30, message = "扩展版本不能超过30个字符")
        String extensionVersion,
        @NotBlank(message = "页面类型不能为空")
        @Size(max = 30, message = "页面类型不能超过30个字符")
        String pageType,
        @NotBlank(message = "登录状态不能为空")
        @Size(max = 30, message = "登录状态不能超过30个字符")
        String loginState,
        @NotBlank(message = "安全状态不能为空")
        @Size(max = 40, message = "安全状态不能超过40个字符")
        String securityState,
        @NotNull(message = "职位列表不能为空")
        @Size(min = 1, max = 50, message = "单次必须采集1到50个职位")
        @Valid
        List<JobRecordRequest> jobs,
        @Size(max = 100, message = "采集时间长度不能超过100个字符")
        String capturedAt
) {
}
