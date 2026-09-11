package com.example.bossapply.model;

/** 投递任务岗位明细。 */
public record DeliveryTaskItemView(
        long id, long taskId, long queueId, JobRecord job, String itemStatus, int itemRank,
        int attemptCount, String lastError, String failureReason, String operatorNote,
        String preparedAt, String submittedAt, String resultAt, String createdAt, String updatedAt
) {
}
