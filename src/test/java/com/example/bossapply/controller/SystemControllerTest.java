package com.example.bossapply.controller;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证管理端状态接口只返回管理端自身状态，不再探测独立浏览器。
 */
class SystemControllerTest {

    @Test
    void shouldReturnManagementApplicationStatus() {
        SystemController controller = new SystemController();

        Map<String, Object> result = controller.status();

        assertEquals("boss-auto-apply", result.get("application"));
        assertEquals("LOCAL", result.get("deployment"));
        assertEquals("管理端运行正常", result.get("message"));
    }
}
