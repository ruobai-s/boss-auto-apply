package com.example.bossapply.controller;

import com.example.bossapply.dto.QueueConfirmRequest;
import com.example.bossapply.dto.QueueConfirmationTokenRequest;
import com.example.bossapply.model.ConfirmationTokenView;
import com.example.bossapply.model.QueueItemView;
import com.example.bossapply.service.DeliveryQueueService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 候选岗位队列和人工确认接口。
 */
@RestController
@RequestMapping("/api")
public class DeliveryQueueController {

    private final DeliveryQueueService deliveryQueueService;

    public DeliveryQueueController(DeliveryQueueService deliveryQueueService) {
        this.deliveryQueueService = deliveryQueueService;
    }

    /**
     * 按当前城市策略重建指定日期的候选岗位队列，不执行投递。
     */
    @PostMapping("/queue/rebuild")
    public List<QueueItemView> rebuild(@RequestParam(required = false) String plannedDate) {
        return deliveryQueueService.rebuild(parseDate(plannedDate));
    }

    /**
     * 查询指定日期的候选岗位队列，可按 QUEUED、APPROVED 等状态过滤。
     */
    @GetMapping("/queue")
    public List<QueueItemView> list(@RequestParam(required = false) String plannedDate,
                                    @RequestParam(required = false) String status) {
        return deliveryQueueService.list(parseDate(plannedDate), status);
    }

    /**
     * 在人工确认弹窗后申请短时一次性令牌。
     */
    @PostMapping("/queue/confirmation-token")
    public ConfirmationTokenView issueQueueConfirmationToken(
            @Valid @RequestBody QueueConfirmationTokenRequest request) {
        return deliveryQueueService.issueConfirmationToken(request.queueIds());
    }

    /**
     * 人工确认所选候选岗位，仅改变队列状态，不会触发 BOSS 页面操作。
     */
    @PostMapping("/queue/confirm")
    public ResponseEntity<List<QueueItemView>> confirm(@Valid @RequestBody QueueConfirmRequest request) {
        return ResponseEntity.ok(deliveryQueueService.confirm(request));
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("日期格式必须为 yyyy-MM-dd");
        }
    }
}
