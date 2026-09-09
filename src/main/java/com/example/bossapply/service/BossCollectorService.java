package com.example.bossapply.service;

import com.example.bossapply.dto.BossCollectRequest;
import com.example.bossapply.dto.JobRecordRequest;
import com.example.bossapply.model.BossCollectResult;
import com.example.bossapply.model.JobRegisterResult;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * BOSS 职位采集适配器，接收浏览器连接器提交的标准化职位快照。
 */
@Service
public class BossCollectorService {

    private final JobRecordService jobRecordService;

    public BossCollectorService(JobRecordService jobRecordService) {
        this.jobRecordService = jobRecordService;
    }

    /**
     * 批量保存职位快照并返回筛选统计，不执行投递动作。
     */
    public BossCollectResult collect(BossCollectRequest request) {
        int duplicates = 0;
        int excluded = 0;
        int manualReview = 0;
        for (JobRecordRequest job : request.jobs()) {
            JobRegisterResult result = jobRecordService.register(job);
            if (result.duplicate()) {
                duplicates++;
            }
            if (result.job().outsourcingExcluded()) {
                excluded++;
            }
            if (result.job().outsourcingManualReview()) {
                manualReview++;
            }
        }
        String capturedAt = request.capturedAt() == null || request.capturedAt().isBlank()
                ? OffsetDateTime.now().toString() : request.capturedAt();
        return new BossCollectResult(request.jobs().size(), request.jobs().size() - duplicates,
                duplicates, excluded, manualReview, capturedAt);
    }
}
