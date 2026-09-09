package com.example.bossapply.controller;

import com.example.bossapply.model.EmbeddedBrowserStatus;
import com.example.bossapply.service.BrowserConnectionService;
import com.example.bossapply.service.EmbeddedBrowserService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证管理端状态接口不会在内置浏览器运行时重复探测旧 CDP 端点。
 */
class SystemControllerTest {

    @Test
    void shouldSkipLegacyCdpProbeWhenEmbeddedBrowserIsRunning() {
        BrowserConnectionService browserConnectionService = mock(BrowserConnectionService.class);
        EmbeddedBrowserService embeddedBrowserService = mock(EmbeddedBrowserService.class);
        when(embeddedBrowserService.status()).thenReturn(new EmbeddedBrowserStatus(
                "STARTED_NEEDS_LOGIN", true, true, false, false,
                "https://www.zhipin.com/web/user/", "", "登录静默模式", "页面加载完成",
                "2026-09-08T09:00:00+08:00"));
        SystemController controller = new SystemController(browserConnectionService, embeddedBrowserService);

        Map<String, Object> result = controller.status();

        assertEquals(true, result.get("browserConnected"));
        assertEquals("managed-edge-cdp", result.get("browserEndpoint"));
        verifyNoInteractions(browserConnectionService);
    }
}

