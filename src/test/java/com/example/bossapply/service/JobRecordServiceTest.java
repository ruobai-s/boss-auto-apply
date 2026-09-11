package com.example.bossapply.service;

import com.example.bossapply.dto.JobRecordRequest;
import com.example.bossapply.dto.OutsourcingConditionRequest;
import com.example.bossapply.dto.OutsourcingRuleConfigRequest;
import com.example.bossapply.infrastructure.SqliteDatabaseService;
import com.example.bossapply.model.JobPageView;
import com.example.bossapply.model.JobRecord;
import com.example.bossapply.model.JobRegisterResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 职位记录持久化、重复注册和外包状态测试。
 */
@SpringBootTest
@TestPropertySource(properties = "app.database.path=target/test-data/job-record-test.db")
class JobRecordServiceTest {

    @Autowired
    private JobRecordService jobRecordService;

    @Autowired
    private OutsourcingRuleStoreService ruleStoreService;

    @Autowired
    private SqliteDatabaseService databaseService;

    @BeforeEach
    void 清理测试数据() throws Exception {
        try (Connection connection = databaseService.open();
             PreparedStatement queue = connection.prepareStatement("DELETE FROM delivery_queue");
             PreparedStatement jobs = connection.prepareStatement("DELETE FROM job_record");
             PreparedStatement conditions = connection.prepareStatement("DELETE FROM outsourcing_condition_rule");
             PreparedStatement companies = connection.prepareStatement("DELETE FROM outsourcing_company_exclusion")) {
            queue.executeUpdate();
            jobs.executeUpdate();
            conditions.executeUpdate();
            companies.executeUpdate();
        }
    }

    @Test
    void 相同来源职位编号第二次注册应标记为重复() {
        JobRecordRequest request = new JobRecordRequest(
                "BOSS", "job-001", "示例公司", "产品研发公司", "Java开发", "负责后端接口开发",
                "北京", "20-30K", "https://example.com/job-001", "2026-09-07");

        JobRegisterResult first = jobRecordService.register(request);
        JobRegisterResult second = jobRecordService.register(request);

        assertFalse(first.duplicate());
        assertTrue(second.duplicate());
        assertEquals(1, jobRecordService.list(null).size());
        assertEquals("2026-09-07", second.job().publishedAt());
        assertEquals("NORMAL", second.job().outsourcingLevel());
    }

    @Test
    void 详情采集应只补充非空字段且保留已有三态状态() {
        jobRecordService.register(new JobRecordRequest(
                "BOSS", "merge-001", "原公司", "原公司介绍", "Java开发", "列表摘要",
                "杭州", "20-30K", "https://example.com/old", "昨天", "3-5年", "本科",
                "100-499人", "软件行业", List.of("五险一金"), List.of("Java"), true, false));

        JobRecord merged = jobRecordService.register(new JobRecordRequest(
                "BOSS", "merge-001", "", "详情公司介绍", "", "完整职位描述",
                "", "", "https://example.com/new", "", null, null, null, "互联网",
                List.of(), List.of("Spring Boot"), null, null)).job();

        assertEquals("原公司", merged.companyName());
        assertEquals("详情公司介绍", merged.companyIntroduction());
        assertEquals("完整职位描述", merged.jobDescription());
        assertEquals("https://example.com/new", merged.jobUrl());
        assertEquals("昨天", merged.publishedAt());
        assertEquals("3-5年", merged.experienceRequirement());
        assertEquals("本科", merged.educationRequirement());
        assertEquals("100-499人", merged.companySize());
        assertEquals("互联网", merged.companyIndustry());
        assertEquals(List.of("五险一金"), merged.welfareTags());
        assertEquals(List.of("Spring Boot"), merged.jobTags());
        assertEquals(Boolean.TRUE, merged.urgent());
        assertEquals(Boolean.FALSE, merged.online());
    }

    @Test
    void 不同来源职位编号可以分别保存() {
        jobRecordService.register(new JobRecordRequest("BOSS", "job-001", "甲公司", "Java开发", "北京", null, null));
        jobRecordService.register(new JobRecordRequest("BOSS", "job-002", "乙公司", "Java开发", "上海", null, null));

        assertEquals(2, jobRecordService.list(null).size());
    }

    @Test
    void 职位列表应分页并保留全量统计() {
        for (int index = 0; index < 5; index++) {
            jobRecordService.register(new JobRecordRequest(
                    "BOSS", "page-" + index, "普通公司", "产品研发", "Java开发",
                    "后端开发", "北京", "20-30K", null, null));
        }
        jobRecordService.register(new JobRecordRequest(
                "BOSS", "page-outsourcing", "某外包公司", "IT外包服务", "Java开发",
                "驻场客户项目", "北京", "20-30K", null, null));

        JobPageView page = jobRecordService.page(null, 1, 2);

        assertEquals(2, page.items().size());
        assertEquals(6, page.total());
        assertEquals(1, page.page());
        assertEquals(2, page.size());
        assertEquals(1, page.outsourcingExcluded());
    }

    @Test
    void 明确外包职位应持久化排除状态并禁止标记已投递() {
        JobRegisterResult result = jobRecordService.register(new JobRecordRequest(
                "BOSS", "job-outsourcing", "某人力公司", "提供IT外包和人才派遣服务", "Java开发",
                "驻场客户项目开发", "北京", "20-30K", null, null));

        assertTrue(result.job().outsourcingExcluded());
        assertFalse(result.job().outsourcingManualReview());
        assertEquals("EXCLUDED_OUTSOURCING", result.job().applyStatus());
        assertThrows(IllegalArgumentException.class,
                () -> jobRecordService.markApplied("BOSS", "job-outsourcing"));
    }

    @Test
    void 疑似外包职位应进入待人工确认状态() {
        JobRecord record = jobRecordService.register(new JobRecordRequest(
                "BOSS", "job-suspect", "普通科技公司", "软件产品研发", "Java开发",
                "需要客户现场支持", "上海", "25-35K", null, null)).job();

        assertFalse(record.outsourcingExcluded());
        assertTrue(record.outsourcingManualReview());
        assertEquals("WAIT_CONFIRM", record.applyStatus());
    }

    @Test
    void 保存公司排除名单后应重新分类已有职位并撤销不安全队列项() throws Exception {
        jobRecordService.register(new JobRecordRequest(
                "BOSS", "custom-company", "目标科技", "产品研发", "Java开发",
                "负责后端开发", "北京", "20-30K", null, null));
        jobRecordService.register(new JobRecordRequest(
                "BOSS", "safe-company", "安全科技", "产品研发", "Java开发",
                "负责后端开发", "北京", "20-30K", null, null));
        插入候选队列("custom-company");
        插入候选队列("safe-company");

        ruleStoreService.save(new OutsourcingRuleConfigRequest(List.of("目标科技"), List.of()));
        int reevaluated = jobRecordService.reapplyOutsourcingRules();

        JobRecord excluded = 查找职位("custom-company");
        assertEquals(2, reevaluated);
        assertTrue(excluded.outsourcingExcluded());
        assertEquals("CUSTOM_COMPANY_EXCLUSION", excluded.outsourcingLevel());
        assertEquals("EXCLUDED_OUTSOURCING", excluded.applyStatus());
        assertEquals(List.of("safe-company"), 查询队列职位编号());
    }

    @Test
    void 保存人工复核条件后应把已有职位调整为待确认() {
        jobRecordService.register(new JobRecordRequest(
                "BOSS", "custom-review", "普通科技", "产品研发", "Java开发",
                "工作地点由项目安排", "上海", "20-30K", null, null));
        ruleStoreService.save(new OutsourcingRuleConfigRequest(List.of(), List.of(
                new OutsourcingConditionRequest(
                        "JOB_DESCRIPTION", "CONTAINS", "项目安排", "REVIEW", true)
        )));

        jobRecordService.reapplyOutsourcingRules();

        JobRecord record = 查找职位("custom-review");
        assertFalse(record.outsourcingExcluded());
        assertTrue(record.outsourcingManualReview());
        assertEquals("CUSTOM_CONDITION_REVIEW", record.outsourcingLevel());
        assertEquals("WAIT_CONFIRM", record.applyStatus());
    }

    private JobRecord 查找职位(String sourceJobId) {
        return jobRecordService.list(null).stream()
                .filter(job -> sourceJobId.equals(job.sourceJobId()))
                .findFirst()
                .orElseThrow();
    }

    private void 插入候选队列(String sourceJobId) throws Exception {
        String now = OffsetDateTime.now().toString();
        try (Connection connection = databaseService.open();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO delivery_queue(source, source_job_id, planned_date, quota_city, city_priority, "
                             + "allocation_type, queue_status, queue_rank, created_at, updated_at) "
                             + "VALUES('BOSS', ?, '2026-09-09', '北京', 1, 'PRIMARY', 'QUEUED', 1, ?, ?)")) {
            statement.setString(1, sourceJobId);
            statement.setString(2, now);
            statement.setString(3, now);
            statement.executeUpdate();
        }
    }

    private List<String> 查询队列职位编号() throws Exception {
        try (Connection connection = databaseService.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT source_job_id FROM delivery_queue ORDER BY source_job_id");
             ResultSet resultSet = statement.executeQuery()) {
            java.util.ArrayList<String> result = new java.util.ArrayList<>();
            while (resultSet.next()) {
                result.add(resultSet.getString("source_job_id"));
            }
            return result;
        }
    }
}
