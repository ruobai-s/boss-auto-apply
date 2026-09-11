package com.example.bossapply.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 本地管理端运行状态接口。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    /**
     * 返回管理端自身状态，不探测、启动或控制任何浏览器进程。
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "application", "boss-auto-apply",
                "deployment", "LOCAL",
                "message", "管理端运行正常"
        );
    }
}