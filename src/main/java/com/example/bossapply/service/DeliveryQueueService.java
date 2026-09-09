package com.example.bossapply.service;

import com.example.bossapply.dto.ApplyRequest;
import com.example.bossapply.dto.QueueConfirmRequest;
import com.example.bossapply.infrastructure.SqliteDatabaseService;
import com.example.bossapply.model.ApplicationResult;
import com.example.bossapply.model.BrowserStatus;
import com.example.bossapply.model.EmbeddedBrowserStatus;
import com.example.bossapply.model.CityPreference;
import com.example.bossapply.model.CityQuota;
import com.example.bossapply.model.JobRecord;
import com.example.bossapply.model.QueueItemView;
import com.example.bossapply.model.ConfirmationTokenView;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 候选岗位队列服务，按城市额度生成待确认列表，并支持安全的单条投递准备。
 */
@Service
public class DeliveryQueueService {

    private final SqliteDatabaseService databaseService;
    private final PolicyStoreService policyStoreService;
    private final CityQuotaService cityQuotaService;
    private final JobRecordService jobRecordService;
    private final BrowserConnectionService browserConnectionService;
    private final EmbeddedBrowserService embeddedBrowserService;
    private final OperationConfirmationService operationConfirmationService;

    public DeliveryQueueService(SqliteDatabaseService databaseService,
                                PolicyStoreService policyStoreService,
                                CityQuotaService cityQuotaService,
                                JobRecordService jobRecordService,
                                BrowserConnectionService browserConnectionService,
                                EmbeddedBrowserService embeddedBrowserService,
                                OperationConfirmationService operationConfirmationService) {
        this.databaseService = databaseService;
        this.policyStoreService = policyStoreService;
        this.cityQuotaService = cityQuotaService;
        this.jobRecordService = jobRecordService;
        this.browserConnectionService = browserConnectionService;
        this.embeddedBrowserService = embeddedBrowserService;
        this.operationConfirmationService = operationConfirmationService;
    }

    /**
     * 按城市额度建立指定日期的候选队列，城市不足时使用允许调剂的剩余候选岗位补量。
     */
    public synchronized List<QueueItemView> rebuild(LocalDate plannedDate) {
        var policy = policyStoreService.get();
        List<CityQuota> quotas = cityQuotaService.calculate(policy);
        Map<String, CityPreference> preferences = new HashMap<>();
        policy.cities().forEach(city -> preferences.put(city.city().trim(), city));
        List<JobRecord> candidates = jobRecordService.list("DISCOVERED").stream()
                .filter(job -> matchPolicyCity(job.city(), preferences.keySet()) != null)
                .sorted(Comparator.comparing(JobRecord::lastSeenAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        Map<String, List<JobRecord>> byCity = new HashMap<>();
        candidates.forEach(job -> {
            String policyCity = matchPolicyCity(job.city(), preferences.keySet());
            if (policyCity != null) {
                byCity.computeIfAbsent(policyCity, ignored -> new ArrayList<>()).add(job);
            }
        });

        List<QueueCandidate> selected = new ArrayList<>();
        Set<Long> selectedIds = new HashSet<>();
        int transferSlots = 0;
        for (CityQuota quota : quotas) {
            List<JobRecord> cityJobs = byCity.getOrDefault(quota.city(), List.of());
            int primaryCount = Math.min(quota.plannedCount(), cityJobs.size());
            for (int index = 0; index < primaryCount; index++) {
                JobRecord job = cityJobs.get(index);
                selected.add(new QueueCandidate(job, quota.city(), quota.priority(), "PRIMARY"));
                selectedIds.add(job.id());
            }
            CityPreference preference = preferences.get(quota.city());
            if (preference != null && preference.allowQuotaTransfer()) {
                transferSlots += quota.plannedCount() - primaryCount;
            }
        }

        if (transferSlots > 0) {
            List<CityQuota> priorityOrder = quotas.stream()
                    .sorted(Comparator.comparingInt(CityQuota::priority))
                    .toList();
            List<JobRecord> transferCandidates = candidates.stream()
                    .filter(job -> !selectedIds.contains(job.id()))
                    .toList();
            int transferIndex = 0;
            for (CityQuota quota : priorityOrder) {
                CityPreference preference = preferences.get(quota.city());
                if (preference == null || !preference.allowQuotaTransfer()) {
                    continue;
                }
                int cityJobs = byCity.getOrDefault(quota.city(), List.of()).size();
                int shortage = Math.max(0, quota.plannedCount() - Math.min(quota.plannedCount(), cityJobs));
                for (int index = 0; index < shortage && transferIndex < transferCandidates.size(); index++) {
                    JobRecord job = transferCandidates.get(transferIndex++);
                    selected.add(new QueueCandidate(job, quota.city(), quota.priority(), "TRANSFER"));
                    selectedIds.add(job.id());
                }
            }
        }

        persistQueue(plannedDate, selected);
        return list(plannedDate, null);
    }

    /**
     * 查询指定日期的候选队列，可按队列状态过滤。
     */
    public List<QueueItemView> list(LocalDate plannedDate, String status) {
        String sql = status == null || status.isBlank()
                ? "SELECT id, source, source_job_id, planned_date, quota_city, city_priority, allocation_type, queue_status, queue_rank, created_at FROM delivery_queue WHERE planned_date = ? ORDER BY queue_rank"
                : "SELECT id, source, source_job_id, planned_date, quota_city, city_priority, allocation_type, queue_status, queue_rank, created_at FROM delivery_queue WHERE planned_date = ? AND queue_status = ? ORDER BY queue_rank";
        try (Connection connection = databaseService.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, plannedDate.toString());
            if (status != null && !status.isBlank()) {
                statement.setString(2, status);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<QueueItemView> items = new ArrayList<>();
                while (resultSet.next()) {
                    String source = resultSet.getString("source");
                    String sourceJobId = resultSet.getString("source_job_id");
                    items.add(new QueueItemView(
                            resultSet.getLong("id"),
                            jobRecordService.get(source, sourceJobId),
                            resultSet.getString("planned_date"),
                            resultSet.getString("quota_city"),
                            resultSet.getInt("city_priority"),
                            resultSet.getString("allocation_type"),
                            resultSet.getString("queue_status"),
                            resultSet.getInt("queue_rank"),
                            resultSet.getString("created_at")
                    ));
                }
                return items;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取候选岗位队列失败", exception);
        }
    }

    /**
     * 为仍处于待确认状态的候选岗位生成一次性令牌，不改变队列状态。
     */
    public ConfirmationTokenView issueConfirmationToken(List<Long> queueIds) {
        if (queueIds == null || queueIds.isEmpty()) {
            throw new IllegalArgumentException("至少选择一个候选岗位");
        }
        try (Connection connection = databaseService.open()) {
            ensureQueued(connection, queueIds);
        } catch (SQLException exception) {
            throw new IllegalStateException("检查候选岗位失败", exception);
        }
        return operationConfirmationService.issueQueueConfirmation(queueIds);
    }

    /**
     * 将待确认候选岗位批量改为已批准，不会触发浏览器投递。
     */
    public List<QueueItemView> confirm(QueueConfirmRequest request) {
        if (request == null || !request.confirm()) {
            throw new IllegalArgumentException("必须明确确认后才能批准候选岗位");
        }
        if (request.queueIds() == null || request.queueIds().isEmpty()) {
            throw new IllegalArgumentException("至少选择一个候选岗位");
        }
        String now = OffsetDateTime.now().toString();
        try (Connection connection = databaseService.open()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (Long queueId : request.queueIds()) {
                    if (queueId == null || queueId <= 0) {
                        throw new IllegalArgumentException("候选岗位编号不合法");
                    }
                }
                ensureQueued(connection, request.queueIds());
                operationConfirmationService.consumeQueueConfirmation(
                        request.confirmationToken(), request.queueIds());
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE delivery_queue SET queue_status = 'APPROVED', updated_at = ? WHERE id = ? AND queue_status = 'QUEUED'")) {
                    for (Long queueId : request.queueIds()) {
                        statement.setString(1, now);
                        statement.setLong(2, queueId);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
                connection.setAutoCommit(originalAutoCommit);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                connection.setAutoCommit(originalAutoCommit);
                if (exception instanceof IllegalArgumentException illegalArgumentException) {
                    throw illegalArgumentException;
                }
                throw exception;
            }
            return listByIds(request.queueIds());
        } catch (SQLException exception) {
            throw new IllegalStateException("确认候选岗位失败", exception);
        }
    }

    /**
     * 为已经人工确认的单条岗位生成一次性投递准备令牌。
     */
    public ConfirmationTokenView issueSingleApplyToken(long queueId) {
        QueueRow row = findQueue(queueId);
        if (!"APPROVED".equals(row.queueStatus())) {
            throw new IllegalArgumentException("候选岗位尚未通过人工确认");
        }
        return operationConfirmationService.issueSingleApply(queueId);
    }

    /**
     * 执行单条投递前置检查。当前版本只生成人工操作指引，不代替用户点击外部网站。
     */
    public ApplicationResult prepareSingleApply(long queueId, ApplyRequest request) {
        if (request == null || !request.confirm()) {
            throw new IllegalArgumentException("单条投递必须明确确认");
        }
        QueueRow row = findQueue(queueId);
        if (!"APPROVED".equals(row.queueStatus())) {
            throw new IllegalArgumentException("候选岗位尚未通过人工确认");
        }
        operationConfirmationService.consumeSingleApply(request.confirmationToken(), queueId);
        // 优先使用本系统启动的独立 Edge 状态，避免已登录的内置浏览器被旧 CDP 检测误判为未连接。
        EmbeddedBrowserStatus embeddedStatus = embeddedBrowserService.status();
        boolean embeddedReady = embeddedStatus.running() && embeddedStatus.loginValid();
        BrowserStatus browserStatus = embeddedReady ? null : browserConnectionService.probe();
        boolean cdpReady = browserStatus != null && browserStatus.browserConnected() && browserStatus.loginValid();
        if (!embeddedReady && !cdpReady) {
            String message = embeddedStatus.running()
                    ? "内置 Edge 当前状态为 " + embeddedStatus.state() + "，请先完成登录或安全验证，已阻止投递"
                    : "浏览器未连接或 BOSS 登录状态无法确认，已阻止投递";
            return new ApplicationResult(queueId, "BROWSER_NOT_READY", message, row.jobUrl());
        }
        boolean openedInEmbeddedBrowser = embeddedReady && embeddedBrowserService.openJobDetail(row.jobUrl());
        String message = openedInEmbeddedBrowser
                ? "已在已连接的 Edge 当前页面打开职位详情，请人工确认后点击投递；系统不会代替你点击"
                : "已通过安全检查，请在 BOSS 页面打开职位详情并手动完成这一条投递，再回到本地页面记录结果";
        return new ApplicationResult(queueId, "MANUAL_ACTION_REQUIRED", message, row.jobUrl());
    }

    private void persistQueue(LocalDate plannedDate, List<QueueCandidate> selected) {
        String now = OffsetDateTime.now().toString();
        try (Connection connection = databaseService.open()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM delivery_queue WHERE planned_date = ? AND queue_status IN ('QUEUED', 'REJECTED')")) {
                delete.setString(1, plannedDate.toString());
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO delivery_queue(source, source_job_id, planned_date, quota_city, city_priority, allocation_type, queue_status, queue_rank, created_at, updated_at) "
                            + "VALUES(?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?) "
                            + "ON CONFLICT(planned_date, source, source_job_id) DO UPDATE SET quota_city=excluded.quota_city, city_priority=excluded.city_priority, "
                            + "allocation_type=excluded.allocation_type, queue_rank=excluded.queue_rank, updated_at=excluded.updated_at "
                            + "WHERE delivery_queue.queue_status = 'QUEUED'")) {
                int rank = 1;
                for (QueueCandidate candidate : selected) {
                    insert.setString(1, candidate.job().source());
                    insert.setString(2, candidate.job().sourceJobId());
                    insert.setString(3, plannedDate.toString());
                    insert.setString(4, candidate.quotaCity());
                    insert.setInt(5, candidate.cityPriority());
                    insert.setString(6, candidate.allocationType());
                    insert.setInt(7, rank++);
                    insert.setString(8, now);
                    insert.setString(9, now);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
            connection.setAutoCommit(originalAutoCommit);
        } catch (SQLException exception) {
            throw new IllegalStateException("生成候选岗位队列失败", exception);
        }
    }

    /**
     * 确认前校验所有编号都存在且仍处于待确认状态，避免重复确认或误操作已投递记录。
     */
    private void ensureQueued(Connection connection, List<Long> ids) throws SQLException {
        List<Long> distinctIds = ids.stream().distinct().toList();
        String placeholders = distinctIds.stream().map(ignored -> "?").reduce((left, right) -> left + "," + right).orElseThrow();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM delivery_queue WHERE id IN (" + placeholders + ") AND queue_status = 'QUEUED'")) {
            for (int index = 0; index < distinctIds.size(); index++) {
                statement.setLong(index + 1, distinctIds.get(index));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                if (resultSet.getInt(1) != distinctIds.size()) {
                    throw new IllegalArgumentException("所选候选岗位中存在不存在或已处理的队列项，请刷新后重试");
                }
            }
        }
    }

    private List<QueueItemView> listByIds(List<Long> ids) throws SQLException {
        String placeholders = ids.stream().map(ignored -> "?").reduce((left, right) -> left + "," + right).orElseThrow();
        try (Connection connection = databaseService.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, source, source_job_id, planned_date, quota_city, city_priority, allocation_type, queue_status, queue_rank, created_at FROM delivery_queue WHERE id IN (" + placeholders + ") ORDER BY queue_rank")) {
            for (int index = 0; index < ids.size(); index++) {
                statement.setLong(index + 1, ids.get(index));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<QueueItemView> items = new ArrayList<>();
                while (resultSet.next()) {
                    items.add(new QueueItemView(
                            resultSet.getLong("id"),
                            jobRecordService.get(resultSet.getString("source"), resultSet.getString("source_job_id")),
                            resultSet.getString("planned_date"),
                            resultSet.getString("quota_city"),
                            resultSet.getInt("city_priority"),
                            resultSet.getString("allocation_type"),
                            resultSet.getString("queue_status"),
                            resultSet.getInt("queue_rank"),
                            resultSet.getString("created_at")
                    ));
                }
                return items;
            }
        }
    }

    private QueueRow findQueue(long queueId) {
        try (Connection connection = databaseService.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT q.queue_status, j.job_url FROM delivery_queue q JOIN job_record j ON j.source = q.source AND j.source_job_id = q.source_job_id WHERE q.id = ?")) {
            statement.setLong(1, queueId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("候选岗位不存在：" + queueId);
                }
                return new QueueRow(resultSet.getString("queue_status"), resultSet.getString("job_url"));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取候选岗位失败", exception);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 将 BOSS 职位卡片中的“城市·区域”或“城市市”归一到策略城市，避免区域后缀导致岗位漏入队列。
     */
    private String matchPolicyCity(String rawCity, Set<String> policyCities) {
        String normalized = safe(rawCity);
        if (normalized.isBlank()) {
            return null;
        }
        return policyCities.stream()
                .filter(policyCity -> isPolicyCityMatch(normalized, policyCity))
                .sorted(Comparator.comparingInt(String::length).reversed())
                .findFirst()
                .orElse(null);
    }

    private boolean isPolicyCityMatch(String rawCity, String policyCity) {
        if (rawCity.equals(policyCity)) {
            return true;
        }
        if (!rawCity.startsWith(policyCity) || rawCity.length() <= policyCity.length()) {
            return false;
        }
        char suffix = rawCity.charAt(policyCity.length());
        return suffix == '市' || suffix == '·' || suffix == ' ' || suffix == '-' || suffix == '/' || suffix == '、';
    }

    private record QueueCandidate(JobRecord job, String quotaCity, int cityPriority, String allocationType) {
    }

    private record QueueRow(String queueStatus, String jobUrl) {
    }
}



