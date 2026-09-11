package com.example.bossapply.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Chrome 扩展领取投递任务请求。 */
public record DeliveryTaskLeaseRequest(
        @NotBlank(message = "扩展实例标识不能为空")
        @Size(max = 80, message = "扩展实例标识不能超过80个字符") String instanceId
) {
}
