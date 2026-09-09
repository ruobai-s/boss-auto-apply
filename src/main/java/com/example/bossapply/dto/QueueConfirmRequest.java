package com.example.bossapply.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量确认候选职位请求。
 */
public record QueueConfirmRequest(
        @NotNull(message = "待确认队列不能为空")
        @Size(min = 1, max = 150, message = "单次确认数量必须在1到150之间")
        List<@Positive(message = "队列编号必须大于0") Long> queueIds,
        boolean confirm,
        @NotBlank(message = "缺少一次性人工确认令牌")
        @Size(max = 256, message = "人工确认令牌长度不合法")
        String confirmationToken
) {
}
