package com.example.bossapply.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 单条投递请求，必须携带用户刚刚取得的一次性确认令牌。
 */
public record ApplyRequest(
        boolean confirm,
        @NotBlank(message = "缺少一次性人工确认令牌")
        @Size(max = 256, message = "人工确认令牌长度不合法")
        String confirmationToken
) {
}
