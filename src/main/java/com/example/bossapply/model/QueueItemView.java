package com.example.bossapply.model;

/**
 * 候选岗位队列展示项，包含职位记录和城市额度分配信息。
 */
public record QueueItemView(
        long queueId,
        JobRecord job,
        String plannedDate,
        String quotaCity,
        int cityPriority,
        String allocationType,
        String queueStatus,
        int queueRank,
        String createdAt
) {
}
