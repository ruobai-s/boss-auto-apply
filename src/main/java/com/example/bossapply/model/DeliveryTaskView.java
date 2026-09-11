package com.example.bossapply.model;

/** 投递任务列表和详情摘要。 */
public record DeliveryTaskView(
        long id, String taskName, String plannedDate, String taskMode, String taskStatus,
        int totalCount, int waitingCount, int successCount, int failedCount, int unknownCount, int skippedCount,
        String lastError, String startedAt, String pausedAt, String completedAt, String createdAt, String updatedAt
) {
}
