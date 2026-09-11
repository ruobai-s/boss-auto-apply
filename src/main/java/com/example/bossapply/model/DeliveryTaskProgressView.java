package com.example.bossapply.model;

/** 投递任务进度汇总。 */
public record DeliveryTaskProgressView(long taskId, int total, int waiting, int success, int failed, int unknown, int skipped, int completed) {
}
