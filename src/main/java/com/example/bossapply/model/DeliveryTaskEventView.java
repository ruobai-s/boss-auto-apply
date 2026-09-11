package com.example.bossapply.model;

/** 投递任务状态事件。 */
public record DeliveryTaskEventView(
        long id, long taskId, Long taskItemId, String eventType, String fromStatus, String toStatus,
        String message, String operator, String createdAt
) {
}
