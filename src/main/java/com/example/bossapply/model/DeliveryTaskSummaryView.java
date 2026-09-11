package com.example.bossapply.model;

/** 管理端投递任务摘要。 */
public record DeliveryTaskSummaryView(
        long id, String taskName, String plannedDate, String taskStatus,
        int totalCount, int waitingCount, int successCount, int failedCount, int unknownCount,
        String createdAt, String updatedAt
) {
}
