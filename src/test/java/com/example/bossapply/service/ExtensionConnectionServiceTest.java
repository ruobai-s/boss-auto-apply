package com.example.bossapply.service;

import com.example.bossapply.config.ExtensionConnectionProperties;
import com.example.bossapply.dto.ExtensionHeartbeatRequest;
import com.example.bossapply.dto.ExtensionJobCollectRequest;
import com.example.bossapply.dto.ExtensionPairRequest;
import com.example.bossapply.dto.JobRecordRequest;
import com.example.bossapply.infrastructure.SqliteDatabaseService;
import com.example.bossapply.model.ExtensionConnectionStatus;
import com.example.bossapply.model.ExtensionPairResult;
import com.example.bossapply.model.ExtensionPairingView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Chrome 扩展一次性配对、令牌绑定、连接状态迁移和职位采集前置校验。
 */
class ExtensionConnectionServiceTest {

    private static final String ORIGIN = "chrome-extension://abcdefghijklmnopabcdefghijklmnop";

    @TempDir
    Path tempDir;

    private ExtensionConnectionService service;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        SqliteDatabaseService databaseService = new SqliteDatabaseService();
        ReflectionTestUtils.setField(databaseService, "databasePath", tempDir.resolve("extension-test.db").toString());
        databaseService.initialize();

        ExtensionConnectionProperties properties = new ExtensionConnectionProperties();
        properties.setPairingTtlSeconds(60);
        properties.setHeartbeatIntervalSeconds(30);
        properties.setHeartbeatTimeoutSeconds(75);
        clock = new MutableClock(Instant.parse("2026-09-09T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
        service = new ExtensionConnectionService(databaseService, properties, clock);
    }

    @Test
    void shouldPairAndAuthenticateOnlyCurrentToken() {
        ExtensionPairResult first = pair("profile-a");

        assertTrue(service.authenticate(ORIGIN, first.extensionToken()));
        assertFalse(service.authenticate("chrome-extension://pppppppppppppppppppppppppppppppp", first.extensionToken()));

        ExtensionPairResult second = pair("profile-a");

        assertFalse(service.authenticate(ORIGIN, first.extensionToken()));
        assertTrue(service.authenticate(ORIGIN, second.extensionToken()));
    }

    @Test
    void shouldRejectWrongAndExpiredPairingCode() {
        ExtensionPairingView pairing = service.startPairing();
        assertThrows(IllegalArgumentException.class, () -> service.pair(
                new ExtensionPairRequest("AAAA-BBBB", "profile-a", "0.1.0"), ORIGIN));

        clock.advanceSeconds(61);

        assertThrows(IllegalArgumentException.class, () -> service.pair(
                new ExtensionPairRequest(pairing.pairingCode(), "profile-a", "0.1.0"), ORIGIN));
    }

    @Test
    void shouldMoveThroughConnectionStatesAndBlockSecurityPage() {
        ExtensionPairResult paired = pair("profile-a");
        assertEquals("EXTENSION_OFFLINE", service.status().state());

        heartbeat(paired.extensionToken(), false, false, "NONE", "UNKNOWN", "UNKNOWN");
        assertEquals("BOSS_TAB_NOT_FOUND", service.status().state());

        heartbeat(paired.extensionToken(), true, false, "JOB_LIST", "UNKNOWN", "NORMAL");
        assertEquals("CONTENT_SCRIPT_UNAVAILABLE", service.status().state());

        heartbeat(paired.extensionToken(), true, true, "SECURITY", "UNKNOWN", "ACCESS_RESTRICTED");
        ExtensionConnectionStatus blocked = service.status();
        assertEquals("SECURITY_CHECK_REQUIRED", blocked.state());
        assertTrue(blocked.securityBlocked());
        assertFalse(blocked.ready());

        heartbeat(paired.extensionToken(), true, true, "JOB_LIST", "NOT_LOGGED_IN", "NORMAL");
        assertEquals("NOT_LOGGED_IN", service.status().state());

        heartbeat(paired.extensionToken(), true, true, "JOB_LIST", "LOGGED_IN", "NORMAL");
        assertEquals("READY", service.status().state());
        assertTrue(service.status().ready());
    }

    @Test
    void shouldRejectMismatchedExtensionInstance() {
        ExtensionPairResult paired = pair("profile-a");
        ExtensionHeartbeatRequest request = new ExtensionHeartbeatRequest(
                "profile-b", "0.1.0", true, true, "JOB_LIST", "LOGGED_IN", "NORMAL", "正常");

        assertThrows(SecurityException.class, () -> service.heartbeat(request, ORIGIN, paired.extensionToken()));
    }

    @Test
    void shouldMarkHeartbeatAsOfflineAfterTimeout() {
        ExtensionPairResult paired = pair("profile-a");
        heartbeat(paired.extensionToken(), true, true, "JOB_LIST", "LOGGED_IN", "NORMAL");
        assertEquals("READY", service.status().state());

        clock.advanceSeconds(76);

        assertEquals("EXTENSION_OFFLINE", service.status().state());
    }

    @Test
    void shouldAllowCollectionOnlyForReadyJobList() {
        ExtensionPairResult paired = pair("profile-a");
        heartbeat(paired.extensionToken(), true, true, "JOB_LIST", "LOGGED_IN", "NORMAL");

        service.validateJobCollection(collectionRequest("profile-a", "JOB_LIST", "LOGGED_IN", "NORMAL"),
                ORIGIN, paired.extensionToken());
    }

    @Test
    void shouldRejectCollectionFromWrongInstanceOrUnsafePage() {
        ExtensionPairResult paired = pair("profile-a");
        heartbeat(paired.extensionToken(), true, true, "JOB_LIST", "LOGGED_IN", "NORMAL");

        assertThrows(SecurityException.class, () -> service.validateJobCollection(
                collectionRequest("profile-b", "JOB_LIST", "LOGGED_IN", "NORMAL"),
                ORIGIN, paired.extensionToken()));

        heartbeat(paired.extensionToken(), true, true, "SECURITY", "UNKNOWN", "ACCESS_RESTRICTED");
        assertThrows(IllegalArgumentException.class, () -> service.validateJobCollection(
                collectionRequest("profile-a", "SECURITY", "UNKNOWN", "ACCESS_RESTRICTED"),
                ORIGIN, paired.extensionToken()));
    }

    @Test
    void shouldRejectCollectionAfterHeartbeatTimeout() {
        ExtensionPairResult paired = pair("profile-a");
        heartbeat(paired.extensionToken(), true, true, "JOB_LIST", "LOGGED_IN", "NORMAL");
        clock.advanceSeconds(76);

        assertThrows(IllegalArgumentException.class, () -> service.validateJobCollection(
                collectionRequest("profile-a", "JOB_LIST", "LOGGED_IN", "NORMAL"),
                ORIGIN, paired.extensionToken()));
    }

    private ExtensionPairResult pair(String instanceId) {
        ExtensionPairingView pairing = service.startPairing();
        return service.pair(new ExtensionPairRequest(pairing.pairingCode(), instanceId, "0.1.0"), ORIGIN);
    }

    private void heartbeat(String token,
                           boolean bossTabFound,
                           boolean contentScriptReady,
                           String pageType,
                           String loginState,
                           String securityState) {
        service.heartbeat(new ExtensionHeartbeatRequest(
                "profile-a", "0.1.0", bossTabFound, contentScriptReady,
                pageType, loginState, securityState, "测试心跳"), ORIGIN, token);
    }

    private ExtensionJobCollectRequest collectionRequest(String instanceId,
                                                         String pageType,
                                                         String loginState,
                                                         String securityState) {
        JobRecordRequest job = new JobRecordRequest(
                "BOSS", "job-001", "示例科技", "Java开发", "北京", "20-30K",
                "https://www.zhipin.com/job_detail/job-001.html");
        return new ExtensionJobCollectRequest(instanceId, "0.1.0", pageType, loginState,
                securityState, List.of(job), "2026-09-09T16:00:00+08:00");
    }

    /**
     * 测试专用可推进时钟，避免测试依赖真实等待。
     */
    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
