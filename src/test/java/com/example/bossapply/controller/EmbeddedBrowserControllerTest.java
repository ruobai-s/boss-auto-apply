package com.example.bossapply.controller;

import com.example.bossapply.config.AppSecurityProperties;
import com.example.bossapply.service.EmbeddedBrowserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 验证浏览器截图默认关闭，避免管理端意外暴露账户页面。
 */
class EmbeddedBrowserControllerTest {

    @Test
    void shouldDisablePreviewByDefault() {
        EmbeddedBrowserService browserService = mock(EmbeddedBrowserService.class);
        AppSecurityProperties properties = new AppSecurityProperties();
        EmbeddedBrowserController controller = new EmbeddedBrowserController(browserService, properties);

        assertEquals(HttpStatus.FORBIDDEN, controller.preview().getStatusCode());
        verifyNoInteractions(browserService);
    }
}
