package com.example.bossapply.controller;

import com.example.bossapply.dto.BossCollectRequest;
import com.example.bossapply.model.BossCollectResult;
import com.example.bossapply.service.BossCollectorService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * BOSS 职位采集接口，只处理采集、筛选和本地预览数据。
 */
@RestController
@RequestMapping("/api/collector/boss")
public class BossCollectorController {

    private final BossCollectorService bossCollectorService;

    public BossCollectorController(BossCollectorService bossCollectorService) {
        this.bossCollectorService = bossCollectorService;
    }

    /**
     * 接收浏览器连接器提交的职位列表，不会点击投递按钮。
     */
    @PostMapping("/jobs")
    public ResponseEntity<BossCollectResult> collect(@Valid @RequestBody BossCollectRequest request) {
        return ResponseEntity.ok(bossCollectorService.collect(request));
    }
}
