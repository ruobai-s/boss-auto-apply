package com.example.bossapply.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Chrome 扩展连接心跳，只上报结构化页面状态，不传输 Cookie 或完整 DOM。
 */
public record ExtensionHeartbeatRequest(
        @NotBlank(message = "扩展实例标识不能为空")
        @Size(max = 80, message = "扩展实例标识不能超过80个字符")
        String instanceId,
        @NotBlank(message = "扩展版本不能为空")
        @Size(max = 30, message = "扩展版本不能超过30个字符")
        String extensionVersion,
        boolean bossTabFound,
        boolean contentScriptReady,
        @Size(max = 30, message = "页面类型不能超过30个字符")
        String pageType,
        @Size(max = 30, message = "登录状态不能超过30个字符")
        String loginState,
        @Size(max = 40, message = "安全状态不能超过40个字符")
        String securityState,
        @Size(max = 160, message = "状态说明不能超过160个字符")
        String message
) {
}
