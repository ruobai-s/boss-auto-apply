package com.example.bossapply.controller;

import com.example.bossapply.dto.ExtensionHeartbeatRequest;
import com.example.bossapply.dto.ExtensionPairRequest;
import com.example.bossapply.model.ExtensionConnectionStatus;
import com.example.bossapply.model.ExtensionHeartbeatResult;
import com.example.bossapply.model.ExtensionPairResult;
import com.example.bossapply.model.ExtensionPairingView;
import com.example.bossapply.security.ExtensionAccessFilter;
import com.example.bossapply.service.ExtensionConnectionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chrome 扩展配对、连接状态和心跳接口。
 */
@RestController
@RequestMapping("/api/extension")
public class ExtensionConnectionController {

    private final ExtensionConnectionService extensionConnectionService;

    public ExtensionConnectionController(ExtensionConnectionService extensionConnectionService) {
        this.extensionConnectionService = extensionConnectionService;
    }

    /**
     * 管理端生成一次性配对码。
     */
    @PostMapping("/pairing/start")
    public ResponseEntity<ExtensionPairingView> startPairing() {
        return ResponseEntity.ok(extensionConnectionService.startPairing());
    }

    /**
     * 管理端读取扩展连接状态。
     */
    @GetMapping("/status")
    public ResponseEntity<ExtensionConnectionStatus> status() {
        return ResponseEntity.ok(extensionConnectionService.status());
    }

    /**
     * 管理端解除当前扩展配对。
     */
    @PostMapping("/unpair")
    public ResponseEntity<ExtensionConnectionStatus> unpair() {
        return ResponseEntity.ok(extensionConnectionService.unpair());
    }

    /**
     * 扩展使用一次性配对码换取长期令牌。
     */
    @PostMapping("/client/pair")
    public ResponseEntity<ExtensionPairResult> pair(
            @Valid @RequestBody ExtensionPairRequest request,
            @RequestHeader(value = "Origin", required = false) String origin) {
        return ResponseEntity.ok(extensionConnectionService.pair(request, origin));
    }

    /**
     * 扩展上报BOSS标签页、登录和安全验证状态。
     */
    @PostMapping("/client/heartbeat")
    public ResponseEntity<ExtensionHeartbeatResult> heartbeat(
            @Valid @RequestBody ExtensionHeartbeatRequest request,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = ExtensionAccessFilter.TOKEN_HEADER, required = false) String token) {
        return ResponseEntity.ok(extensionConnectionService.heartbeat(request, origin, token));
    }
}
