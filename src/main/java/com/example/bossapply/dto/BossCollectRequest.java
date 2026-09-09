package com.example.bossapply.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * BOSS 职位采集桥接请求，浏览器连接器将职位页面转换为统一字段后提交。
 */
public record BossCollectRequest(
        @NotNull(message = "职位列表不能为空")
        @Size(max = 50, message = "单次最多导入50个职位")
        @Valid
        List<JobRecordRequest> jobs,
        @Size(max = 100, message = "采集时间长度不能超过100个字符")
        String capturedAt
) {
}
