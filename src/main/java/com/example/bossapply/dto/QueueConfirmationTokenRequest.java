package com.example.bossapply.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 申请候选岗位批量确认令牌的请求。
 */
public record QueueConfirmationTokenRequest(
        @NotNull(message = "待确认队列不能为空")
        @Size(min = 1, max = 150, message = "单次确认数量必须在1到150之间")
        List<@Positive(message = "队列编号必须大于0") Long> queueIds
) {
}
