package com.example.bossapply.dto;

import jakarta.validation.constraints.NotBlank;

/** 投递任务状态操作请求。 */
public record DeliveryTaskActionRequest(@NotBlank(message = "缺少一次性人工确认令牌") String confirmationToken) {
}
