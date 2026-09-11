package com.example.bossapply.service;

import com.example.bossapply.dto.DeliveryTaskExtensionResultRequest;
import com.example.bossapply.dto.DeliveryTaskLeaseRequest;
import com.example.bossapply.dto.DeliveryTaskStageRequest;
import com.example.bossapply.infrastructure.SqliteDatabaseService;
import com.example.bossapply.model.DeliveryTaskItemView;
import com.example.bossapply.model.DeliveryTaskLeaseView;
import com.example.bossapply.model.DeliveryTaskProgressView;
import com.example.bossapply.model.DeliveryTaskSummaryView;
import com.example.bossapply.model.JobRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 投递任务服务，负责候选确认后的任务入库、扩展领取、租约和结果回报。
 */
@Service
public class DeliveryTaskService {

    private static final int LEASE_SECONDS = 90;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SqliteDatabaseService databaseService;
    private final JobRecordService jobRecordService;

    public DeliveryTaskService(SqliteDatabaseService databaseService, JobRecordService jobRecordService) {
        this.databaseService = databaseService;
        this.jobRecordService = jobRecordService;
    }

    /** 在候选队列确认事务中创建任务和任务明细，重复确认不会重复创建。 */
    public void createForConfirmedQueue(Connection connection, List<Long> queueIds, String plannedDate) throws SQLException {
        List<Long> distinctIds = queueIds.stream().distinct().toList();
        String now = OffsetDateTime.now().toString();
        long taskId;
        try (PreparedStatement task = connection.prepareStatement(
                "INSERT INTO delivery_task(task_name, planned_date, task_mode, task_status, total_count, waiting_count, created_at, updated_at) "
                        + "VALUES(?, ?, 'EXTENSION_AUTO', 'WAITING', ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            task.setString(1, "BOSS 自动投递 " + plannedDate + " " + now.substring(11, 16));
            task.setString(2, plannedDate);
            task.setInt(3, distinctIds.size());
            task.setInt(4, distinctIds.size());
            task.setString(5, now);
            task.setString(6, now);
            task.executeUpdate();
            try (ResultSet keys = task.getGeneratedKeys()) {
                if (keys.next()) {
                    taskId = keys.getLong(1);
                } else {
                    try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("SELECT last_insert_rowid()")) {
                        if (!resultSet.next()) throw new IllegalStateException("创建投递任务失败");
                        taskId = resultSet.getLong(1);
                    }
                }
            }
        }

        try (PreparedStatement queue = connection.prepareStatement(
                "SELECT id, source, source_job_id FROM delivery_queue WHERE id=? AND planned_date=? AND queue_status='APPROVED'")) {
            int rank = 1;
            for (Long queueId : distinctIds) {
                queue.setLong(1, queueId);
                queue.setString(2, plannedDate);
                try (ResultSet resultSet = queue.executeQuery()) {
                    if (!resultSet.next()) throw new IllegalArgumentException("确认岗位未能完整进入投递任务");
                    insertTaskItem(connection, taskId, resultSet.getLong("id"), resultSet.getString("source"),
                            resultSet.getString("source_job_id"), rank++, now);
                }
            }
        }
        insertEvent(connection, taskId, null, "TASK_CREATED", null, "WAITING", "候选队列确认后自动创建投递任务");
    }

    /** 查询管理端任务摘要。 */
    public List<DeliveryTaskSummaryView> list(String plannedDate) {
        String sql = plannedDate == null || plannedDate.isBlank()
                ? "SELECT id, task_name, planned_date, task_status, total_count, waiting_count, success_count, failed_count, unknown_count, created_at, updated_at FROM delivery_task ORDER BY id DESC"
                : "SELECT id, task_name, planned_date, task_status, total_count, waiting_count, success_count, failed_count, unknown_count, created_at, updated_at FROM delivery_task WHERE planned_date=? ORDER BY id DESC";
        try (Connection connection = databaseService.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (plannedDate != null && !plannedDate.isBlank()) statement.setString(1, plannedDate);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<DeliveryTaskSummaryView> result = new ArrayList<>();
                while (resultSet.next()) result.add(new DeliveryTaskSummaryView(
                        resultSet.getLong("id"), resultSet.getString("task_name"), resultSet.getString("planned_date"),
                        resultSet.getString("task_status"), resultSet.getInt("total_count"), resultSet.getInt("waiting_count"),
                        resultSet.getInt("success_count"), resultSet.getInt("failed_count"), resultSet.getInt("unknown_count"),
                        resultSet.getString("created_at"), resultSet.getString("updated_at")));
                return result;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取投递任务失败", exception);
        }
    }

    /** 查询任务明细，供管理端查看执行进度。 */
    public List<DeliveryTaskItemView> items(long taskId) {
        try (Connection connection = databaseService.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id, task_id, queue_id, source, source_job_id, item_status, item_rank, attempt_count, last_error, failure_reason, operator_note, prepared_at, submitted_at, result_at, created_at, updated_at FROM delivery_task_item WHERE task_id=? ORDER BY item_rank")) {
            statement.setLong(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<DeliveryTaskItemView> result = new ArrayList<>();
                while (resultSet.next()) {
                    JobRecord job = jobRecordService.get(resultSet.getString("source"), resultSet.getString("source_job_id"));
                    result.add(new DeliveryTaskItemView(
                            resultSet.getLong("id"), resultSet.getLong("task_id"), resultSet.getLong("queue_id"), job,
                            resultSet.getString("item_status"), resultSet.getInt("item_rank"), resultSet.getInt("attempt_count"),
                            resultSet.getString("last_error"), resultSet.getString("failure_reason"), resultSet.getString("operator_note"),
                            resultSet.getString("prepared_at"), resultSet.getString("submitted_at"), resultSet.getString("result_at"),
                            resultSet.getString("created_at"), resultSet.getString("updated_at")));
                }
                return result;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取投递任务明细失败", exception);
        }
    }

    /** 查询单个任务进度。 */
    public DeliveryTaskProgressView progress(long taskId) {
        try (Connection connection = databaseService.open()) {
            return progress(connection, taskId);
        } catch (SQLException exception) {
            throw new IllegalStateException("读取投递任务进度失败", exception);
        }
    }

    /** 扩展按租约领取一条任务；同一时刻全局只允许一个岗位执行。 */
    public DeliveryTaskLeaseView lease(DeliveryTaskLeaseRequest request) {
        String instanceId = required(request.instanceId());
        String now = OffsetDateTime.now().toString();
        String leaseUntil = OffsetDateTime.now().plusSeconds(LEASE_SECONDS).toString();
        String executionToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
        try (Connection connection = databaseService.open()) {
            connection.setAutoCommit(false);
            try {
                recoverExpired(connection, now);
                try (PreparedStatement active = connection.prepareStatement(
                        "SELECT 1 FROM delivery_task_item WHERE item_status IN ('CLAIMED','RUNNING','CLICKED_PENDING_CONFIRMATION') AND lease_until>? LIMIT 1")) {
                    active.setString(1, now);
                    try (ResultSet resultSet = active.executeQuery()) {
                        if (resultSet.next()) {
                            connection.commit();
                            return DeliveryTaskLeaseView.unavailable();
                        }
                    }
                }

                String sql = "SELECT i.id, i.task_id, i.queue_id, i.source, i.source_job_id, j.job_name, j.job_url "
                        + "FROM delivery_task_item i JOIN delivery_task t ON t.id=i.task_id "
                        + "JOIN job_record j ON j.source=i.source AND j.source_job_id=i.source_job_id "
                        + "WHERE i.item_status='WAITING' AND t.task_status IN ('WAITING','RUNNING') ORDER BY t.id, i.item_rank LIMIT 1";
                try (PreparedStatement select = connection.prepareStatement(sql); ResultSet resultSet = select.executeQuery()) {
                    if (!resultSet.next()) {
                        connection.commit();
                        return DeliveryTaskLeaseView.unavailable();
                    }
                    long itemId = resultSet.getLong("id");
                    long taskId = resultSet.getLong("task_id");
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE delivery_task_item SET item_status='CLAIMED', claim_instance_id=?, lease_until=?, execution_token_hash=?, attempt_count=attempt_count+1, prepared_at=?, updated_at=? WHERE id=? AND item_status='WAITING'")) {
                        update.setString(1, instanceId);
                        update.setString(2, leaseUntil);
                        update.setString(3, hash(executionToken));
                        update.setString(4, now);
                        update.setString(5, now);
                        update.setLong(6, itemId);
                        if (update.executeUpdate() != 1) {
                            connection.rollback();
                            return DeliveryTaskLeaseView.unavailable();
                        }
                    }
                    try (PreparedStatement updateTask = connection.prepareStatement(
                            "UPDATE delivery_task SET task_status='RUNNING', started_at=COALESCE(started_at,?), updated_at=? WHERE id=?")) {
                        updateTask.setString(1, now);
                        updateTask.setString(2, now);
                        updateTask.setLong(3, taskId);
                        updateTask.executeUpdate();
                    }
                    insertEvent(connection, taskId, itemId, "TASK_CLAIMED", "WAITING", "CLAIMED", "扩展领取任务");
                    connection.commit();
                    return new DeliveryTaskLeaseView(true, taskId, itemId, resultSet.getLong("queue_id"), resultSet.getString("source"),
                            resultSet.getString("source_job_id"), resultSet.getString("job_name"), resultSet.getString("job_url"),
                            instanceId, leaseUntil, executionToken);
                }
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("领取投递任务失败", exception);
        }
    }

    /** 更新扩展执行阶段并续租。 */
    public void stage(long itemId, DeliveryTaskStageRequest request) {
        String stage = required(request.stage()).toUpperCase(Locale.ROOT);
        if (!List.of("RUNNING", "CLICKED_PENDING_CONFIRMATION").contains(stage)) throw new IllegalArgumentException("不支持的任务阶段");
        try (Connection connection = databaseService.open()) {
            Claimed claimed = requireClaim(connection, itemId, request.instanceId(), request.executionToken());
            String now = OffsetDateTime.now().toString();
            String leaseUntil = OffsetDateTime.now().plusSeconds(LEASE_SECONDS).toString();
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE delivery_task_item SET item_status=?, lease_until=?, updated_at=? WHERE id=?")) {
                update.setString(1, stage);
                update.setString(2, leaseUntil);
                update.setString(3, now);
                update.setLong(4, itemId);
                update.executeUpdate();
            }
            insertEvent(connection, claimed.taskId(), itemId, "TASK_STAGE", claimed.status(), stage, null);
        } catch (SQLException exception) {
            throw new IllegalStateException("更新投递任务阶段失败", exception);
        }
    }

    /** 接收扩展结果，并在同一事务内更新职位、候选队列和任务明细。 */
    public DeliveryTaskProgressView result(long itemId, DeliveryTaskExtensionResultRequest request) {
        String resultStatus = required(request.resultStatus()).toUpperCase(Locale.ROOT);
        if (!List.of("SUCCESS", "FAILED", "UNKNOWN", "BLOCKED").contains(resultStatus)) {
            throw new IllegalArgumentException("不支持的投递结果状态");
        }
        try (Connection connection = databaseService.open()) {
            connection.setAutoCommit(false);
            try {
                Claimed claimed = requireClaim(connection, itemId, request.instanceId(), request.executionToken());
                String now = OffsetDateTime.now().toString();
                String itemStatus = "BLOCKED".equals(resultStatus) ? "UNKNOWN" : resultStatus;
                String jobStatus = "SUCCESS".equals(resultStatus) ? "APPLIED" : itemStatus;
                String queueStatus = "SUCCESS".equals(resultStatus) ? "APPLIED" : itemStatus;
                try (PreparedStatement job = connection.prepareStatement(
                        "UPDATE job_record SET apply_status=?, last_seen_at=? WHERE source=? AND source_job_id=? AND outsourcing_excluded=0")) {
                    job.setString(1, jobStatus);
                    job.setString(2, now);
                    job.setString(3, claimed.source());
                    job.setString(4, claimed.sourceJobId());
                    job.executeUpdate();
                }
                try (PreparedStatement queue = connection.prepareStatement(
                        "UPDATE delivery_queue SET queue_status=?, updated_at=? WHERE id=? AND queue_status='APPROVED'")) {
                    queue.setString(1, queueStatus);
                    queue.setString(2, now);
                    queue.setLong(3, claimed.queueId());
                    queue.executeUpdate();
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE delivery_task_item SET item_status=?, last_error=?, failure_reason=?, operator_note=?, result_at=?, submitted_at=CASE WHEN ?='SUCCESS' THEN ? ELSE submitted_at END, lease_until=NULL, claim_instance_id=NULL, execution_token_hash=NULL, updated_at=? WHERE id=?")) {
                    update.setString(1, itemStatus);
                    update.setString(2, request.failureReason());
                    update.setString(3, request.failureReason());
                    update.setString(4, request.operatorNote());
                    update.setString(5, now);
                    update.setString(6, resultStatus);
                    update.setString(7, now);
                    update.setString(8, now);
                    update.setLong(9, itemId);
                    update.executeUpdate();
                }
                insertEvent(connection, claimed.taskId(), itemId, "TASK_RESULT", claimed.status(), itemStatus, request.failureReason());
                refreshTask(connection, claimed.taskId(), now, "BLOCKED".equals(resultStatus));
                DeliveryTaskProgressView progress = progress(connection, claimed.taskId());
                connection.commit();
                return progress;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("回报投递结果失败", exception);
        }
    }

    private void insertTaskItem(Connection connection, long taskId, long queueId, String source, String sourceJobId,
                                int rank, String now) throws SQLException {
        try (PreparedStatement item = connection.prepareStatement(
                "INSERT INTO delivery_task_item(task_id, queue_id, source, source_job_id, item_status, item_rank, created_at, updated_at) VALUES(?,?,?,?, 'WAITING', ?, ?, ?)")) {
            item.setLong(1, taskId);
            item.setLong(2, queueId);
            item.setString(3, source);
            item.setString(4, sourceJobId);
            item.setInt(5, rank);
            item.setString(6, now);
            item.setString(7, now);
            item.executeUpdate();
        }
    }

    private Claimed requireClaim(Connection connection, long itemId, String instanceId, String executionToken) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT task_id, queue_id, source, source_job_id, item_status FROM delivery_task_item WHERE id=? AND claim_instance_id=? AND execution_token_hash=? AND item_status IN ('CLAIMED','RUNNING','CLICKED_PENDING_CONFIRMATION')")) {
            statement.setLong(1, itemId);
            statement.setString(2, required(instanceId));
            statement.setString(3, hash(required(executionToken)));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) throw new ResponseStatusException(HttpStatus.CONFLICT, "任务租约已失效，请停止当前投递并等待人工核实");
                return new Claimed(resultSet.getLong("task_id"), resultSet.getLong("queue_id"), resultSet.getString("source"), resultSet.getString("source_job_id"), resultSet.getString("item_status"));
            }
        }
    }

    private void recoverExpired(Connection connection, String now) throws SQLException {
        List<Long> affectedTaskIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT DISTINCT task_id FROM delivery_task_item WHERE item_status IN ('CLAIMED','RUNNING','CLICKED_PENDING_CONFIRMATION') AND lease_until<=?")) {
            statement.setString(1, now);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) affectedTaskIds.add(resultSet.getLong(1));
            }
        }
        try (PreparedStatement reset = connection.prepareStatement(
                "UPDATE delivery_task_item SET item_status='WAITING', claim_instance_id=NULL, lease_until=NULL, execution_token_hash=NULL, updated_at=? WHERE item_status='CLAIMED' AND lease_until<=?")) {
            reset.setString(1, now);
            reset.setString(2, now);
            reset.executeUpdate();
        }
        try (PreparedStatement unknown = connection.prepareStatement(
                "UPDATE delivery_task_item SET item_status='UNKNOWN', last_error='任务租约在执行阶段失效，禁止自动重试', result_at=?, claim_instance_id=NULL, lease_until=NULL, execution_token_hash=NULL, updated_at=? WHERE item_status IN ('RUNNING','CLICKED_PENDING_CONFIRMATION') AND lease_until<=?")) {
            unknown.setString(1, now);
            unknown.setString(2, now);
            unknown.setString(3, now);
            unknown.executeUpdate();
        }
        for (Long taskId : affectedTaskIds) refreshTask(connection, taskId, now, false);
    }

    private void refreshTask(Connection connection, long taskId, String now, boolean blocked) throws SQLException {
        int waiting = count(connection, taskId, "WAITING");
        int success = count(connection, taskId, "SUCCESS");
        int failed = count(connection, taskId, "FAILED");
        int unknown = count(connection, taskId, "UNKNOWN");
        int active = count(connection, taskId, "CLAIMED") + count(connection, taskId, "RUNNING") + count(connection, taskId, "CLICKED_PENDING_CONFIRMATION");
        String currentStatus;
        try (PreparedStatement statement = connection.prepareStatement("SELECT task_status FROM delivery_task WHERE id=?")) {
            statement.setLong(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) throw new IllegalArgumentException("投递任务不存在");
                currentStatus = resultSet.getString(1);
            }
        }
        String status = blocked || unknown > 0 ? "BLOCKED"
                : waiting == 0 && active == 0 ? "COMPLETED"
                : "WAITING".equals(currentStatus) && active == 0 ? "WAITING" : "RUNNING";
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE delivery_task SET task_status=?, waiting_count=?, success_count=?, failed_count=?, unknown_count=?, updated_at=?, completed_at=CASE WHEN ? IN ('COMPLETED','BLOCKED') THEN COALESCE(completed_at,?) ELSE completed_at END WHERE id=?")) {
            update.setString(1, status);
            update.setInt(2, waiting);
            update.setInt(3, success);
            update.setInt(4, failed);
            update.setInt(5, unknown);
            update.setString(6, now);
            update.setString(7, status);
            update.setString(8, now);
            update.setLong(9, taskId);
            update.executeUpdate();
        }
    }

    private DeliveryTaskProgressView progress(Connection connection, long taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) total, SUM(CASE WHEN item_status='WAITING' THEN 1 ELSE 0 END) waiting, SUM(CASE WHEN item_status='SUCCESS' THEN 1 ELSE 0 END) success, SUM(CASE WHEN item_status='FAILED' THEN 1 ELSE 0 END) failed, SUM(CASE WHEN item_status='UNKNOWN' THEN 1 ELSE 0 END) unknown, SUM(CASE WHEN item_status='SKIPPED' THEN 1 ELSE 0 END) skipped, SUM(CASE WHEN item_status IN ('SUCCESS','FAILED','UNKNOWN','SKIPPED') THEN 1 ELSE 0 END) completed FROM delivery_task_item WHERE task_id=?")) {
            statement.setLong(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) throw new IllegalArgumentException("投递任务不存在");
                return new DeliveryTaskProgressView(taskId, resultSet.getInt("total"), resultSet.getInt("waiting"), resultSet.getInt("success"), resultSet.getInt("failed"), resultSet.getInt("unknown"), resultSet.getInt("skipped"), resultSet.getInt("completed"));
            }
        }
    }

    private int count(Connection connection, long taskId, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM delivery_task_item WHERE task_id=? AND item_status=?")) {
            statement.setLong(1, taskId);
            statement.setString(2, status);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private void insertEvent(Connection connection, Long taskId, Long itemId, String type, String from, String to, String message) throws SQLException {
        try (PreparedStatement event = connection.prepareStatement(
                "INSERT INTO delivery_task_event(task_id, task_item_id, event_type, from_status, to_status, message, created_at) VALUES(?,?,?,?,?,?,?)")) {
            event.setLong(1, taskId);
            if (itemId == null) event.setNull(2, java.sql.Types.INTEGER); else event.setLong(2, itemId);
            event.setString(3, type);
            event.setString(4, from);
            event.setString(5, to);
            event.setString(6, message);
            event.setString(7, OffsetDateTime.now().toString());
            event.executeUpdate();
        }
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private String hash(String value) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行环境不支持 SHA-256", exception);
        }
    }

    private String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("参数不能为空");
        return value.trim();
    }

    private record Claimed(long taskId, long queueId, String source, String sourceJobId, String status) { }
}
