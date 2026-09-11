package com.example.bossapply.controller;

import com.example.bossapply.model.DeliveryTaskItemView;
import com.example.bossapply.model.DeliveryTaskProgressView;
import com.example.bossapply.model.DeliveryTaskSummaryView;
import com.example.bossapply.service.DeliveryTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理端投递任务查询接口，确认后只负责查看进度，不再触发浏览器操作。 */
@RestController
@RequestMapping("/api/delivery-tasks")
public class DeliveryTaskController {

    private final DeliveryTaskService deliveryTaskService;

    public DeliveryTaskController(DeliveryTaskService deliveryTaskService) {
        this.deliveryTaskService = deliveryTaskService;
    }

    @GetMapping
    public List<DeliveryTaskSummaryView> list(@RequestParam(required = false) String plannedDate) {
        return deliveryTaskService.list(plannedDate);
    }

    @GetMapping("/{taskId}/items")
    public List<DeliveryTaskItemView> items(@PathVariable long taskId) {
        return deliveryTaskService.items(taskId);
    }

    @GetMapping("/{taskId}/progress")
    public DeliveryTaskProgressView progress(@PathVariable long taskId) {
        return deliveryTaskService.progress(taskId);
    }
}
