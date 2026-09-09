package com.example.bossapply.service;

import com.example.bossapply.model.JobSnapshot;
import com.example.bossapply.model.OutsourcingConditionRule;
import com.example.bossapply.model.OutsourcingDecision;
import com.example.bossapply.model.OutsourcingRuleConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 外包内置规则、自定义公司名单和自定义条件测试。
 */
class OutsourcingFilterServiceTest {

    private final OutsourcingRuleStoreService ruleStoreService = mock(OutsourcingRuleStoreService.class);
    private final OutsourcingFilterService service = new OutsourcingFilterService(ruleStoreService);

    @BeforeEach
    void 使用空自定义规则() {
        when(ruleStoreService.get()).thenReturn(new OutsourcingRuleConfig(List.of(), List.of()));
    }

    @Test
    void 明确外包公司应自动排除() {
        OutsourcingDecision result = service.decide(new JobSnapshot(
                "示例科技",
                "提供IT外包和人才派遣服务",
                "Java开发",
                "负责客户项目开发"
        ));

        assertTrue(result.excluded());
        assertFalse(result.manualReview());
    }

    @Test
    void 疑似外包岗位应进入人工复核() {
        OutsourcingDecision result = service.decide(new JobSnapshot(
                "普通科技公司",
                "软件产品研发",
                "Java开发",
                "需要客户现场支持"
        ));

        assertFalse(result.excluded());
        assertTrue(result.manualReview());
    }

    @Test
    void 自定义公司名单应忽略大小写并按完整名称排除() {
        when(ruleStoreService.get()).thenReturn(new OutsourcingRuleConfig(
                List.of("Acme Technology"), List.of()));

        OutsourcingDecision result = service.decide(new JobSnapshot(
                "  acme technology  ", "产品研发", "Java开发", "后端开发"));

        assertTrue(result.excluded());
        assertEquals("CUSTOM_COMPANY_EXCLUSION", result.level());
        assertEquals("Acme Technology", result.matchedKeyword());
    }

    @Test
    void 全文包含条件可以自动排除() {
        when(ruleStoreService.get()).thenReturn(new OutsourcingRuleConfig(List.of(), List.of(
                new OutsourcingConditionRule("ALL_TEXT", "CONTAINS", "驻场开发", "EXCLUDE", true)
        )));

        OutsourcingDecision result = service.decide(new JobSnapshot(
                "普通科技公司", "产品研发", "Java工程师", "需要参与驻场开发项目"));

        assertTrue(result.excluded());
        assertEquals("CUSTOM_CONDITION_EXCLUSION", result.level());
    }

    @Test
    void 职位描述条件可以进入人工复核() {
        when(ruleStoreService.get()).thenReturn(new OutsourcingRuleConfig(List.of(), List.of(
                new OutsourcingConditionRule("JOB_DESCRIPTION", "CONTAINS", "客户安排", "REVIEW", true)
        )));

        OutsourcingDecision result = service.decide(new JobSnapshot(
                "普通科技公司", "产品研发", "Java工程师", "工作地点根据客户安排"));

        assertFalse(result.excluded());
        assertTrue(result.manualReview());
        assertEquals("CUSTOM_CONDITION_REVIEW", result.level());
    }

    @Test
    void 禁用的自定义条件不应生效() {
        when(ruleStoreService.get()).thenReturn(new OutsourcingRuleConfig(List.of(), List.of(
                new OutsourcingConditionRule("JOB_NAME", "CONTAINS", "Java", "EXCLUDE", false)
        )));

        OutsourcingDecision result = service.decide(new JobSnapshot(
                "普通科技公司", "产品研发", "Java工程师", "负责后端开发"));

        assertFalse(result.excluded());
        assertFalse(result.manualReview());
        assertEquals("NORMAL", result.level());
    }

    @Test
    void 多条条件应按保存顺序执行第一条命中规则() {
        when(ruleStoreService.get()).thenReturn(new OutsourcingRuleConfig(List.of(), List.of(
                new OutsourcingConditionRule("JOB_NAME", "CONTAINS", "Java", "REVIEW", true),
                new OutsourcingConditionRule("ALL_TEXT", "CONTAINS", "Java", "EXCLUDE", true)
        )));

        OutsourcingDecision result = service.decide(new JobSnapshot(
                "普通科技公司", "产品研发", "Java工程师", "负责后端开发"));

        assertFalse(result.excluded());
        assertTrue(result.manualReview());
        assertEquals("CUSTOM_CONDITION_REVIEW", result.level());
    }
}
