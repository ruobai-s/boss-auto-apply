package com.example.bossapply.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Chrome 扩展上报投递任务阶段请求。 */
public record DeliveryTaskStageRequest(
        @NotBlank(message = "扩展实例标识不能为空")
        @Size(max = 80, message = "扩展实例标识不能超过80个字符") String instanceId,
        @NotBlank(message = "任务执行令牌不能为空")
        @Size(max = 200, message = "任务执行令牌不能超过200个字符") String executionToken,
        @NotBlank(message = "任务阶段不能为空")
        @Size(max = 40, message = "任务阶段不能超过40个字符") String stage
) {
}
