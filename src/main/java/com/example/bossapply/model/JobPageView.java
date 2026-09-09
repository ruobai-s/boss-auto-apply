package com.example.bossapply.model;

import java.util.List;

/**
 * 职位分页结果，同时返回全量状态统计，避免前端为统计加载全部记录。
 */
public record JobPageView(
        List<JobRecord> items,
        long total,
        int page,
        int size,
        long outsourcingExcluded,
        long outsourcingManualReview,
        long applied
) {
}
