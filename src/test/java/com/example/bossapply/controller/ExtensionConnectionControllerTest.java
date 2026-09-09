package com.example.bossapply.controller;

import com.example.bossapply.model.ExtensionConnectionStatus;
import com.example.bossapply.model.ExtensionPairingView;
import com.example.bossapply.service.ExtensionConnectionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证管理端扩展连接中心接口返回服务层结果。
 */
class ExtensionConnectionControllerTest {

    @Test
    void shouldReturnPairingAndConnectionStatus() {
        ExtensionConnectionService service = mock(ExtensionConnectionService.class);
        ExtensionPairingView pairing = new ExtensionPairingView("ABCD-EFGH", "2026-09-09T16:05:00+08:00");
        ExtensionConnectionStatus status = new ExtensionConnectionStatus(
                "READY", true, true, true, "extension-id", "instance-id", "0.1.0",
                true, true, true, false, "JOB_LIST", "LOGGED_IN", "NORMAL",
                "连接就绪", "2026-09-09T16:00:00+08:00", "2026-09-09T16:01:00+08:00");
        when(service.startPairing()).thenReturn(pairing);
        when(service.status()).thenReturn(status);
        ExtensionConnectionController controller = new ExtensionConnectionController(service);

        assertEquals(pairing, controller.startPairing().getBody());
        assertEquals(status, controller.status().getBody());
    }
}
