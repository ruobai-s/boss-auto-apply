package com.example.bossapply.service;

import com.example.bossapply.dto.DeliveryPolicyRequest;
import com.example.bossapply.dto.JobRecordRequest;
import com.example.bossapply.dto.QueueConfirmRequest;
import com.example.bossapply.infrastructure.SqliteDatabaseService;
import com.example.bossapply.model.CityPreference;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 候选队列生成、补量和人工确认测试。
 */
@SpringBootTest
@TestPropertySource(properties = "app.database.path=target/test-data/delivery-queue-test.db")
class DeliveryQueueServiceTest {

    private static final LocalDate TEST_DATE = LocalDate.of(2026, 9, 7);

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
            try (PreparedStatement queue = connection.prepareStatement("DELETE FROM delivery_queue")) {
                queue.executeUpdate();
            }
            try (PreparedStatement jobs = connection.prepareStatement("DELETE FROM job_record")) {
                jobs.executeUpdate();
            }
            try (PreparedStatement cities = connection.prepareStatement("DELETE FROM city_preference")) {
                cities.executeUpdate();
            }
            try (PreparedStatement settings = connection.prepareStatement("DELETE FROM app_setting")) {
                settings.executeUpdate();
            }
        }
        policyStoreService.save(new DeliveryPolicyRequest(10, List.of(
                new CityPreference("北京", 1, 50, true, true),
                new CityPreference("上海", 2, 50, true, true)
        )));
    }

    @Test
    void 城市岗位不足时应按优先级使用其他城市候选岗位补量() {
        for (int index = 1; index <= 6; index++) {
            注册普通职位("beijing-" + index, "北京");
        }
        注册明确外包职位("outsourcing", "北京", "某人力公司", "提供IT外包服务", "驻场客户项目");
        注册疑似外包职位("suspect", "上海", "普通科技公司", "软件产品研发", "需要客户现场支持");

        List<QueueItemView> queue = deliveryQueueService.rebuild(TEST_DATE);

        assertEquals(6, queue.size());
        assertEquals(5, queue.stream().filter(item -> "PRIMARY".equals(item.allocationType())).count());
        assertEquals(1, queue.stream().filter(item -> "TRANSFER".equals(item.allocationType())).count());
        assertEquals("上海", queue.stream().filter(item -> "TRANSFER".equals(item.allocationType()))
                .findFirst().orElseThrow().quotaCity());
        assertEquals(0, queue.stream().filter(item -> "outsourcing".equals(item.job().sourceJobId())).count());
        assertEquals(0, queue.stream().filter(item -> "suspect".equals(item.job().sourceJobId())).count());
    }


    @Test
    void BOSS城市带区域后缀时仍应匹配优先城市() {
        注册普通职位("beijing-district", "北京·朝阳区");

        List<QueueItemView> queue = deliveryQueueService.rebuild(TEST_DATE);

        assertEquals(1, queue.size());
        assertEquals("北京", queue.get(0).quotaCity());
    }

    @Test
    void 未经队列人工确认不能直接记录已投递() {
        注册普通职位("direct-apply", "北京");

        assertThrows(IllegalArgumentException.class,
                () -> jobRecordService.markApplied("BOSS", "direct-apply"));
    }

    @Test
    void 记录人工投递结果后应锁定对应队列避免重复准备() {
        注册普通职位("applied-once", "北京");
        List<QueueItemView> queue = deliveryQueueService.rebuild(TEST_DATE);
        long queueId = queue.get(0).queueId();
        确认队列(queueId);

        assertEquals("APPLIED", jobRecordService.markApplied("BOSS", "applied-once").applyStatus());
        assertEquals("APPLIED", deliveryQueueService.list(TEST_DATE, null).get(0).queueStatus());
    }

    @Test
    void 人工确认只能处理仍处于排队状态的项目() {
        注册普通职位("beijing-1", "北京");
        List<QueueItemView> queue = deliveryQueueService.rebuild(TEST_DATE);
        long queueId = queue.get(0).queueId();

        List<QueueItemView> confirmed = 确认队列(queueId);

        assertEquals("APPROVED", confirmed.get(0).queueStatus());
        assertThrows(IllegalArgumentException.class,
                () -> deliveryQueueService.confirm(new QueueConfirmRequest(List.of(queueId), true, "used-token")));
        assertThrows(IllegalArgumentException.class,
                () -> deliveryQueueService.confirm(new QueueConfirmRequest(List.of(queueId), false, "unused-token")));
    }

    private List<QueueItemView> 确认队列(long queueId) {
        String token = deliveryQueueService.issueConfirmationToken(List.of(queueId)).token();
        return deliveryQueueService.confirm(new QueueConfirmRequest(List.of(queueId), true, token));
    }

    private void 注册普通职位(String id, String city) {
        jobRecordService.register(new JobRecordRequest(
                "BOSS", id, "普通科技公司", "软件产品研发", "Java开发", "负责后端接口开发",
                city, "20-30K", "https://www.zhipin.com/job_detail/" + id + ".html", "2026-09-07"));
    }

    private void 注册疑似外包职位(String id, String city, String company, String intro, String description) {
        jobRecordService.register(new JobRecordRequest(
                "BOSS", id, company, intro, "Java开发", description, city, "20-30K", null, "2026-09-07"));
    }

    private void 注册明确外包职位(String id, String city, String company, String intro, String description) {
        jobRecordService.register(new JobRecordRequest(
                "BOSS", id, company, intro, "Java开发", description, city, "20-30K", null, "2026-09-07"));
    }
}

