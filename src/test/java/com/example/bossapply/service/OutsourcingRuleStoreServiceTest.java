package com.example.bossapply.service;

import com.example.bossapply.dto.OutsourcingConditionRequest;
import com.example.bossapply.dto.OutsourcingRuleConfigRequest;
import com.example.bossapply.infrastructure.SqliteDatabaseService;
import com.example.bossapply.model.OutsourcingConditionRule;
import com.example.bossapply.model.OutsourcingRuleConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多公司排除名单和多条自定义条件的 SQLite 持久化测试。
 */
@SpringBootTest
@TestPropertySource(properties = "app.database.path=target/test-data/outsourcing-rule-test.db")
class OutsourcingRuleStoreServiceTest {

    @Autowired
    private OutsourcingRuleStoreService ruleStoreService;

    @Autowired
    private SqliteDatabaseService databaseService;

    @BeforeEach
    void 清理测试规则() throws Exception {
        try (Connection connection = databaseService.open();
             PreparedStatement conditions = connection.prepareStatement("DELETE FROM outsourcing_condition_rule");
             PreparedStatement companies = connection.prepareStatement("DELETE FROM outsourcing_company_exclusion")) {
            conditions.executeUpdate();
            companies.executeUpdate();
        }
    }

    @Test
    void 多家公司应去空行去重并在重新创建服务后保持顺序() {
        OutsourcingRuleConfig saved = ruleStoreService.save(new OutsourcingRuleConfigRequest(
                List.of("  甲公司  ", "Acme", "", "acme", "乙公司"),
                List.of(
                        new OutsourcingConditionRequest("job_description", "contains", "驻场", "review", true),
                        new OutsourcingConditionRequest("ALL_TEXT", "EQUALS", "指定内容", "EXCLUDE", false)
                )
        ));

        OutsourcingRuleConfig reloaded = new OutsourcingRuleStoreService(databaseService).get();

        assertEquals(List.of("甲公司", "Acme", "乙公司"), saved.excludedCompanies());
        assertEquals(saved, reloaded);
        assertEquals("JOB_DESCRIPTION", reloaded.conditions().get(0).field());
        assertEquals("CONTAINS", reloaded.conditions().get(0).matchType());
        assertEquals("REVIEW", reloaded.conditions().get(0).action());
        assertTrue(reloaded.conditions().get(0).enabled());
        assertFalse(reloaded.conditions().get(1).enabled());
    }

    @Test
    void 条件保存顺序应作为规则优先级保留() {
        ruleStoreService.save(new OutsourcingRuleConfigRequest(List.of(), List.of(
                new OutsourcingConditionRequest("JOB_NAME", "CONTAINS", "第一条", "REVIEW", true),
                new OutsourcingConditionRequest("ALL_TEXT", "CONTAINS", "第二条", "EXCLUDE", true)
        )));

        List<OutsourcingConditionRule> conditions = ruleStoreService.get().conditions();

        assertEquals(List.of("第一条", "第二条"),
                conditions.stream().map(OutsourcingConditionRule::keyword).toList());
    }

    @Test
    void 非法字段和空关键词应被拒绝且不覆盖原规则() {
        OutsourcingRuleConfig original = ruleStoreService.save(new OutsourcingRuleConfigRequest(
                List.of("保留公司"), List.of()));

        assertThrows(IllegalArgumentException.class, () -> ruleStoreService.save(
                new OutsourcingRuleConfigRequest(List.of(), List.of(
                        new OutsourcingConditionRequest("UNKNOWN", "CONTAINS", "外包", "EXCLUDE", true)))));
        assertThrows(IllegalArgumentException.class, () -> ruleStoreService.save(
                new OutsourcingRuleConfigRequest(List.of(), List.of(
                        new OutsourcingConditionRequest("ALL_TEXT", "CONTAINS", " ", "EXCLUDE", true)))));
        assertEquals(original, ruleStoreService.get());
    }

    @Test
    void 超过公司和条件上限应被拒绝() {
        List<String> companies = IntStream.rangeClosed(1, 101)
                .mapToObj(index -> "公司" + index).toList();
        List<OutsourcingConditionRequest> conditions = IntStream.rangeClosed(1, 101)
                .mapToObj(index -> new OutsourcingConditionRequest(
                        "ALL_TEXT", "CONTAINS", "条件" + index, "EXCLUDE", true))
                .toList();

        assertThrows(IllegalArgumentException.class, () -> ruleStoreService.save(
                new OutsourcingRuleConfigRequest(companies, List.of())));
        assertThrows(IllegalArgumentException.class, () -> ruleStoreService.save(
                new OutsourcingRuleConfigRequest(List.of(), conditions)));
    }
}
