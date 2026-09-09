package com.example.bossapply.service;

import com.example.bossapply.dto.OutsourcingConditionRequest;
import com.example.bossapply.dto.OutsourcingRuleConfigRequest;
import com.example.bossapply.infrastructure.SqliteDatabaseService;
import com.example.bossapply.model.OutsourcingConditionRule;
import com.example.bossapply.model.OutsourcingRuleConfig;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 外包公司名单和自定义条件的本地 SQLite 存储服务。
 */
@Service
public class OutsourcingRuleStoreService {

    private final SqliteDatabaseService databaseService;

    public OutsourcingRuleStoreService(SqliteDatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    /**
     * 读取当前规则配置，保持用户保存时的顺序。
     */
    public synchronized OutsourcingRuleConfig get() {
        try (Connection connection = databaseService.open()) {
            return read(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("读取外包规则失败", exception);
        }
    }

    /**
     * 批量替换用户配置，事务失败时保留原规则。
     */
    public synchronized OutsourcingRuleConfig save(OutsourcingRuleConfigRequest request) {
        List<String> companies = normalizeCompanies(request.excludedCompanies());
        List<OutsourcingConditionRule> conditions = normalizeConditions(request.conditions());
        try (Connection connection = databaseService.open()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement deleteCompanies = connection.prepareStatement(
                        "DELETE FROM outsourcing_company_exclusion");
                     PreparedStatement deleteConditions = connection.prepareStatement(
                             "DELETE FROM outsourcing_condition_rule")) {
                    deleteCompanies.executeUpdate();
                    deleteConditions.executeUpdate();
                }
                try (PreparedStatement insertCompany = connection.prepareStatement(
                        "INSERT INTO outsourcing_company_exclusion(company_name, sort_order) VALUES(?, ?)")) {
                    for (int index = 0; index < companies.size(); index++) {
                        insertCompany.setString(1, companies.get(index));
                        insertCompany.setInt(2, index + 1);
                        insertCompany.addBatch();
                    }
                    insertCompany.executeBatch();
                }
                try (PreparedStatement insertCondition = connection.prepareStatement(
                        "INSERT INTO outsourcing_condition_rule(field_name, match_type, keyword, action, enabled, sort_order) "
                                + "VALUES(?, ?, ?, ?, ?, ?)")) {
                    for (int index = 0; index < conditions.size(); index++) {
                        OutsourcingConditionRule condition = conditions.get(index);
                        insertCondition.setString(1, condition.field());
                        insertCondition.setString(2, condition.matchType());
                        insertCondition.setString(3, condition.keyword());
                        insertCondition.setString(4, condition.action());
                        insertCondition.setInt(5, condition.enabled() ? 1 : 0);
                        insertCondition.setInt(6, index + 1);
                        insertCondition.addBatch();
                    }
                    insertCondition.executeBatch();
                }
                connection.commit();
                return new OutsourcingRuleConfig(List.copyOf(companies), List.copyOf(conditions));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("保存外包规则失败", exception);
        }
    }

    private OutsourcingRuleConfig read(Connection connection) throws SQLException {
        List<String> companies = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT company_name FROM outsourcing_company_exclusion ORDER BY sort_order, id");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                companies.add(resultSet.getString("company_name"));
            }
        }
        List<OutsourcingConditionRule> conditions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT field_name, match_type, keyword, action, enabled "
                        + "FROM outsourcing_condition_rule ORDER BY sort_order, id");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                conditions.add(new OutsourcingConditionRule(
                        resultSet.getString("field_name"),
                        resultSet.getString("match_type"),
                        resultSet.getString("keyword"),
                        resultSet.getString("action"),
                        resultSet.getInt("enabled") == 1
                ));
            }
        }
        return new OutsourcingRuleConfig(List.copyOf(companies), List.copyOf(conditions));
    }

    private List<String> normalizeCompanies(List<String> source) {
        Map<String, String> distinct = new LinkedHashMap<>();
        for (String company : source) {
            String value = company == null ? "" : company.trim();
            if (value.isBlank()) {
                continue;
            }
            if (value.length() > 120) {
                throw new IllegalArgumentException("公司名称不能超过120个字符");
            }
            distinct.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
        }
        if (distinct.size() > 100) {
            throw new IllegalArgumentException("最多配置100家公司");
        }
        return new ArrayList<>(distinct.values());
    }

    private List<OutsourcingConditionRule> normalizeConditions(List<OutsourcingConditionRequest> source) {
        List<OutsourcingConditionRule> result = new ArrayList<>();
        for (OutsourcingConditionRequest condition : source) {
            if (condition == null) {
                throw new IllegalArgumentException("自定义条件不能为空");
            }
            String field = upper(condition.field());
            String matchType = upper(condition.matchType());
            String keyword = condition.keyword() == null ? "" : condition.keyword().trim();
            String action = upper(condition.action());
            validateCondition(field, matchType, keyword, action);
            result.add(new OutsourcingConditionRule(field, matchType, keyword, action, condition.enabled()));
        }
        if (result.size() > 100) {
            throw new IllegalArgumentException("最多配置100条自定义条件");
        }
        return result;
    }

    private void validateCondition(String field, String matchType, String keyword, String action) {
        if (!List.of("COMPANY_NAME", "COMPANY_INTRODUCTION", "JOB_NAME", "JOB_DESCRIPTION", "ALL_TEXT").contains(field)) {
            throw new IllegalArgumentException("规则字段不受支持");
        }
        if (!List.of("CONTAINS", "EQUALS").contains(matchType)) {
            throw new IllegalArgumentException("匹配方式不受支持");
        }
        if (keyword.isBlank()) {
            throw new IllegalArgumentException("匹配内容不能为空");
        }
        if (keyword.length() > 120) {
            throw new IllegalArgumentException("单条匹配内容不能超过120个字符");
        }
        if (!List.of("EXCLUDE", "REVIEW").contains(action)) {
            throw new IllegalArgumentException("处理动作不受支持");
        }
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
