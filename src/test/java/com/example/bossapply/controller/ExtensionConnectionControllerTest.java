package com.example.bossapply.controller;

import com.example.bossapply.dto.ExtensionJobCollectRequest;
import com.example.bossapply.dto.JobRecordRequest;
import com.example.bossapply.model.BossCollectResult;
import com.example.bossapply.model.ExtensionConnectionStatus;
import com.example.bossapply.model.ExtensionPairingView;
import com.example.bossapply.service.BossCollectorService;
import com.example.bossapply.service.ExtensionConnectionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        ExtensionConnectionController controller = new ExtensionConnectionController(service, mock(BossCollectorService.class));

        assertEquals(pairing, controller.startPairing().getBody());
        assertEquals(status, controller.status().getBody());
    }

    @Test
    void shouldValidateAndPersistExtensionJobs() {
        ExtensionConnectionService connectionService = mock(ExtensionConnectionService.class);
        BossCollectorService collectorService = mock(BossCollectorService.class);
        BossCollectResult expected = new BossCollectResult(1, 1, 0, 0, 0,
                "2026-09-09T16:00:00+08:00");
        when(collectorService.collect(any())).thenReturn(expected);
        ExtensionConnectionController controller = new ExtensionConnectionController(connectionService, collectorService);
        ExtensionJobCollectRequest request = new ExtensionJobCollectRequest(
                "profile-a", "0.2.0", "JOB_LIST", "LOGGED_IN", "NORMAL",
                List.of(new JobRecordRequest("BOSS", "job-001", "示例科技", "Java开发",
                        "北京", "20-30K", "https://www.zhipin.com/job_detail/job-001.html")),
                "2026-09-09T16:00:00+08:00");

        assertEquals(expected, controller.collectJobs(request,
                "chrome-extension://abcdefghijklmnopabcdefghijklmnop", "token").getBody());
        verify(connectionService).validateJobCollection(request,
                "chrome-extension://abcdefghijklmnopabcdefghijklmnop", "token");
        verify(collectorService).collect(any());
    }
}
