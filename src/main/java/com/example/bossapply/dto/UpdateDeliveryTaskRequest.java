package com.example.bossapply.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** 修改投递任务请求。 */
public record UpdateDeliveryTaskRequest(
        @NotBlank(message = "任务名称不能为空") @Size(max = 100, message = "任务名称不能超过100个字符") String taskName,
        @NotNull(message = "计划日期不能为空") LocalDate plannedDate
) {
}
