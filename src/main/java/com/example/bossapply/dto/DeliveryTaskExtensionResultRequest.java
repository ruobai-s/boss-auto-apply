package com.example.bossapply.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Chrome 扩展回报投递任务结果请求。 */
public record DeliveryTaskExtensionResultRequest(
        @NotBlank(message = "扩展实例标识不能为空")
        @Size(max = 80, message = "扩展实例标识不能超过80个字符") String instanceId,
        @NotBlank(message = "任务执行令牌不能为空")
        @Size(max = 200, message = "任务执行令牌不能超过200个字符") String executionToken,
        @NotBlank(message = "结果状态不能为空")
        @Size(max = 30, message = "结果状态不能超过30个字符") String resultStatus,
        @Size(max = 500, message = "失败原因不能超过500个字符") String failureReason,
        @Size(max = 500, message = "操作备注不能超过500个字符") String operatorNote
) {
}
