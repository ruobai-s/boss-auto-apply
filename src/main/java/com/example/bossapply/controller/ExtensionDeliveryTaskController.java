package com.example.bossapply.controller;

import com.example.bossapply.dto.DeliveryTaskExtensionResultRequest;
import com.example.bossapply.dto.DeliveryTaskLeaseRequest;
import com.example.bossapply.dto.DeliveryTaskStageRequest;
import com.example.bossapply.model.DeliveryTaskLeaseView;
import com.example.bossapply.model.DeliveryTaskProgressView;
import com.example.bossapply.security.ExtensionAccessFilter;
import com.example.bossapply.service.DeliveryTaskService;
import com.example.bossapply.service.ExtensionConnectionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Chrome 扩展投递任务领取、阶段上报和结果回报接口。 */
@RestController
@RequestMapping("/api/extension/client/delivery-tasks")
public class ExtensionDeliveryTaskController {

    private final DeliveryTaskService deliveryTaskService;
    private final ExtensionConnectionService extensionConnectionService;

    public ExtensionDeliveryTaskController(DeliveryTaskService deliveryTaskService,
                                           ExtensionConnectionService extensionConnectionService) {
        this.deliveryTaskService = deliveryTaskService;
        this.extensionConnectionService = extensionConnectionService;
    }

    @PostMapping("/lease")
    public ResponseEntity<DeliveryTaskLeaseView> lease(
            @Valid @RequestBody DeliveryTaskLeaseRequest request,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = ExtensionAccessFilter.TOKEN_HEADER, required = false) String token) {
        extensionConnectionService.validateExtensionInstance(request.instanceId(), origin, token);
        return ResponseEntity.ok(deliveryTaskService.lease(request));
    }

    @PostMapping("/{itemId}/stage")
    public ResponseEntity<Void> stage(
            @PathVariable long itemId,
            @Valid @RequestBody DeliveryTaskStageRequest request,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = ExtensionAccessFilter.TOKEN_HEADER, required = false) String token) {
        extensionConnectionService.validateExtensionInstance(request.instanceId(), origin, token);
        deliveryTaskService.stage(itemId, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{itemId}/result")
    public ResponseEntity<DeliveryTaskProgressView> result(
            @PathVariable long itemId,
            @Valid @RequestBody DeliveryTaskExtensionResultRequest request,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = ExtensionAccessFilter.TOKEN_HEADER, required = false) String token) {
        extensionConnectionService.validateExtensionInstance(request.instanceId(), origin, token);
        return ResponseEntity.ok(deliveryTaskService.result(itemId, request));
    }
}
