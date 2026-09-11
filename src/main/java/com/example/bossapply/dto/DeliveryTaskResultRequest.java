package com.example.bossapply.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 登记人工投递结果请求。 */
public record DeliveryTaskResultRequest(
        @NotBlank(message = "结果状态不能为空") String resultStatus,
        @Size(max = 500, message = "失败原因不能超过500个字符") String failureReason,
        @Size(max = 500, message = "操作备注不能超过500个字符") String operatorNote,
        @NotBlank(message = "缺少一次性人工确认令牌") String confirmationToken
) {
}
