package com.example.bossapply.model;

/** 扩展领取到的单条投递任务。 */
public record DeliveryTaskLeaseView(
        boolean available, long taskId, long taskItemId, long queueId, String source, String sourceJobId,
        String jobName, String jobUrl, String instanceId, String leaseUntil, String executionToken
) {
    public static DeliveryTaskLeaseView unavailable() {
        return new DeliveryTaskLeaseView(false, 0, 0, 0, "", "", "", "", "", "", "");
    }
}
