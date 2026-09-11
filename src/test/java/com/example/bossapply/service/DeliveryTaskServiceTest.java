package com.example.bossapply.service;

import com.example.bossapply.dto.DeliveryPolicyRequest;
import com.example.bossapply.dto.DeliveryTaskExtensionResultRequest;
import com.example.bossapply.dto.DeliveryTaskLeaseRequest;
import com.example.bossapply.dto.DeliveryTaskStageRequest;
import com.example.bossapply.dto.JobRecordRequest;
import com.example.bossapply.dto.QueueConfirmRequest;
import com.example.bossapply.infrastructure.SqliteDatabaseService;
import com.example.bossapply.model.CityPreference;
import com.example.bossapply.model.DeliveryTaskLeaseView;
import com.example.bossapply.model.DeliveryTaskProgressView;
import com.example.bossapply.model.DeliveryTaskSummaryView;
import com.example.bossapply.model.QueueItemView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 候选确认后自动建任务、扩展领取和结果回报测试。 */
@SpringBootTest
@TestPropertySource(properties = "app.database.path=target/test-data/delivery-task-test.db")
class DeliveryTaskServiceTest {

    private static final LocalDate TEST_DATE = LocalDate.of(2026, 9, 11);

    @Autowired
    private DeliveryTaskService deliveryTaskService;

    @Autowired
    private DeliveryQueueService deliveryQueueService;

    @Autowired
    private PolicyStoreService policyStoreService;

    @Autowired
    private JobRecordService jobRecordService;

    @Autowired
    private SqliteDatabaseService databaseService;

    @BeforeEach
    void 清理测试数据() throws Exception {
        try (Connection connection = databaseService.open()) {
            for (String table : List.of("delivery_task_event", "delivery_task_item", "delivery_task", "delivery_queue", "job_record", "city_preference", "app_setting")) {
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table)) {
                    statement.executeUpdate();
                }
            }
        }
        policyStoreService.save(new DeliveryPolicyRequest(10, List.of(
                new CityPreference("北京", 1, 100, true, true)
        )));
    }

    @Test
    void 候选确认后自动创建任务并串行完成成功回报() {
        注册职位("task-success");
        long queueId = deliveryQueueService.rebuild(TEST_DATE).get(0).queueId();
        确认队列(queueId);

        List<DeliveryTaskSummaryView> tasks = deliveryTaskService.list(TEST_DATE.toString());
        assertEquals(1, tasks.size());
        assertEquals("WAITING", tasks.get(0).taskStatus());
        assertEquals(1, tasks.get(0).totalCount());

        DeliveryTaskLeaseView lease = deliveryTaskService.lease(new DeliveryTaskLeaseRequest("chrome-test"));
        assertTrue(lease.available());
        assertFalse(deliveryTaskService.lease(new DeliveryTaskLeaseRequest("chrome-second")).available());
        deliveryTaskService.stage(lease.taskItemId(),
                new DeliveryTaskStageRequest("chrome-test", lease.executionToken(), "RUNNING"));

        DeliveryTaskProgressView progress = deliveryTaskService.result(lease.taskItemId(),
                new DeliveryTaskExtensionResultRequest("chrome-test", lease.executionToken(), "SUCCESS", "", "现场冒烟测试"));
        assertEquals(1, progress.success());
        assertEquals(1, progress.completed());
        assertEquals("APPLIED", jobRecordService.get("BOSS", "task-success").applyStatus());
        assertEquals("APPLIED", deliveryQueueService.list(TEST_DATE, null).get(0).queueStatus());
        assertEquals("COMPLETED", deliveryTaskService.list(TEST_DATE.toString()).get(0).taskStatus());
    }

    @Test
    void 阻断结果进入未知状态且不自动重试() {
        注册职位("task-blocked");
        long queueId = deliveryQueueService.rebuild(TEST_DATE).get(0).queueId();
        确认队列(queueId);
        DeliveryTaskLeaseView lease = deliveryTaskService.lease(new DeliveryTaskLeaseRequest("chrome-test"));

        DeliveryTaskProgressView progress = deliveryTaskService.result(lease.taskItemId(),
                new DeliveryTaskExtensionResultRequest("chrome-test", lease.executionToken(), "BLOCKED", "检测到验证码或访问限制", "已停止，不绕过风控"));
        assertEquals(1, progress.unknown());
        assertEquals("BLOCKED", deliveryTaskService.list(TEST_DATE.toString()).get(0).taskStatus());
        assertEquals("UNKNOWN", deliveryQueueService.list(TEST_DATE, null).get(0).queueStatus());
        assertFalse(deliveryTaskService.lease(new DeliveryTaskLeaseRequest("chrome-test")).available());
    }

    private void 确认队列(long queueId) {
        String token = deliveryQueueService.issueConfirmationToken(List.of(queueId)).token();
        List<QueueItemView> confirmed = deliveryQueueService.confirm(new QueueConfirmRequest(List.of(queueId), true, token));
        assertEquals("APPROVED", confirmed.get(0).queueStatus());
    }

    private void 注册职位(String id) {
        jobRecordService.register(new JobRecordRequest(
                "BOSS", id, "普通科技公司", "软件产品研发", "Java开发", "负责后端接口开发",
                "北京", "20-30K", "https://www.zhipin.com/job_detail/" + id + ".html", TEST_DATE.toString()));
    }
}
