package com.example.bossapply.service;

import com.example.bossapply.dto.JobRecordRequest;
import com.example.bossapply.infrastructure.SqliteDatabaseService;
import com.example.bossapply.model.JobRecord;
import com.example.bossapply.model.JobPageView;
import com.example.bossapply.model.JobRegisterResult;
import com.example.bossapply.model.JobSnapshot;
import com.example.bossapply.model.OutsourcingDecision;
import com.example.bossapply.model.OutsourcingRuleConfig;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 职位本地记录服务，使用来源和来源职位编号实现幂等去重。
 */
@Service
public class JobRecordService {

    private final SqliteDatabaseService databaseService;
    private final OutsourcingFilterService outsourcingFilterService;

    public JobRecordService(SqliteDatabaseService databaseService,
                            OutsourcingFilterService outsourcingFilterService) {
        this.databaseService = databaseService;
        this.outsourcingFilterService = outsourcingFilterService;
    }

    /**
     * 注册或刷新职位快照；重复职位不会新增记录，并同步保存外包判断结果。
     */
    public JobRegisterResult register(JobRecordRequest request) {
        String now = OffsetDateTime.now().toString();
        OutsourcingDecision decision = outsourcingFilterService.decide(new JobSnapshot(
                request.companyName(), request.companyIntroduction(), request.jobName(), request.jobDescription()));
        String initialStatus = statusFor(decision);
        try (Connection connection = databaseService.open()) {
            boolean duplicate = exists(connection, request.source(), request.sourceJobId());
            String sql = "INSERT INTO job_record(source, source_job_id, company_name, company_introduction, job_name, "
                    + "job_description, city, salary, job_url, published_at, experience_requirement, education_requirement, "
                    + "company_size, company_industry, welfare_tags, job_tags, urgent, online, apply_status, outsourcing_excluded, "
                    + "outsourcing_manual_review, outsourcing_level, outsourcing_reason, outsourcing_keyword, first_seen_at, last_seen_at) "
                    + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT(source, source_job_id) DO UPDATE SET "
                    + "company_name=COALESCE(NULLIF(excluded.company_name, ''), job_record.company_name), "
                    + "company_introduction=COALESCE(NULLIF(excluded.company_introduction, ''), job_record.company_introduction), "
                    + "job_name=COALESCE(NULLIF(excluded.job_name, ''), job_record.job_name), "
                    + "job_description=COALESCE(NULLIF(excluded.job_description, ''), job_record.job_description), "
                    + "city=COALESCE(NULLIF(excluded.city, ''), job_record.city), "
                    + "salary=COALESCE(NULLIF(excluded.salary, ''), job_record.salary), "
                    + "job_url=COALESCE(NULLIF(excluded.job_url, ''), job_record.job_url), "
                    + "published_at=COALESCE(NULLIF(excluded.published_at, ''), job_record.published_at), "
                    + "experience_requirement=COALESCE(NULLIF(excluded.experience_requirement, ''), job_record.experience_requirement), "
                    + "education_requirement=COALESCE(NULLIF(excluded.education_requirement, ''), job_record.education_requirement), "
                    + "company_size=COALESCE(NULLIF(excluded.company_size, ''), job_record.company_size), "
                    + "company_industry=COALESCE(NULLIF(excluded.company_industry, ''), job_record.company_industry), "
                    + "welfare_tags=COALESCE(NULLIF(excluded.welfare_tags, ''), job_record.welfare_tags), "
                    + "job_tags=COALESCE(NULLIF(excluded.job_tags, ''), job_record.job_tags), "
                    + "urgent=COALESCE(excluded.urgent, job_record.urgent), online=COALESCE(excluded.online, job_record.online), "
                    + "apply_status=CASE WHEN job_record.apply_status IN ('DISCOVERED', 'EXCLUDED_OUTSOURCING', 'WAIT_CONFIRM') "
                    + "THEN excluded.apply_status ELSE job_record.apply_status END, "
                    + "outsourcing_excluded=excluded.outsourcing_excluded, "
                    + "outsourcing_manual_review=excluded.outsourcing_manual_review, "
                    + "outsourcing_level=excluded.outsourcing_level, outsourcing_reason=excluded.outsourcing_reason, "
                    + "outsourcing_keyword=excluded.outsourcing_keyword, last_seen_at=excluded.last_seen_at";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, request.source().trim());
                statement.setString(2, request.sourceJobId().trim());
                statement.setString(3, request.companyName());
                statement.setString(4, request.companyIntroduction());
                statement.setString(5, request.jobName());
                statement.setString(6, request.jobDescription());
                statement.setString(7, request.city());
                statement.setString(8, request.salary());
                statement.setString(9, request.jobUrl());
                statement.setString(10, request.publishedAt());
                statement.setString(11, request.experienceRequirement());
                statement.setString(12, request.educationRequirement());
                statement.setString(13, request.companySize());
                statement.setString(14, request.companyIndustry());
                statement.setString(15, encodeTags(request.welfareTags()));
                statement.setString(16, encodeTags(request.jobTags()));
                setNullableBoolean(statement, 17, request.urgent());
                setNullableBoolean(statement, 18, request.online());
                statement.setString(19, initialStatus);
                statement.setInt(20, decision.excluded() ? 1 : 0);
                statement.setInt(21, decision.manualReview() ? 1 : 0);
                statement.setString(22, decision.level());
                statement.setString(23, decision.reason());
                statement.setString(24, decision.matchedKeyword());
                statement.setString(25, now);
                statement.setString(26, now);
                statement.executeUpdate();
            }
            return new JobRegisterResult(findOne(connection, request.source(), request.sourceJobId()), duplicate);
        } catch (SQLException exception) {
            throw new IllegalStateException("保存职位记录失败", exception);
        }
    }

    /**
     * 规则保存后重新检查本地职位，并撤销已不安全的待确认或已批准队列项。
     */
    public synchronized int reapplyOutsourcingRules() {
        OutsourcingRuleConfig config = outsourcingFilterService.currentConfig();
        try (Connection connection = databaseService.open()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<RuleReevaluationRow> rows = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, company_name, company_introduction, job_name, job_description, apply_status FROM job_record");
                     ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        rows.add(new RuleReevaluationRow(
                                resultSet.getLong("id"),
                                resultSet.getString("company_name"),
                                resultSet.getString("company_introduction"),
                                resultSet.getString("job_name"),
                                resultSet.getString("job_description"),
                                resultSet.getString("apply_status")
                        ));
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE job_record SET apply_status = ?, outsourcing_excluded = ?, "
                                + "outsourcing_manual_review = ?, outsourcing_level = ?, outsourcing_reason = ?, "
                                + "outsourcing_keyword = ? WHERE id = ?")) {
                    for (RuleReevaluationRow row : rows) {
                        OutsourcingDecision decision = outsourcingFilterService.decide(new JobSnapshot(
                                row.companyName(), row.companyIntroduction(), row.jobName(), row.jobDescription()), config);
                        String status = isRuleManagedStatus(row.applyStatus())
                                ? statusFor(decision) : row.applyStatus();
                        update.setString(1, status);
                        update.setInt(2, decision.excluded() ? 1 : 0);
                        update.setInt(3, decision.manualReview() ? 1 : 0);
                        update.setString(4, decision.level());
                        update.setString(5, decision.reason());
                        update.setString(6, decision.matchedKeyword());
                        update.setLong(7, row.id());
                        update.addBatch();
                    }
                    update.executeBatch();
                }
                // 新规则命中的职位不应继续保留在未投递队列中，用户可在修改规则后重新生成队列。
                try (PreparedStatement deleteUnsafeQueue = connection.prepareStatement(
                        "DELETE FROM delivery_queue WHERE queue_status IN ('QUEUED', 'APPROVED') "
                                + "AND EXISTS (SELECT 1 FROM job_record j WHERE j.source = delivery_queue.source "
                                + "AND j.source_job_id = delivery_queue.source_job_id "
                                + "AND (j.outsourcing_excluded = 1 OR j.outsourcing_manual_review = 1))")) {
                    deleteUnsafeQueue.executeUpdate();
                }
                connection.commit();
                return rows.size();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("重新检查本地职位失败", exception);
        }
    }

    private boolean isRuleManagedStatus(String status) {
        return "DISCOVERED".equals(status)
                || "EXCLUDED_OUTSOURCING".equals(status)
                || "WAIT_CONFIRM".equals(status);
    }
    /**
     * 查询本地职位列表，可按投递状态过滤。
     */
    public List<JobRecord> list(String status) {
        try (Connection connection = databaseService.open();
             PreparedStatement statement = connection.prepareStatement(
                     status == null || status.isBlank()
                             ? "SELECT * FROM job_record ORDER BY last_seen_at DESC"
                             : "SELECT * FROM job_record WHERE apply_status = ? ORDER BY last_seen_at DESC")) {
            if (status != null && !status.isBlank()) {
                statement.setString(1, status);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<JobRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    records.add(map(resultSet));
                }
                return records;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取职位记录失败", exception);
        }
    }

    /**
     * 分页查询职位，同时计算全量状态统计，单页最多返回两百条。
     */
    public JobPageView page(String status, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(200, size));
        boolean filtered = status != null && !status.isBlank();
        String where = filtered ? " WHERE apply_status = ?" : "";
        try (Connection connection = databaseService.open()) {
            long total;
            try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM job_record" + where)) {
                if (filtered) {
                    count.setString(1, status.trim());
                }
                try (ResultSet resultSet = count.executeQuery()) {
                    total = resultSet.next() ? resultSet.getLong(1) : 0L;
                }
            }

            List<JobRecord> items = new ArrayList<>();
            String sql = "SELECT * FROM job_record" + where + " ORDER BY last_seen_at DESC LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int parameter = 1;
                if (filtered) {
                    statement.setString(parameter++, status.trim());
                }
                statement.setInt(parameter++, safeSize);
                statement.setLong(parameter, (long) safePage * safeSize);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        items.add(map(resultSet));
                    }
                }
            }

            long excluded = 0L;
            long manualReview = 0L;
            long applied = 0L;
            try (PreparedStatement statistics = connection.prepareStatement(
                    "SELECT "
                            + "SUM(CASE WHEN outsourcing_excluded = 1 THEN 1 ELSE 0 END), "
                            + "SUM(CASE WHEN outsourcing_manual_review = 1 THEN 1 ELSE 0 END), "
                            + "SUM(CASE WHEN apply_status = 'APPLIED' THEN 1 ELSE 0 END) "
                            + "FROM job_record")) {
                try (ResultSet resultSet = statistics.executeQuery()) {
                    if (resultSet.next()) {
                        excluded = resultSet.getLong(1);
                        manualReview = resultSet.getLong(2);
                        applied = resultSet.getLong(3);
                    }
                }
            }
            return new JobPageView(items, total, safePage, safeSize, excluded, manualReview, applied);
        } catch (SQLException exception) {
            throw new IllegalStateException("分页读取职位记录失败", exception);
        }
    }

    /**
     * 按来源和来源职位编号读取职位，供候选队列拼装职位详情。
     */
    public JobRecord get(String source, String sourceJobId) {
        if (source == null || source.isBlank() || sourceJobId == null || sourceJobId.isBlank()) {
            throw new IllegalArgumentException("职位来源和来源职位编号不能为空");
        }
        try (Connection connection = databaseService.open()) {
            return findOne(connection, source, sourceJobId);
        } catch (SQLException exception) {
            throw new IllegalStateException("读取职位记录失败", exception);
        }
    }

    /**
     * 将职位标记为已投递；明确外包职位禁止进入投递状态。
     */
    public JobRecord markApplied(String source, String sourceJobId) {
        try (Connection connection = databaseService.open()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                JobRecord record = findOne(connection, source, sourceJobId);
                if (record.outsourcingExcluded()) {
                    throw new IllegalArgumentException("明确外包职位不能标记为已投递");
                }
                if ("APPLIED".equals(record.applyStatus())) {
                    connection.commit();
                    return record;
                }
                if (!hasApprovedQueue(connection, source, sourceJobId)) {
                    throw new IllegalArgumentException("职位必须先经过候选队列人工确认，不能直接标记为已投递");
                }
                String now = OffsetDateTime.now().toString();
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE job_record SET apply_status = 'APPLIED', last_seen_at = ? "
                                + "WHERE source = ? AND source_job_id = ? AND apply_status <> 'APPLIED'")) {
                    statement.setString(1, now);
                    statement.setString(2, source.trim());
                    statement.setString(3, sourceJobId.trim());
                    statement.executeUpdate();
                }
                // 同一职位可能出现在多个日期队列中，完成一次人工投递后全部锁定，避免重复投递。
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE delivery_queue SET queue_status = 'APPLIED', updated_at = ? "
                                + "WHERE source = ? AND source_job_id = ? AND queue_status = 'APPROVED'")) {
                    statement.setString(1, now);
                    statement.setString(2, source.trim());
                    statement.setString(3, sourceJobId.trim());
                    statement.executeUpdate();
                }
                connection.commit();
                return findOne(connection, source, sourceJobId);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                if (exception instanceof IllegalArgumentException illegalArgumentException) {
                    throw illegalArgumentException;
                }
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("更新职位投递状态失败", exception);
        }
    }

    /**
     * 只有已经人工确认的候选队列才允许记录已投递结果。
     */
    private boolean hasApprovedQueue(Connection connection, String source, String sourceJobId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM delivery_queue WHERE source = ? AND source_job_id = ? AND queue_status = 'APPROVED' LIMIT 1")) {
            statement.setString(1, source.trim());
            statement.setString(2, sourceJobId.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private String statusFor(OutsourcingDecision decision) {
        if (decision.excluded()) {
            return "EXCLUDED_OUTSOURCING";
        }
        if (decision.manualReview()) {
            return "WAIT_CONFIRM";
        }
        return "DISCOVERED";
    }

    private boolean exists(Connection connection, String source, String sourceJobId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM job_record WHERE source = ? AND source_job_id = ?")) {
            statement.setString(1, source.trim());
            statement.setString(2, sourceJobId.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private JobRecord findOne(Connection connection, String source, String sourceJobId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM job_record WHERE source = ? AND source_job_id = ?")) {
            statement.setString(1, source.trim());
            statement.setString(2, sourceJobId.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("职位记录不存在：" + source + "/" + sourceJobId);
                }
                return map(resultSet);
            }
        }
    }

    /**
     * 标签使用换行分隔保存在本地 SQLite，避免为小型本地项目增加额外表。
     */
    private String encodeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        return tags.stream()
                .map(tag -> tag == null ? "" : tag.replaceAll("\\s+", " ").trim())
                .filter(tag -> !tag.isBlank())
                .distinct()
                .limit(20)
                .reduce((left, right) -> left + "\n" + right)
                .orElse(null);
    }

    /**
     * 将数据库中的标签文本恢复为只读列表。
     */
    private List<String> decodeTags(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("\n"))
                .map(String::trim)
                .filter(tag -> !tag.isBlank())
                .toList();
    }

    /**
     * SQLite 使用 0、1 或 NULL 保存三态布尔字段。
     */
    private void setNullableBoolean(PreparedStatement statement, int index, Boolean value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value ? 1 : 0);
        }
    }

    /**
     * 保留未识别状态，避免把“未采集”误判为否。
     */
    private Boolean nullableBoolean(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value != 0;
    }

    private record RuleReevaluationRow(long id, String companyName, String companyIntroduction,
                                       String jobName, String jobDescription, String applyStatus) {
    }
    private JobRecord map(ResultSet resultSet) throws SQLException {
        return new JobRecord(
                resultSet.getLong("id"),
                resultSet.getString("source"),
                resultSet.getString("source_job_id"),
                resultSet.getString("company_name"),
                resultSet.getString("company_introduction"),
                resultSet.getString("job_name"),
                resultSet.getString("job_description"),
                resultSet.getString("city"),
                resultSet.getString("salary"),
                resultSet.getString("job_url"),
                resultSet.getString("published_at"),
                resultSet.getString("experience_requirement"),
                resultSet.getString("education_requirement"),
                resultSet.getString("company_size"),
                resultSet.getString("company_industry"),
                decodeTags(resultSet.getString("welfare_tags")),
                decodeTags(resultSet.getString("job_tags")),
                nullableBoolean(resultSet, "urgent"),
                nullableBoolean(resultSet, "online"),
                resultSet.getString("apply_status"),
                resultSet.getInt("outsourcing_excluded") == 1,
                resultSet.getInt("outsourcing_manual_review") == 1,
                resultSet.getString("outsourcing_level"),
                resultSet.getString("outsourcing_reason"),
                resultSet.getString("outsourcing_keyword"),
                resultSet.getString("first_seen_at"),
                resultSet.getString("last_seen_at")
        );
    }
}


