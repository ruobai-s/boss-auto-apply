package com.example.bossapply.controller;

import com.example.bossapply.model.BrowserStatus;
import com.example.bossapply.model.EmbeddedBrowserStatus;
import com.example.bossapply.service.BrowserConnectionService;
import com.example.bossapply.service.EmbeddedBrowserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 本地运行状态接口。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final BrowserConnectionService browserConnectionService;
    private final EmbeddedBrowserService embeddedBrowserService;

    public SystemController(BrowserConnectionService browserConnectionService,
                            EmbeddedBrowserService embeddedBrowserService) {
        this.browserConnectionService = browserConnectionService;
        this.embeddedBrowserService = embeddedBrowserService;
    }

    /**
     * 读取内置浏览器、兼容 CDP 连接和 BOSS 页面状态。
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        EmbeddedBrowserStatus embedded = embeddedBrowserService.status();
        if (embedded.running()) {
            // 独立 Edge 运行期间直接使用连接器状态，不再探测旧的固定 CDP 端点。
            return Map.of(
                    "application", "boss-auto-apply",
                    "deployment", "LOCAL",
                    "browserConnected", true,
                    "loginValid", embedded.loginValid(),
                    "automationReady", embedded.readyForCollection(),
                    "browserEndpoint", "managed-edge-cdp",
                    "message", embedded.message(),
                    "embeddedBrowser", embedded
            );
        }
        BrowserStatus cdp = browserConnectionService.probe();
        return Map.of(
                "application", "boss-auto-apply",
                "deployment", "LOCAL",
                "browserConnected", cdp.browserConnected(),
                "loginValid", cdp.loginValid(),
                "automationReady", cdp.automationReady(),
                "browserEndpoint", cdp.endpoint(),
                "message", cdp.message(),
                "embeddedBrowser", embedded
        );
    }
}

