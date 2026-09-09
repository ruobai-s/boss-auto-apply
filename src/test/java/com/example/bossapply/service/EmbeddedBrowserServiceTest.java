package com.example.bossapply.service;

import com.example.bossapply.dto.BossCollectRequest;
import com.example.bossapply.model.BossCollectResult;
import com.example.bossapply.model.EmbeddedBrowserCollectResult;
import com.example.bossapply.model.EmbeddedBrowserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证独立 Edge 连接器在人工登录前保持静默，并正确清理临时会话。
 */
class EmbeddedBrowserServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReturnDisconnectedBeforeStart() {
        EmbeddedBrowserService service = service();

        EmbeddedBrowserStatus status = service.status();

        assertEquals("DISCONNECTED", status.state());
        assertFalse(status.running());
        assertFalse(status.loginValid());
        assertFalse(status.readyForCollection());
    }

    @Test
    void shouldNotCollectBeforeBrowserIsReady() {
        EmbeddedBrowserService service = service();

        EmbeddedBrowserCollectResult result = service.collect();

        assertEquals("DISCONNECTED", result.state());
        assertEquals(0, result.jobsFound());
        assertNull(result.persisted());
    }

    /**
     * 验证未启动时不会建立浏览器业务连接。
     */
    @Test
    void shouldNotConnectBeforeStart() {
        EmbeddedBrowserService service = service();

        EmbeddedBrowserStatus status = service.connect();

        assertEquals("DISCONNECTED", status.state());
        assertFalse(status.running());
        assertFalse(status.loginValid());
        assertFalse(status.readyForCollection());
    }

    /**
     * 验证未启动时不会生成页面截图。
     */
    @Test
    void shouldNotCreateScreenshotBeforeStart() {
        EmbeddedBrowserService service = service();

        byte[] screenshot = service.screenshot();

        assertNull(screenshot);
    }

    /**
     * 验证停止操作会保留最近一次人工操作诊断，便于前端解释页面生命周期。
     */
    @Test
    void shouldExposeManualStopDiagnostic() {
        EmbeddedBrowserService service = service();

        EmbeddedBrowserStatus status = service.stop();

        assertEquals("DISCONNECTED", status.state());
        assertEquals("用户已关闭内置浏览器", status.lastEvent());
    }

    /**
     * 验证人工登录阶段只读取进程和本地端点，不访问任何 Playwright 页面 API。
     */
    @Test
    void shouldKeepStatusProbeQuietDuringLogin() throws Exception {
        EmbeddedBrowserService service = service();
        Process process = mock(Process.class);
        Page page = mock(Page.class);
        when(process.isAlive()).thenReturn(true);
        setField(service, "edgeProcess", process);
        setField(service, "cdpReady", true);
        setField(service, "page", page);

        EmbeddedBrowserStatus status = service.status();

        assertEquals("STARTED_NEEDS_LOGIN", status.state());
        assertFalse(status.loginValid());
        verify(page, never()).url();
        verify(page, never()).title();
        verify(page, never()).screenshot();
    }

    /**
     * 验证 Edge 包装进程退出后，只要原动态 CDP 端点仍可访问，系统仍识别为运行中。
     */
    @Test
    void shouldKeepRunningWhenRelaunchedEdgeCdpEndpointIsReady() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/json/version", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            EmbeddedBrowserService service = service();
            Process process = mock(Process.class);
            when(process.isAlive()).thenReturn(false);
            setField(service, "edgeProcess", process);
            setField(service, "cdpPort", server.getAddress().getPort());

            EmbeddedBrowserStatus status = service.status();

            assertEquals("STARTED_NEEDS_LOGIN", status.state());
            assertTrue(status.running());
            assertFalse(status.loginValid());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 验证建立连接只读取 CDP target 元数据，不接入 Playwright 或读取页面 DOM。
     */
    @Test
    void shouldConnectPassivelyWithoutReadingPageDom() throws Exception {
        HttpServer server = createCdpServer("""
                [{"type":"page","id":"page-1","title":"BOSS直聘职位列表",
                  "url":"https://www.zhipin.com/web/geek/jobs?ka=header-jobs"}]
                """);
        server.start();
        try {
            EmbeddedBrowserService service = service();
            Process process = mock(Process.class);
            Page page = mock(Page.class);
            when(process.isAlive()).thenReturn(false);
            setField(service, "edgeProcess", process);
            setField(service, "cdpPort", server.getAddress().getPort());
            setField(service, "page", page);

            EmbeddedBrowserStatus status = service.connect();

            assertEquals("CONNECTED", status.state());
            assertTrue(status.loginValid());
            assertTrue(status.readyForCollection());
            assertTrue(status.message().contains("静默连接"));
            verify(page, never()).evaluate(anyString());
            verify(page, never()).screenshot();
        } finally {
            server.stop(0);
        }
    }

    /**
     * 验证页面导航导致的执行上下文销毁会被识别为可重试错误。
     */
    @Test
    void shouldRecognizeExecutionContextDestroyedAsTransientNavigationError() {
        EmbeddedBrowserService service = service();

        assertTrue(service.isTransientNavigationError(new RuntimeException(
                "Execution context was destroyed, most likely because of a navigation")));
        assertFalse(service.isTransientNavigationError(new RuntimeException("Target page has been closed")));
    }
    /**
     * 验证采集遇到页面导航竞争后立即断开短连接，不循环读取页面。
     */
    @Test
    void shouldStopShortCollectionWhenNavigationDestroysExecutionContext() throws Exception {
        BossCollectorService collectorService = mock(BossCollectorService.class);
        EmbeddedBrowserService service = service(collectorService);
        Process process = mock(Process.class);
        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);
        Page activePage = mock(Page.class);

        when(process.isAlive()).thenReturn(true);
        when(browser.isConnected()).thenReturn(true);
        when(context.pages()).thenReturn(List.of(activePage));
        when(activePage.isClosed()).thenReturn(false);
        when(activePage.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        when(activePage.evaluate(anyString()))
                .thenThrow(new RuntimeException("Execution context was destroyed, most likely because of a navigation"));
        prepareReadyBrowser(service, process, browser, context, activePage);

        EmbeddedBrowserCollectResult result = service.collect();

        assertEquals("PAGE_UNAVAILABLE", result.state());
        assertEquals(0, result.jobsFound());
        assertNull(result.persisted());
        verify(activePage, times(1)).evaluate(anyString());
        verify(collectorService, never()).collect(any(BossCollectRequest.class));
    }

    /**
     * 验证职位页面持续导航时返回脱敏提示，并且不会保存任何不完整职位。
     */
    @Test
    void shouldPauseCollectionWithoutLeakingStackWhenNavigationContinues() throws Exception {
        BossCollectorService collectorService = mock(BossCollectorService.class);
        EmbeddedBrowserService service = service(collectorService);
        Process process = mock(Process.class);
        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);
        Page activePage = mock(Page.class);
        RuntimeException navigationError = new RuntimeException(
                "Execution context was destroyed at C:\\Users\\HP\\AppData\\Local");

        when(process.isAlive()).thenReturn(true);
        when(browser.isConnected()).thenReturn(true);
        when(context.pages()).thenReturn(List.of(activePage));
        when(activePage.isClosed()).thenReturn(false);
        when(activePage.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        when(activePage.evaluate(anyString()))
                .thenThrow(navigationError)
                .thenThrow(navigationError)
                .thenThrow(navigationError);
        prepareReadyBrowser(service, process, browser, context, activePage);

        EmbeddedBrowserCollectResult result = service.collect();

        assertEquals("PAGE_UNAVAILABLE", result.state());
        assertEquals(0, result.jobsFound());
        assertNull(result.persisted());
        assertFalse(result.message().contains("Execution context"));
        assertFalse(result.message().contains("AppData"));
        verify(activePage, times(1)).evaluate(anyString());
        verify(collectorService, never()).collect(any(BossCollectRequest.class));
    }

    /**
     * 验证本地 CDP target 已进入 403 页面时立即暂停采集，且不读取页面正文。
     */
    @Test
    void shouldDetectAccessRestrictionFromCdpTarget() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/json/version", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/json/list", exchange -> {
            byte[] body = """
                    [{"type":"page","title":"BOSS直聘","url":"https://www.zhipin.com/web/passport/zp/403.html?code=32"}]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            EmbeddedBrowserService service = service();
            Process process = mock(Process.class);
            when(process.isAlive()).thenReturn(false);
            setField(service, "edgeProcess", process);
            setField(service, "cdpPort", server.getAddress().getPort());

            EmbeddedBrowserStatus status = service.status();

            assertEquals("SECURITY_CHECK_REQUIRED", status.state());
            assertFalse(status.loginValid());
            assertFalse(status.readyForCollection());
            assertEquals("https://www.zhipin.com/web/passport/zp/403.html", status.currentUrl());
            assertTrue(status.message().contains("访问限制页面"));
        } finally {
            server.stop(0);
        }
    }

    /**
     * 验证职位列表为空时不在短连接中循环读取页面。
     */
    @Test
    void shouldStopShortCollectionWhenJobListIsEmpty() throws Exception {
        BossCollectorService collectorService = mock(BossCollectorService.class);
        EmbeddedBrowserService service = service(collectorService);
        Process process = mock(Process.class);
        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);
        Page activePage = mock(Page.class);

        when(process.isAlive()).thenReturn(true);
        when(browser.isConnected()).thenReturn(true);
        when(context.pages()).thenReturn(List.of(activePage));
        when(activePage.isClosed()).thenReturn(false);
        when(activePage.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        when(activePage.evaluate(anyString())).thenReturn(List.of());
        prepareReadyBrowser(service, process, browser, context, activePage);

        EmbeddedBrowserCollectResult result = service.collect();

        assertEquals("PAGE_UNAVAILABLE", result.state());
        assertEquals(0, result.jobsFound());
        verify(activePage, times(1)).evaluate(anyString());
        verify(collectorService, never()).collect(any(BossCollectRequest.class));
    }

    /**
     * 验证平台私有区字体字符不会作为乱码薪资写入数据库。
     */
    @Test
    void shouldRemovePrivateUseCharactersFromSalary() {
        EmbeddedBrowserService service = service();

        assertEquals("", service.sanitizeSalary("\uE034\uE036-\uE035\uE031K"));
        assertEquals("25-35K", service.sanitizeSalary(" 25-35K "));
    }

    /**
     * 验证现场确认的职位卡片、公司、地点和薪资选择器已进入采集脚本。
     */
    @Test
    void shouldUseCalibratedBossJobSelectors() throws Exception {
        BossCollectorService collectorService = mock(BossCollectorService.class);
        EmbeddedBrowserService service = service(collectorService);
        Process process = mock(Process.class);
        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);
        Page activePage = mock(Page.class);
        BossCollectResult persisted = new BossCollectResult(1, 1, 0, 0, 0, "2026-09-08T15:30:00+08:00");

        when(process.isAlive()).thenReturn(true);
        when(browser.isConnected()).thenReturn(true);
        when(context.pages()).thenReturn(List.of(activePage));
        when(activePage.isClosed()).thenReturn(false);
        when(activePage.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        when(activePage.evaluate(anyString())).thenReturn(List.of(Map.of(
                "sourceJobId", "job-003",
                "jobName", "后端工程师",
                "companyName", "示例公司",
                "city", "北京",
                "salary", "",
                "jobUrl", "https://www.zhipin.com/job_detail/job-003.html")));
        when(collectorService.collect(any(BossCollectRequest.class))).thenReturn(persisted);
        prepareReadyBrowser(service, process, browser, context, activePage);

        service.collect();

        org.mockito.ArgumentCaptor<String> script = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(activePage).evaluate(script.capture());
        assertTrue(script.getValue().contains("li.job-card-box"));
        assertTrue(script.getValue().contains("span.boss-name"));
        assertTrue(script.getValue().contains("span.company-location"));
        assertTrue(script.getValue().contains("span.job-salary"));
    }

    /**
     * 验证新版 BOSS 顶部个人入口可以作为登录成功标记。
     */
    @Test
    void shouldConfirmLoginWithPersonalHeaderMarker() {
        EmbeddedBrowserService service = service();

        boolean confirmed = service.isLoginConfirmed(Map.of(
                "authenticatedMarker", true,
                "loginMarker", false,
                "loginPrompt", false,
                "loggedInText", false));

        assertTrue(confirmed);
    }

    /**
     * 验证页面仍显示登录入口时不能建立业务连接，即使同时出现个人入口标记。
     */
    @Test
    void shouldRejectLoginWhenLoginMarkerIsVisible() {
        EmbeddedBrowserService service = service();

        boolean confirmed = service.isLoginConfirmed(Map.of(
                "authenticatedMarker", true,
                "loginMarker", true,
                "loginPrompt", false,
                "loggedInText", true));

        assertFalse(confirmed);
    }

    /**
     * 验证主动断开自动化连接时，不会把 Playwright 页面代理关闭误报为真实页面关闭。
     */
    @Test
    void shouldIgnorePageCloseEventWithoutBusinessConnection() throws Exception {
        EmbeddedBrowserService service = service();
        setField(service, "lastEvent", "尚未确认登录");
        setField(service, "businessConnected", false);

        service.handlePageClosed(mock(Page.class));

        assertEquals("尚未确认登录", service.status().lastEvent());
    }
    /**
     * 验证普通 Edge 启动参数不包含 Playwright 直接启动时的高风险自动化参数。
     */
    @Test
    void shouldBuildMinimalManagedEdgeCommand() {
        EmbeddedBrowserService service = service();

        List<String> command = service.buildEdgeCommand(
                Path.of("C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"),
                tempDir.resolve("edge-session"), 19333);

        assertTrue(command.contains("--remote-debugging-address=127.0.0.1"));
        assertTrue(command.contains("--remote-debugging-port=19333"));
        assertTrue(command.stream().anyMatch(value -> value.startsWith("--user-data-dir=")));
        assertFalse(command.contains("--edge-skip-compat-layer-relaunch"));
        assertFalse(command.contains("--no-sandbox"));
        assertFalse(command.contains("--remote-debugging-pipe"));
        assertFalse(command.stream().anyMatch(value -> value.startsWith("--disable-features=")));
    }

    /**
     * 验证 Spring 容器关闭时只断开自动化客户端，不终止独立 Edge 或删除会话目录。
     */
    @Test
    void shouldKeepManagedEdgeSessionWhenServiceIsDestroyed() throws Exception {
        HttpServer server = createCdpServer("[]");
        server.start();
        try {
            EmbeddedBrowserService service = service();
            Process process = mock(Process.class);
            when(process.isAlive()).thenReturn(true);
            Path sessionDirectory = Files.createDirectories(tempDir.resolve("edge-preserved-session"));
            setField(service, "edgeProcess", process);
            setField(service, "sessionDirectory", sessionDirectory);
            setField(service, "cdpPort", server.getAddress().getPort());

            service.destroy();

            verify(process, never()).destroy();
            verify(process, never()).destroyForcibly();
            assertTrue(Files.isDirectory(sessionDirectory));
            assertTrue(Files.isRegularFile(tempDir.resolve("active-session.json")));
        } finally {
            server.stop(0);
        }
    }

    /**
     * 验证新服务实例可以根据最小元数据恢复原 Edge，且启动操作会直接复用该窗口。
     */
    @Test
    void shouldRestoreAndReuseManagedEdgeAfterServiceRestart() throws Exception {
        HttpServer server = createCdpServer("[]");
        server.start();
        try {
            Path sessionDirectory = Files.createDirectories(tempDir.resolve("edge-restored-session"));
            writeSessionMetadata(sessionDirectory, server.getAddress().getPort());
            EmbeddedBrowserService service = service();

            EmbeddedBrowserStatus status = service.start();

            assertEquals("STARTED_NEEDS_LOGIN", status.state());
            assertTrue(status.running());
            assertTrue(status.message().contains("已恢复重启前"));
            assertTrue(Files.isDirectory(sessionDirectory));
            try (var paths = Files.list(tempDir)) {
                assertEquals(1, paths.filter(Files::isDirectory).count());
            }
        } finally {
            server.stop(0);
        }
    }

    /**
     * 验证越出受管会话根目录的元数据会被拒绝，且不会删除外部目录。
     */
    @Test
    void shouldRejectRestoredSessionOutsideManagedRoot() throws Exception {
        Path outsideDirectory = tempDir.resolveSibling(tempDir.getFileName() + "-outside");
        Files.createDirectories(outsideDirectory);
        try {
            writeSessionMetadata(outsideDirectory, 19333);
            EmbeddedBrowserService service = service();

            EmbeddedBrowserStatus status = service.status();

            assertEquals("DISCONNECTED", status.state());
            assertTrue(Files.isDirectory(outsideDirectory));
            assertFalse(Files.exists(tempDir.resolve("active-session.json")));
        } finally {
            Files.deleteIfExists(outsideDirectory);
        }
    }

    /**
     * 验证失效 CDP 端口不会被误识别为运行中的 Edge，并清除失效接管元数据。
     */
    @Test
    void shouldRemoveMetadataWhenRestoredCdpEndpointIsUnavailable() throws Exception {
        HttpServer server = createCdpServer("[]");
        int unavailablePort = server.getAddress().getPort();
        server.stop(0);
        Path sessionDirectory = Files.createDirectories(tempDir.resolve("edge-stale-session"));
        writeSessionMetadata(sessionDirectory, unavailablePort);
        EmbeddedBrowserService service = service();

        EmbeddedBrowserStatus status = service.status();

        assertEquals("DISCONNECTED", status.state());
        assertFalse(Files.exists(sessionDirectory));
        assertFalse(Files.exists(tempDir.resolve("active-session.json")));
    }

    /**
     * 验证用户明确停止连接器后删除临时会话目录和接管元数据。
     */
    @Test
    void shouldDeleteTemporaryBrowserSessionWhenStopped() throws Exception {
        EmbeddedBrowserService service = service();
        Path sessionDirectory = Files.createDirectories(tempDir.resolve("edge-test-session"));
        Files.writeString(sessionDirectory.resolve("marker.txt"), "temporary");
        writeSessionMetadata(sessionDirectory, 19333);
        setField(service, "sessionDirectory", sessionDirectory);

        service.stop();

        assertFalse(Files.exists(sessionDirectory));
        assertFalse(Files.exists(tempDir.resolve("active-session.json")));
    }

    /**
     * 验证启动状态审计会删除无进程关联的孤儿目录，但不会误删非 edge-* 目录。
     */
    @Test
    void shouldCleanupOnlyOrphanManagedSessionDirectories() throws Exception {
        Path orphan = Files.createDirectories(tempDir.resolve("edge-orphan"));
        Files.writeString(orphan.resolve("marker.txt"), "temporary");
        Path unrelated = Files.createDirectories(tempDir.resolve("keep-data"));
        EmbeddedBrowserService service = service();

        service.status();

        assertFalse(Files.exists(orphan));
        assertTrue(Files.isDirectory(unrelated));
    }

    /**
     * 验证支持 ACL 的文件系统只保留目录所有者权限；不支持时安全跳过。
     */
    @Test
    void shouldRestrictManagedSessionAclWhenSupported() throws Exception {
        EmbeddedBrowserService service = service();
        Path sessionDirectory = Files.createDirectories(tempDir.resolve("edge-acl-test"));
        AclFileAttributeView view = Files.getFileAttributeView(
                sessionDirectory, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);

        boolean applied = service.secureSessionDirectory(sessionDirectory);

        if (view == null) {
            assertFalse(applied);
        } else {
            assertTrue(applied);
            assertEquals(1, view.getAcl().size());
            assertEquals(Files.getOwner(sessionDirectory), view.getAcl().get(0).principal());
            assertFalse(hasInheritedWindowsAcl(sessionDirectory));
        }
    }

    /**
     * 验证权限加固拒绝越出受管会话根目录的路径。
     */
    @Test
    void shouldRejectAclChangeOutsideManagedSessionRoot() throws Exception {
        EmbeddedBrowserService service = service();
        Path outside = tempDir.resolveSibling(tempDir.getFileName() + "-acl-outside");
        Files.createDirectories(outside);
        try {
            assertFalse(service.secureSessionDirectory(outside));
            assertTrue(Files.isDirectory(outside));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    /**
     * Windows 下使用系统 ACL 工具确认不存在继承标记；其他系统无需执行该检查。
     */
    private boolean hasInheritedWindowsAcl(Path directory) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            return false;
        }
        Process process = new ProcessBuilder("icacls.exe", directory.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue());
        return output.contains("(I)");
    }
    private HttpServer createCdpServer(String targetList) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/json/version", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/json/list", exchange -> {
            byte[] body = targetList.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        return server;
    }

    private void writeSessionMetadata(Path sessionDirectory, int port) throws Exception {
        new ObjectMapper().writeValue(tempDir.resolve("active-session.json").toFile(), Map.of(
                "cdpPort", port,
                "sessionDirectory", sessionDirectory.toAbsolutePath().normalize().toString()));
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = EmbeddedBrowserService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void prepareReadyBrowser(EmbeddedBrowserService service, Process process, Browser browser,
                                     BrowserContext context, Page activePage) throws Exception {
        setField(service, "edgeProcess", process);
        setField(service, "cdpReady", true);
        setField(service, "browser", browser);
        setField(service, "context", context);
        setField(service, "page", activePage);
        setField(service, "businessConnected", true);
    }

    private EmbeddedBrowserService service() {
        return service(mock(BossCollectorService.class));
    }

    private EmbeddedBrowserService service(BossCollectorService collectorService) {
        return new EmbeddedBrowserService(collectorService, "msedge", "",
                "https://www.zhipin.com/web/geek/jobs", 1000, 1000, 50, tempDir.toString());
    }
}

