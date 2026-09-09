package com.example.bossapply.controller;

import com.example.bossapply.dto.OutsourcingRuleConfigRequest;
import com.example.bossapply.model.JobSnapshot;
import com.example.bossapply.model.OutsourcingDecision;
import com.example.bossapply.model.OutsourcingRuleConfig;
import com.example.bossapply.model.OutsourcingRuleSaveResult;
import com.example.bossapply.service.JobRecordService;
import com.example.bossapply.service.OutsourcingFilterService;
import com.example.bossapply.service.OutsourcingRuleStoreService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 外包识别和自定义规则接口。
 */
@RestController
@RequestMapping("/api/filter")
public class FilterController {

    private final OutsourcingFilterService outsourcingFilterService;
    private final OutsourcingRuleStoreService ruleStoreService;
    private final JobRecordService jobRecordService;

    public FilterController(OutsourcingFilterService outsourcingFilterService,
                            OutsourcingRuleStoreService ruleStoreService,
                            JobRecordService jobRecordService) {
        this.outsourcingFilterService = outsourcingFilterService;
        this.ruleStoreService = ruleStoreService;
        this.jobRecordService = jobRecordService;
    }

    /**
     * 预览单个职位是否需要排除或人工复核。
     */
    @PostMapping("/outsourcing")
    public OutsourcingDecision checkOutsourcing(@RequestBody JobSnapshot job) {
        return outsourcingFilterService.decide(job);
    }

    /**
     * 返回当前公司排除名单和自定义条件。
     */
    @GetMapping("/rules")
    public OutsourcingRuleConfig getRules() {
        return ruleStoreService.get();
    }

    /**
     * 保存多家公司和多条条件，并立即重新检查本地职位。
     */
    @PostMapping("/rules")
    public OutsourcingRuleSaveResult saveRules(@Valid @RequestBody OutsourcingRuleConfigRequest request) {
        OutsourcingRuleConfig config = ruleStoreService.save(request);
        int reevaluatedJobs = jobRecordService.reapplyOutsourcingRules();
        return new OutsourcingRuleSaveResult(config, reevaluatedJobs);
    }
}
