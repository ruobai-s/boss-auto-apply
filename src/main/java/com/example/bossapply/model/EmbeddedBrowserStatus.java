package com.example.bossapply.model;

/**
 * 受支持浏览器连接器的运行状态，只返回页面元信息，不返回 Cookie 或凭据。
 */
public record EmbeddedBrowserStatus(
        String state,
        boolean running,
        boolean bossPage,
        boolean loginValid,
        boolean readyForCollection,
        String currentUrl,
        String title,
        String message,
        String lastEvent,
        String lastEventAt
) {

    /**
     * 创建尚未启动的浏览器状态。
     */
    public static EmbeddedBrowserStatus stopped(String message) {
        return stopped(message, "尚未启动", "");
    }

    /**
     * 创建带有最近生命周期事件的停止状态。
     */
    public static EmbeddedBrowserStatus stopped(String message, String lastEvent, String lastEventAt) {
        return new EmbeddedBrowserStatus("DISCONNECTED", false, false, false, false, "", "", message,
                lastEvent, lastEventAt);
    }
}
