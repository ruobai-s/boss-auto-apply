package com.example.bossapply.controller;

import com.example.bossapply.dto.DeliveryPolicyRequest;
import com.example.bossapply.model.CityQuota;
import com.example.bossapply.service.CityQuotaService;
import com.example.bossapply.service.PolicyStoreService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 投递策略接口。
 */
@RestController
@RequestMapping("/api/policy")
public class PolicyController {

    private final CityQuotaService cityQuotaService;
    private final PolicyStoreService policyStoreService;

    public PolicyController(CityQuotaService cityQuotaService, PolicyStoreService policyStoreService) {
        this.cityQuotaService = cityQuotaService;
        this.policyStoreService = policyStoreService;
    }

    /**
     * 返回当前策略。
     */
    @GetMapping
    public DeliveryPolicyRequest getPolicy() {
        return policyStoreService.get();
    }

    /**
     * 保存并校验城市策略。
     */
    @PostMapping
    public ResponseEntity<DeliveryPolicyRequest> savePolicy(@Valid @RequestBody DeliveryPolicyRequest request) {
        cityQuotaService.calculate(request);
        return ResponseEntity.ok(policyStoreService.save(request));
    }

    /**
     * 计算城市每日额度，供页面预览。
     */
    @PostMapping("/preview")
    public ResponseEntity<List<CityQuota>> preview(@Valid @RequestBody DeliveryPolicyRequest request) {
        return ResponseEntity.ok(cityQuotaService.calculate(request));
    }
}
