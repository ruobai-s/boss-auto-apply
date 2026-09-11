package com.example.bossapply.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/** 新建人工辅助批量投递任务请求。 */
public record CreateDeliveryTaskRequest(
        @NotBlank(message = "任务名称不能为空") @Size(max = 100, message = "任务名称不能超过100个字符") String taskName,
        @NotNull(message = "计划日期不能为空") LocalDate plannedDate,
        @NotNull(message = "候选队列不能为空") @Size(min = 1, max = 150, message = "单次任务必须包含1到150个岗位") List<Long> queueIds,
        @NotBlank(message = "缺少一次性人工确认令牌") String confirmationToken
) {
}
