package com.example.bossapply.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Chrome 扩展首次配对请求。
 */
public record ExtensionPairRequest(
        @NotBlank(message = "配对码不能为空")
        @Size(max = 32, message = "配对码长度不合法")
        String pairingCode,
        @NotBlank(message = "扩展实例标识不能为空")
        @Size(max = 80, message = "扩展实例标识不能超过80个字符")
        String instanceId,
        @NotBlank(message = "扩展版本不能为空")
        @Size(max = 30, message = "扩展版本不能超过30个字符")
        String extensionVersion
) {
}
