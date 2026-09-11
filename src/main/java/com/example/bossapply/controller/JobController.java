package com.example.bossapply.controller;

import com.example.bossapply.dto.JobRecordRequest;
import com.example.bossapply.model.JobRecord;
import com.example.bossapply.model.JobPageView;
import com.example.bossapply.model.JobRegisterResult;
import com.example.bossapply.service.JobRecordService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
/**
 * 职位记录接口，为后续 BOSS 采集适配器提供本地去重入口。
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobRecordService jobRecordService;

    public JobController(JobRecordService jobRecordService) {
        this.jobRecordService = jobRecordService;
    }

    /**
     * 注册职位快照并返回是否重复。
     */
    @PostMapping("/register")
    public ResponseEntity<JobRegisterResult> register(@Valid @RequestBody JobRecordRequest request) {
        return ResponseEntity.ok(jobRecordService.register(request));
    }

    /**
     * 查询本地职位记录。
     */
    @GetMapping
    public JobPageView list(@RequestParam(required = false) String status,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "50") int size) {
        return jobRecordService.page(status, page, size);
    }

    /**
     * Chrome 扩展完成自动投递点击后，将职位和对应候选队列标记为已投递。
     */
    @PostMapping("/{source}/{sourceJobId}/applied")
    public JobRecord markApplied(@PathVariable String source, @PathVariable String sourceJobId) {
        return jobRecordService.markApplied(source, sourceJobId);
    }
}


