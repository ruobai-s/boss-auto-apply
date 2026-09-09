package com.example.bossapply.controller;

import com.example.bossapply.config.AppSecurityProperties;
import com.example.bossapply.model.EmbeddedBrowserCollectResult;
import com.example.bossapply.model.EmbeddedBrowserStatus;
import com.example.bossapply.service.EmbeddedBrowserService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 独立受支持浏览器连接器接口。
 */
@RestController
@RequestMapping("/api/browser/embedded")
public class EmbeddedBrowserController {

    private final EmbeddedBrowserService embeddedBrowserService;
    private final AppSecurityProperties securityProperties;

    public EmbeddedBrowserController(EmbeddedBrowserService embeddedBrowserService,
                                     AppSecurityProperties securityProperties) {
        this.embeddedBrowserService = embeddedBrowserService;
        this.securityProperties = securityProperties;
    }

    /**
     * 启动独立可见浏览器，用户在窗口内手动完成登录。
     */
    @PostMapping("/start")
    public ResponseEntity<EmbeddedBrowserStatus> start() {
        return ResponseEntity.ok(embeddedBrowserService.start());
    }

    /**
     * 读取独立浏览器和 BOSS 页面状态。
     */
    @GetMapping("/status")
    public ResponseEntity<EmbeddedBrowserStatus> status() {
        return ResponseEntity.ok(embeddedBrowserService.status());
    }

    /**
     * 在用户完成登录后检测状态，并建立可采集的业务连接。
     */
    @PostMapping("/connect")
    public ResponseEntity<EmbeddedBrowserStatus> connect() {
        return ResponseEntity.ok(embeddedBrowserService.connect());
    }

    /**
     * 返回当前独立浏览器页面的临时截图；默认关闭，避免泄露账户页面信息。
     */
    @GetMapping(value = "/preview", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> preview() {
        if (!securityProperties.isPreviewEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        byte[] screenshot = embeddedBrowserService.screenshot();
        if (screenshot == null || screenshot.length == 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG)
                .body(screenshot);
    }

    /**
     * 关闭独立浏览器。
     */
    @PostMapping("/stop")
    public ResponseEntity<EmbeddedBrowserStatus> stop() {
        return ResponseEntity.ok(embeddedBrowserService.stop());
    }

    /**
     * 采集当前列表页已加载职位，只保存和筛选，不执行投递。
     */
    @PostMapping("/collect")
    public ResponseEntity<EmbeddedBrowserCollectResult> collect() {
        return ResponseEntity.ok(embeddedBrowserService.collect());
    }
}
