package com.example.bossapply.service;

import com.example.bossapply.dto.BossCollectRequest;
import com.example.bossapply.dto.JobRecordRequest;
import com.example.bossapply.model.BossCollectResult;
import com.example.bossapply.model.EmbeddedBrowserCollectResult;
import com.example.bossapply.model.EmbeddedBrowserStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 管理独立的普通 Edge 进程，并在用户完成登录后通过 CDP 建立 Playwright 连接。
 * 浏览器会话仅保存在独立临时目录中，停止后删除，不读取或导出密码、Cookie 和短信验证码。
 */
@Service
public class EmbeddedBrowserService {

    private static final String BOSS_HOST = "zhipin.com";
    private static final long COLLECTION_STABILITY_TIMEOUT_MS = 5000L;
    private static final long COLLECTION_RETRY_INTERVAL_MS = 400L;
    private static final String EXTRACTION_SCRIPT = """
            () => {
              const selectors = {
                card: ['li.job-card-box', '[ka=job-card]', '.job-card-wrapper', 'li.job-card-wrapper'],
                link: 'a.job-name[href*=\"/job_detail/\"], a[href*=\"/job_detail/\"]',
                name: ['a.job-name', '.job-name', '[class*=job-name]', 'h3', 'h4'],
                company: ['span.boss-name', '.boss-name', '.company-name', '[class*=company-name]', '.company-text'],
                city: ['span.company-location', '.company-location', '.job-area', '[class*=job-area]'],
                salary: ['span.job-salary', '.job-salary', '.salary', '[class*=salary]']
              };
              const text = (root, list) => {
                for (const selector of list) {
                  const node = root.querySelector(selector);
                  if (node && node.textContent && node.textContent.trim()) return node.textContent.trim();
                }
                return '';
              };
              const unique = (nodes) => Array.from(new Set(nodes));
              let cards = unique(selectors.card.flatMap(selector => Array.from(document.querySelectorAll(selector))));
              if (!cards.length) {
                cards = unique(Array.from(document.querySelectorAll(selectors.link)).map(link => link.closest('li,article,div')));
              }
              return cards.map(card => {
                const link = card.querySelector(selectors.link);
                const href = link ? link.href : '';
                const match = href.match(/\\/job_detail\\/([^/?#]+)/i);
                const sourceJobId = (card.getAttribute('data-jobid') || (match ? match[1] : '')).trim();
                const salary = text(card, selectors.salary);
                return {
                  sourceJobId,
                  companyName: text(card, selectors.company),
                  jobName: text(card, selectors.name),
                  city: text(card, selectors.city),
                  salary: /[\uE000-\uF8FF]/.test(salary) ? '' : salary,
                  jobUrl: href
                };
              }).filter(item => item.sourceJobId && (item.jobName || item.companyName));
            }
            """;

    private final BossCollectorService bossCollectorService;
    private final String channel;
    private final String executablePath;
    private final String startUrl;
    private final int navigationTimeoutMs;
    private final int cdpStartupTimeoutMs;
    private final int maxJobsPerCollect;
    private final Path sessionRoot;
    private final Path activeSessionMetadataFile;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Process edgeProcess;
    private Path sessionDirectory;
    private int cdpPort;
    private volatile boolean cdpReady;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    // 只记录浏览器生命周期诊断信息，不记录 Cookie、密码、验证码或页面正文。
    private volatile String lastEvent = "尚未启动";
    private volatile String lastEventAt = "";
    private volatile String lastNavigationUrl = "";
    private volatile boolean pageClosed;
    private volatile boolean browserDisconnected;
    // 只有用户主动点击“登录后建立连接”并通过一次登录检测后，才开放采集能力。
    private volatile boolean businessConnected;
    private volatile boolean restoreAttempted;
    private volatile boolean restoredManagedSession;
    private volatile String lastKnownTitle = "";

    public EmbeddedBrowserService(
            BossCollectorService bossCollectorService,
            @Value("${app.browser.embedded.channel:msedge}") String channel,
            @Value("${app.browser.embedded.executable-path:}") String executablePath,
            @Value("${app.browser.embedded.start-url:https://www.zhipin.com/web/geek/jobs}") String startUrl,
            @Value("${app.browser.embedded.navigation-timeout-ms:20000}") int navigationTimeoutMs,
            @Value("${app.browser.embedded.cdp-startup-timeout-ms:10000}") int cdpStartupTimeoutMs,
            @Value("${app.browser.embedded.max-jobs-per-collect:50}") int maxJobsPerCollect,
            @Value("${app.browser.embedded.session-root:runtime/browser-sessions}") String sessionRoot) {
        this.bossCollectorService = bossCollectorService;
        this.channel = channel;
        this.executablePath = executablePath;
        this.startUrl = startUrl;
        this.navigationTimeoutMs = navigationTimeoutMs;
        this.cdpStartupTimeoutMs = Math.max(1000, cdpStartupTimeoutMs);
        this.maxJobsPerCollect = Math.max(1, maxJobsPerCollect);
        this.sessionRoot = Path.of(sessionRoot).toAbsolutePath().normalize();
        this.activeSessionMetadataFile = this.sessionRoot.resolve("active-session.json").normalize();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
    }

    /**
     * 启动独立可见浏览器并打开 BOSS 页面，登录动作由用户手动完成。
     */
    public synchronized EmbeddedBrowserStatus start() {
        EmbeddedBrowserStatus current = status();
        if (current.running()) {
            return current;
        }
        if (!isBossUrl(startUrl)) {
            return EmbeddedBrowserStatus.stopped("独立 Edge 起始地址必须是 BOSS 页面");
        }
        closeResources();
        if (sessionDirectory != null) {
            return EmbeddedBrowserStatus.stopped("上一浏览器临时会话目录仍被占用，请稍后重试");
        }
        resetDiagnostics();
        try {
            Path browserExecutable = resolveBrowserExecutable();
            Files.createDirectories(sessionRoot);
            secureSessionDirectory(sessionRoot);
            cleanupOrphanSessionDirectories(null);
            sessionDirectory = Files.createTempDirectory(sessionRoot, "edge-").toAbsolutePath().normalize();
            secureSessionDirectory(sessionDirectory);
            cdpPort = reserveLoopbackPort();
            List<String> command = buildEdgeCommand(browserExecutable, sessionDirectory, cdpPort);
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
            edgeProcess = processBuilder.start();
            restoredManagedSession = false;
            recordEvent("普通 Edge 已启动，等待人工登录");
            cdpReady = waitForCdpEndpoint();
            if (cdpReady) {
                persistManagedSessionMetadata();
            }
            return status();
        } catch (Exception exception) {
            recordEvent("独立 Edge 启动失败");
            closeResources();
            return EmbeddedBrowserStatus.stopped("独立 Edge 启动失败：" + friendlyMessage(exception)
                    + "。请检查 application.yml 中的 Edge 路径配置", lastEvent, lastEventAt);
        }
    }

    /**
     * 在用户完成登录后重新检测页面，并确认是否可以建立业务连接。
     * 连接建立只代表页面状态满足采集前置条件，不会执行投递操作。
     */
    public synchronized EmbeddedBrowserStatus connect() {
        EmbeddedBrowserStatus current = status();
        if (!current.running()) {
            return current;
        }
        if ("SECURITY_CHECK_REQUIRED".equals(current.state())) {
            return current;
        }
        if (!cdpReady && !isCdpEndpointReady()) {
            return newStatus("BROWSER_STARTING", true, false, false, false, startUrl, "",
                    "Edge 调试端点尚未准备好，请稍后再建立连接");
        }
        cdpReady = true;
        if (businessConnected) {
            return status();
        }

        // 建立连接阶段只观察本地 CDP target，不接入 Playwright，不执行页面脚本。
        // 这样可以避免用户刚完成登录时因自动化客户端接管页面而触发连续重新加载。
        detachAutomationClient();
        CdpPageTarget target = waitForStableCdpPageTarget();
        if (target == null) {
            return newStatus("PAGE_UNAVAILABLE", true, false, false, false, lastNavigationUrl, lastKnownTitle,
                    "BOSS 页面尚未稳定，本次未接入自动化；请保持职位列表打开后重试");
        }
        String currentUrl = target.url();
        lastNavigationUrl = safeUrlForDiagnostics(currentUrl);
        lastKnownTitle = target.title();
        if (isAccessRestrictedPage(currentUrl) || isVerificationPage(currentUrl, target.title())) {
            return newStatus("SECURITY_CHECK_REQUIRED", true, true, false, false, currentUrl, target.title(),
                    "检测到安全验证，本次未接入自动化，请在独立 Edge 中人工处理");
        }
        if (isLoginPage(currentUrl)) {
            return newStatus("STARTED_NEEDS_LOGIN", true, true, false, false, currentUrl, target.title(),
                    "请先在独立 Edge 中完成人工登录，再打开职位列表建立连接");
        }
        if (!isJobCollectionPage(currentUrl)) {
            return newStatus("WRONG_PAGE", true, true, false, false, currentUrl, target.title(),
                    "请在独立 Edge 中打开 BOSS 职位列表页后再建立连接");
        }

        // 用户主动确认登录后只保存业务授权状态；真正采集时才短暂接入 Playwright。
        businessConnected = true;
        recordEvent("用户确认登录，静默业务连接已建立");
        return newStatus("CONNECTED", true, true, true, true, currentUrl, target.title(),
                "已建立静默连接；职位采集时才会短暂接入页面，连接阶段不会刷新 BOSS");
    }

    /**
     * 关闭本服务创建的浏览器实例。
     */
    public synchronized EmbeddedBrowserStatus stop() {
        closeResources();
        recordEvent("用户已关闭内置浏览器");
        return EmbeddedBrowserStatus.stopped("内置浏览器已关闭", lastEvent, lastEventAt);
    }

    /**
     * 读取浏览器进程和 BOSS 页面状态，不读取 Cookie、密码或验证码。
     */
    public synchronized EmbeddedBrowserStatus status() {
        restoreManagedSessionIfAvailable();
        if (!isManagedEdgeRunning()) {
            businessConnected = false;
            detachAutomationClient();
            return EmbeddedBrowserStatus.stopped("独立 Edge 尚未启动", lastEvent, lastEventAt);
        }
        if (!cdpReady) {
            cdpReady = isCdpEndpointReady();
            if (cdpReady) {
                persistManagedSessionMetadata();
            }
            if (!cdpReady) {
                return newStatus("BROWSER_STARTING", true, false, false, false, startUrl, "",
                        "普通 Edge 正在启动；登录期间系统不会连接或读取页面");
            }
        }
        CdpPageTarget cdpTarget = readCurrentCdpPageTarget();
        if (cdpTarget != null) {
            lastNavigationUrl = safeUrlForDiagnostics(cdpTarget.url());
            if (!cdpTarget.title().isBlank()) {
                lastKnownTitle = cdpTarget.title();
            }
            if (isAccessRestrictedPage(cdpTarget.url())) {
                disconnectAutomation();
                recordEventIfChanged("BOSS 已进入访问限制页面，自动采集已暂停");
                return newStatus("SECURITY_CHECK_REQUIRED", true, true, false, false,
                        cdpTarget.url(), cdpTarget.title(),
                        "BOSS 已进入访问限制页面，请在独立 Edge 中人工处理，恢复职位列表后重新建立连接");
            }
            if (isVerificationPage(cdpTarget.url(), cdpTarget.title())) {
                disconnectAutomation();
                return newStatus("SECURITY_CHECK_REQUIRED", true, true, false, false,
                        cdpTarget.url(), cdpTarget.title(),
                        "检测到安全验证，自动化连接已撤销，请人工处理完成后重试");
            }
            if (isLoginPage(cdpTarget.url())) {
                disconnectAutomation();
                return newStatus("STARTED_NEEDS_LOGIN", true, true, false, false,
                        cdpTarget.url(), cdpTarget.title(),
                        "请在独立 Edge 中完成人工登录，完成后再点击“登录后建立连接”");
            }
            if (businessConnected && isJobCollectionPage(cdpTarget.url())) {
                // 状态轮询只使用 CDP target 元数据，不保持 Playwright 长连接。
                return newStatus("READY", true, true, true, true, cdpTarget.url(), cdpTarget.title(),
                        "静默业务连接有效；采集时才会短暂接入页面");
            }
        }
        if (businessConnected && isBrowserPageAvailable()) {
            try {
                String connectedUrl = page.url();
                if (isJobCollectionPage(connectedUrl)) {
                    return newStatus("READY", true, true, true, true, connectedUrl, lastKnownTitle,
                            "短时自动化操作正在执行");
                }
            } catch (Exception ignored) {
                // 页面正在关闭时由下方逻辑撤销失效连接。
            }
        }
        if (businessConnected) {
            businessConnected = false;
            detachAutomationClient();
            recordEventIfChanged("BOSS 职位列表已离开，静默业务连接已撤销");
        }
        String currentUrl = cdpTarget == null ? startUrl : cdpTarget.url();
        String currentTitle = cdpTarget == null ? lastKnownTitle : cdpTarget.title();
        String message = restoredManagedSession
                ? "已恢复重启前的独立 Edge，会话和页面未刷新；请确认登录状态后点击“登录后建立连接”"
                : "普通 Edge 已启动：请先人工登录并打开职位列表，再点击“登录后建立连接”";
        return newStatus("STARTED_NEEDS_LOGIN", true, cdpTarget != null, false, false,
                currentUrl, currentTitle, message);
    }

    /**
     * 在已连接的独立 Edge 当前页面打开单条职位详情，不新建标签页、不点击投递。
     */
    public synchronized boolean openJobDetail(String jobUrl) {
        if (!isJobDetailUrl(jobUrl)) {
            return false;
        }
        EmbeddedBrowserStatus current = status();
        if (!current.readyForCollection()) {
            return false;
        }
        try {
            attachAutomationClient();
            // 该导航只在用户确认单条岗位后执行；完成后立即撤销 Playwright 接入。
            page.navigate(jobUrl, new Page.NavigateOptions().setTimeout(navigationTimeoutMs));
            return true;
        } catch (Exception navigationException) {
            recordEvent("职位详情导航未完成，已保留浏览器窗口供人工处理");
            return false;
        } finally {
            detachAutomationClient();
        }
    }

    /**
     * 获取当前内置浏览器页面的临时截图，用于管理端预览。
     * 截图只在内存中生成，不写入磁盘，也不读取 Cookie、密码或验证码。
     */
    public synchronized byte[] screenshot() {
        EmbeddedBrowserStatus current = status();
        if (!current.readyForCollection()) {
            return null;
        }
        try {
            attachAutomationClient();
            // 页面预览只响应用户主动操作，截图完成后立即撤销 Playwright 接入。
            return page.screenshot();
        } catch (Exception exception) {
            System.err.println("内置浏览器截图失败：" + friendlyMessage(exception));
            return null;
        } finally {
            detachAutomationClient();
        }
    }

    /**
     * 只采集当前 BOSS 列表页已加载的职位，不翻页、不点击投递按钮。
     */
    public synchronized EmbeddedBrowserCollectResult collect() {
        EmbeddedBrowserStatus before = status();
        if (!before.readyForCollection()) {
            return new EmbeddedBrowserCollectResult(before.state(), 0, null, before.message(), now());
        }
        boolean automationAlreadyAttached = isBrowserPageAvailable();
        CdpPageTarget stableTarget = automationAlreadyAttached ? null : waitForStableCdpPageTarget();
        if (!automationAlreadyAttached && (stableTarget == null || !isJobCollectionPage(stableTarget.url()))) {
            businessConnected = false;
            return collectionUnavailable("BOSS 职位页面尚未稳定，本次未接入页面，请等待后重新建立连接");
        }
        try {
            // 每次人工采集仅建立一次短连接并执行一次只读脚本，不在页面跳转期间循环重试。
            attachAutomationClient();
            Object raw = page.evaluate(EXTRACTION_SCRIPT);
            if (!(raw instanceof List<?> items)) {
                return structureChanged("无法识别 BOSS 职位列表结构，已暂停采集");
            }
            List<JobRecordRequest> jobs = toJobRequests(items);
            if (jobs.isEmpty()) {
                return collectionUnavailable("当前页面未识别到职位卡片，请确认列表已经加载完成后重试");
            }
            String capturedAt = now();
            BossCollectResult persisted = bossCollectorService.collect(new BossCollectRequest(jobs, capturedAt));
            return new EmbeddedBrowserCollectResult("READY", jobs.size(), persisted,
                    "职位已采集并完成本地筛选，未执行投递", capturedAt);
        } catch (Exception exception) {
            if (isTransientNavigationError(exception)) {
                businessConnected = false;
                recordEvent("采集时页面发生跳转，短连接已断开");
                return collectionUnavailable("BOSS 页面在采集瞬间发生跳转，已停止读取；请等待稳定后重新建立连接");
            }
            recordEvent("职位采集脚本执行失败，短连接已断开");
            return structureChanged("职位采集执行失败，未写入数据；请确认页面正常后重试");
        } finally {
            detachAutomationClient();
        }
    }

    /**
     * 将页面脚本结果转换为本地职位请求，只有完整结果才允许进入持久化。
     */
    private List<JobRecordRequest> toJobRequests(List<?> items) {
        List<JobRecordRequest> jobs = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String sourceJobId = value(map, "sourceJobId");
            String jobName = value(map, "jobName");
            String companyName = value(map, "companyName");
            if (sourceJobId.isBlank() || (jobName.isBlank() && companyName.isBlank())) {
                continue;
            }
            jobs.add(new JobRecordRequest("BOSS", sourceJobId, companyName, null, jobName, null,
                    value(map, "city"), sanitizeSalary(value(map, "salary")), value(map, "jobUrl"), null));
            if (jobs.size() >= maxJobsPerCollect) {
                break;
            }
        }
        return jobs;
    }

    /**
     * 应用退出时只断开进程内自动化客户端，保留独立 Edge 供服务重启后重新接管。
     */
    @PreDestroy
    public synchronized void destroy() {
        if (isCdpEndpointReady()) {
            persistManagedSessionMetadata();
        }
        disconnectAutomation();
    }

    private EmbeddedBrowserCollectResult structureChanged(String message) {
        return new EmbeddedBrowserCollectResult("PAGE_STRUCTURE_CHANGED", 0, null, message, now());
    }

    /**
     * 返回页面暂不可用状态，不暴露 Playwright 堆栈或本机路径。
     */
    private EmbeddedBrowserCollectResult collectionUnavailable(String message) {
        return new EmbeddedBrowserCollectResult("PAGE_UNAVAILABLE", 0, null, message, now());
    }

    private EmbeddedBrowserCollectResult collectionSecurityRequired(String message) {
        return new EmbeddedBrowserCollectResult("SECURITY_CHECK_REQUIRED", 0, null, message, now());
    }

    private boolean isJobDetailUrl(String url) {
        if (!isBossUrl(url)) {
            return false;
        }
        try {
            String path = URI.create(url).getPath();
            return path != null && path.toLowerCase().startsWith("/job_detail/");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isBossUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && host != null
                    && (host.equalsIgnoreCase(BOSS_HOST) || host.toLowerCase().endsWith("." + BOSS_HOST));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 采集前短暂建立 Playwright 连接；连接只在当前同步操作内存活。
     */
    private void attachAutomationClient() {
        if (isBrowserPageAvailable()) {
            return;
        }
        detachAutomationClient();
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().connectOverCDP(cdpEndpoint(),
                    new BrowserType.ConnectOverCDPOptions().setTimeout(navigationTimeoutMs));
            List<BrowserContext> contexts = browser.contexts();
            if (contexts.isEmpty()) {
                throw new IllegalStateException("已连接 Edge，但没有可用的浏览器上下文");
            }
            context = contexts.get(0);
            page = findActivePage();
            if (page == null || page.isClosed() || !isBossUrl(page.url())) {
                throw new IllegalStateException("未找到可用的 BOSS 页面");
            }
            page.setDefaultTimeout(Math.min(navigationTimeoutMs, 3000));
        } catch (Exception exception) {
            detachAutomationClient();
            throw exception;
        }
    }

    /**
     * 仅断开 Playwright 客户端，不关闭独立 Edge，也不撤销用户确认的静默业务连接。
     */
    private void detachAutomationClient() {
        try {
            if (browser != null) {
                browser.close();
            }
        } catch (Exception ignored) {
            // 短连接已经失效时无需重复处理。
        }
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception ignored) {
            // Playwright 驱动已经退出时无需重复处理。
        }
        page = null;
        context = null;
        browser = null;
        playwright = null;
    }

    /**
     * 通过本地 CDP 元数据等待同一职位页连续稳定出现，不接入页面执行上下文。
     */
    private CdpPageTarget waitForStableCdpPageTarget() {
        long timeoutMs = Math.max(600L, Math.min(3000L, navigationTimeoutMs));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        CdpPageTarget previous = null;
        int stableCount = 0;
        while (System.nanoTime() < deadline) {
            CdpPageTarget current = readCurrentCdpPageTarget();
            if (current != null) {
                if (previous != null && current.samePage(previous)) {
                    stableCount++;
                } else {
                    stableCount = 1;
                }
                previous = current;
                if (stableCount >= 3) {
                    return current;
                }
            } else {
                previous = current;
                stableCount = 0;
            }
            waitForPassiveTargetObservation();
        }
        return null;
    }

    private void waitForPassiveTargetObservation() {
        try {
            Thread.sleep(250L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 BOSS 页面稳定时被中断", exception);
        }
    }

    private boolean isJobCollectionPage(String url) {
        if (!isBossUrl(url)) {
            return false;
        }
        try {
            String path = URI.create(url).getPath();
            return path != null && path.toLowerCase(Locale.ROOT).startsWith("/web/geek/jobs");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 只检查浏览器和页面引用是否可用，不执行 DOM 读取。
     */
    private boolean isBrowserPageAvailable() {
        try {
            return browser != null && browser.isConnected() && page != null && !page.isClosed();
        } catch (Exception exception) {
            return false;
        }
    }

    private Page findActivePage() {
        if (context == null) {
            return null;
        }
        List<Page> pages;
        try {
            pages = context.pages();
        } catch (Exception exception) {
            return null;
        }
        for (int index = pages.size() - 1; index >= 0; index--) {
            Page candidate = pages.get(index);
            try {
                if (!candidate.isClosed() && isBossUrl(candidate.url())) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // 页面正在关闭时忽略该页面，继续检查其他页面。
            }
        }
        if (page != null) {
            try {
                if (!page.isClosed()) {
                    return page;
                }
            } catch (Exception ignored) {
                // 页面正在关闭时交由上层状态诊断处理。
            }
        }
        for (Page candidate : pages) {
            try {
                if (!candidate.isClosed()) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // 页面正在关闭时忽略该页面。
            }
        }
        return null;
    }

    /**
     * 使用新版个人入口标记优先确认登录，并在仍显示登录入口时拒绝建立业务连接。
     */
    boolean isLoginConfirmed(Map<?, ?> probe) {
        boolean authenticatedMarker = Boolean.TRUE.equals(probe.get("authenticatedMarker"));
        boolean loginMarker = Boolean.TRUE.equals(probe.get("loginMarker"));
        boolean loginPrompt = Boolean.TRUE.equals(probe.get("loginPrompt"));
        boolean loggedInText = Boolean.TRUE.equals(probe.get("loggedInText"));
        return !loginMarker && !loginPrompt && (authenticatedMarker || loggedInText);
    }
    /**
     * 判断 Playwright 是否因页面导航而暂时失去执行上下文。
     */
    boolean isTransientNavigationError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("execution context was destroyed")
                    || normalized.contains("most likely because of a navigation")
                    || normalized.contains("frame was detached")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 给 BOSS 的登录跳转留出短暂稳定时间，不刷新页面也不发起新的导航。
     */
    private void waitForNavigationToSettle() {
        try {
            Thread.sleep(COLLECTION_RETRY_INTERVAL_MS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 BOSS 页面稳定时被中断", exception);
        }
    }
    private boolean isVerificationPage(String url, String title) {
        String normalizedUrl = url == null ? "" : url.toLowerCase();
        String normalizedTitle = title == null ? "" : title.toLowerCase();
        return isAccessRestrictedPage(url) || normalizedUrl.contains("/verify") || normalizedUrl.contains("captcha")
                || normalizedUrl.contains("security-check") || normalizedTitle.contains("安全验证")
                || normalizedTitle.contains("验证码");
    }

    /**
     * 识别 BOSS 明确返回的访问限制页面，不尝试绕过或继续读取页面。
     */
    boolean isAccessRestrictedPage(String url) {
        if (!isBossUrl(url)) {
            return false;
        }
        String normalizedUrl = url.toLowerCase(Locale.ROOT);
        return normalizedUrl.contains("/403.html")
                || normalizedUrl.contains("/web/passport/zp/403")
                || normalizedUrl.contains("code=32");
    }

    /**
     * 薪资文本包含平台私有区字体字符时留空，避免将不可读乱码写入数据库。
     */
    String sanitizeSalary(String salary) {
        if (salary == null || salary.isBlank()) {
            return "";
        }
        return salary.codePoints().anyMatch(codePoint -> codePoint >= 0xE000 && codePoint <= 0xF8FF)
                ? "" : salary.trim();
    }

    private long collectionStabilityTimeoutMs() {
        return Math.max(800L, Math.min(COLLECTION_STABILITY_TIMEOUT_MS, navigationTimeoutMs));
    }

    private boolean isLoginPage(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String normalizedUrl = url.toLowerCase();
        return normalizedUrl.contains("/web/user") || normalizedUrl.contains("/passport/")
                || normalizedUrl.contains("ka=header-login");
    }

    /**
     * 为页面绑定只读生命周期监听，用于判断页面被清空、关闭或导航失败的原因。
     */
    private void attachPageListeners(Page observedPage) {
        // 新页面同样使用短操作超时，避免登录跳转时状态接口长时间占用请求线程。
        observedPage.setDefaultTimeout(Math.min(navigationTimeoutMs, 3000));
        observedPage.onClose(this::handlePageClosed);
        observedPage.onFrameNavigated(frame -> {
            try {
                if (frame.parentFrame() == null) {
                    String navigatedUrl = frame.url();
                    lastNavigationUrl = safeUrlForDiagnostics(navigatedUrl);
                    if (isBossUrl(navigatedUrl)) {
                        page = frame.page();
                        pageClosed = false;
                    }
                    // 正常的 BOSS 域内导航保留连接；进入登录、安全验证或外部页面时立即暂停业务连接。
                    if (!isBossUrl(navigatedUrl) || isVerificationPage(navigatedUrl, "") || isLoginPage(navigatedUrl)) {
                        businessConnected = false;
                        lastKnownTitle = "";
                    }
                    recordEvent("页面已导航：" + navigationKind(navigatedUrl));
                }
            } catch (Exception ignored) {
                // 页面关闭时忽略无法读取的导航事件。
            }
        });
        observedPage.onLoad(loadedPage -> recordEvent("页面加载完成"));
        observedPage.onPageError(error -> recordEvent("页面脚本错误，已暂停自动操作"));
        observedPage.onRequestFailed(request -> {
            if (request.isNavigationRequest()) {
                recordEvent("页面导航请求失败，已暂停自动操作");
            }
        });
    }

    /**
     * 主动断开自动化连接时忽略 Playwright 代理关闭事件，只记录真实业务连接期间的页面关闭。
     */
    void handlePageClosed(Page closedPage) {
        if (!businessConnected) {
            return;
        }
        if (closedPage == page) {
            pageClosed = true;
        }
        recordEvent("BOSS 页面已关闭");
    }
    /**
     * 创建包含生命周期诊断字段的状态对象。
     */
    private EmbeddedBrowserStatus newStatus(String state, boolean running, boolean bossPage,
                                             boolean loginValid, boolean readyForCollection,
                                             String currentUrl, String title, String message) {
        // 当前地址可能包含平台临时参数，只向管理端返回协议、主机和路径。
        String safeCurrentUrl = safeUrlForDiagnostics(currentUrl);
        return new EmbeddedBrowserStatus(state, running, bossPage, loginValid, readyForCollection,
                safeCurrentUrl, title == null ? "" : title, message,
                lastEvent, lastEventAt);
    }

    /**
     * 清理一次新的浏览器会话的诊断状态。
     */
    private void resetDiagnostics() {
        lastEvent = "正在创建浏览器会话";
        lastEventAt = now();
        lastNavigationUrl = "";
        pageClosed = false;
        browserDisconnected = false;
        businessConnected = false;
        lastKnownTitle = "";
    }

    /**
     * 记录生命周期事件并输出简短日志，便于定位 BOSS 页面自动变空白的原因。
     */
    private void recordEvent(String event) {
        lastEvent = event;
        lastEventAt = now();
        System.err.println("[内置浏览器] " + event);
    }

    private void recordEventIfChanged(String event) {
        if (!event.equals(lastEvent)) {
            recordEvent(event);
        }
    }

    /**
     * 诊断字段只保留地址主干，避免把查询参数中的临时令牌写入内存状态或日志。
     */
    private String safeUrlForDiagnostics(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getPath() == null ? "" : uri.getPath();
            return (uri.getScheme() == null ? "" : uri.getScheme() + "://")
                    + (uri.getRawAuthority() == null ? "" : uri.getRawAuthority()) + path;
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    /**
     * 只返回导航地址的有限分类，不把地址参数写入诊断信息。
     */
    private String navigationKind(String url) {
        if (url == null || url.isBlank() || "about:blank".equalsIgnoreCase(url)) {
            return "空白页";
        }
        if (isVerificationPage(url, "")) {
            return "安全验证页";
        }
        return isBossUrl(url) ? "BOSS 页面" : "非 BOSS 页面";
    }

    private String value(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String now() {
        return OffsetDateTime.now().toString();
    }

    private String friendlyMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    /**
     * 构造普通 Edge 启动命令，只保留本地 CDP、独立会话目录和基础启动参数。
     */
    List<String> buildEdgeCommand(Path browserExecutable, Path profileDirectory, int port) {
        return List.of(
                browserExecutable.toString(),
                "--remote-debugging-address=127.0.0.1",
                "--remote-debugging-port=" + port,
                "--user-data-dir=" + profileDirectory,
                "--no-first-run",
                "--no-default-browser-check",
                startUrl
        );
    }

    private Path resolveBrowserExecutable() throws IOException {
        if (executablePath != null && !executablePath.isBlank()) {
            Path configured = Path.of(executablePath).toAbsolutePath().normalize();
            if (Files.isRegularFile(configured)) {
                return configured;
            }
            throw new IOException("配置的浏览器文件不存在：" + configured);
        }
        List<Path> candidates = new ArrayList<>();
        String localAppData = System.getenv("LOCALAPPDATA");
        if ("chrome".equalsIgnoreCase(channel)) {
            candidates.add(Path.of("C:/Program Files/Google/Chrome/Application/chrome.exe"));
            candidates.add(Path.of("C:/Program Files (x86)/Google/Chrome/Application/chrome.exe"));
            if (localAppData != null && !localAppData.isBlank()) {
                candidates.add(Path.of(localAppData, "Google", "Chrome", "Application", "chrome.exe"));
            }
        } else {
            candidates.add(Path.of("C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"));
            candidates.add(Path.of("C:/Program Files/Microsoft/Edge/Application/msedge.exe"));
            if (localAppData != null && !localAppData.isBlank()) {
                candidates.add(Path.of(localAppData, "Microsoft", "Edge", "Application", "msedge.exe"));
            }
        }
        return candidates.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IOException("未找到本机 Edge 浏览器，请配置 executable-path"));
    }

    private int reserveLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private boolean waitForCdpEndpoint() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cdpStartupTimeoutMs);
        // Edge 在部分 Windows 环境会先退出包装进程再拉起真实进程，因此等待只以 CDP 端点为准。
        while (System.nanoTime() < deadline) {
            if (isCdpEndpointReady()) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 从本地元数据恢复重启前的受管 Edge。这里只恢复端口和临时目录，不读取登录数据或页面正文。
     */
    private void restoreManagedSessionIfAvailable() {
        if (restoreAttempted || sessionDirectory != null || cdpPort > 0) {
            return;
        }
        restoreAttempted = true;
        Path activeDirectory = null;
        if (Files.isRegularFile(activeSessionMetadataFile, LinkOption.NOFOLLOW_LINKS)) {
            try {
                BrowserSessionMetadata metadata = objectMapper.readValue(
                        activeSessionMetadataFile.toFile(), BrowserSessionMetadata.class);
                Path restoredDirectory = Path.of(metadata.sessionDirectory()).toAbsolutePath().normalize();
                if (!isSafeSessionDirectory(restoredDirectory)
                        || !Files.isDirectory(restoredDirectory, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(restoredDirectory)
                        || metadata.cdpPort() <= 0
                        || metadata.cdpPort() > 65535) {
                    deleteManagedSessionMetadata();
                } else {
                    sessionDirectory = restoredDirectory;
                    cdpPort = metadata.cdpPort();
                    if (!isCdpEndpointReady()) {
                        sessionDirectory = null;
                        cdpPort = 0;
                        cdpReady = false;
                        deleteManagedSessionMetadata();
                    } else {
                        edgeProcess = null;
                        cdpReady = true;
                        businessConnected = false;
                        restoredManagedSession = true;
                        pageClosed = false;
                        browserDisconnected = false;
                        activeDirectory = restoredDirectory;
                        secureSessionDirectory(restoredDirectory);
                        recordEventIfChanged("已恢复重启前的独立 Edge，会话未刷新");
                    }
                }
            } catch (Exception exception) {
                sessionDirectory = null;
                cdpPort = 0;
                cdpReady = false;
                deleteManagedSessionMetadata();
            }
        }
        cleanupOrphanSessionDirectories(activeDirectory);
    }

    /**
     * 持久化重新接管所需的最小元数据，不保存 Cookie、密码、验证码或页面内容。
     */
    private void persistManagedSessionMetadata() {
        if (!isSafeSessionDirectory(sessionDirectory) || cdpPort <= 0 || cdpPort > 65535) {
            return;
        }
        try {
            Files.createDirectories(sessionRoot);
            Path temporaryFile = sessionRoot.resolve("active-session.json.tmp").normalize();
            objectMapper.writeValue(temporaryFile.toFile(),
                    new BrowserSessionMetadata(cdpPort, sessionDirectory.toString()));
            try {
                Files.move(temporaryFile, activeSessionMetadataFile,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException exception) {
                Files.move(temporaryFile, activeSessionMetadataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            System.err.println("[独立 Edge] 无法保存浏览器接管元数据：" + friendlyMessage(exception));
        }
    }

    private void deleteManagedSessionMetadata() {
        try {
            Files.deleteIfExists(activeSessionMetadataFile);
            Files.deleteIfExists(sessionRoot.resolve("active-session.json.tmp").normalize());
        } catch (IOException exception) {
            System.err.println("[独立 Edge] 无法删除浏览器接管元数据：" + friendlyMessage(exception));
        }
    }

    private boolean isSafeSessionDirectory(Path directory) {
        if (directory == null) {
            return false;
        }
        Path target = directory.toAbsolutePath().normalize();
        Path fileName = target.getFileName();
        return target.getParent() != null
                && target.getParent().equals(sessionRoot)
                && fileName != null
                && fileName.toString().startsWith("edge-");
    }

    /**
     * 在支持 Windows ACL 的文件系统上，将会话目录限制为仅当前目录所有者可完全访问。
     */
    boolean secureSessionDirectory(Path directory) {
        if (directory == null) {
            return false;
        }
        Path target = directory.toAbsolutePath().normalize();
        if (!target.equals(sessionRoot) && !isSafeSessionDirectory(target)) {
            System.err.println("[独立 Edge] 拒绝修改超出会话范围的目录权限");
            return false;
        }
        try {
            if (Files.isSymbolicLink(target)) {
                return false;
            }
            AclFileAttributeView view = Files.getFileAttributeView(
                    target, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                return false;
            }
            UserPrincipal owner = Files.getOwner(target, LinkOption.NOFOLLOW_LINKS);
            if (!disableInheritedAcl(target)) {
                return false;
            }
            AclEntry ownerAccess = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(Set.of(AclEntryPermission.values()))
                    .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                    .build();
            view.setAcl(List.of(ownerAccess));
            return view.getAcl().size() == 1 && owner.equals(view.getAcl().get(0).principal());
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            System.err.println("[独立 Edge] 当前文件系统无法应用会话目录 ACL，已继续使用本机目录边界保护");
            return false;
        }
    }

    /**
     * 只清理会话根目录下无浏览器进程关联的 edge-* 直接子目录，不跟随符号链接。
     */
    /**
     * Windows NIO 设置 ACL 时不会自动关闭继承，必须先删除继承项，避免其他本机账户继续读取会话目录。
     */
    private boolean disableInheritedAcl(Path target) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return true;
        }
        try {
            Process process = new ProcessBuilder("icacls.exe", target.toString(), "/inheritance:r")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException exception) {
            System.err.println("[独立 Edge] 无法关闭会话目录 ACL 继承：" + friendlyMessage(exception));
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    private void cleanupOrphanSessionDirectories(Path activeDirectory) {
        if (!Files.isDirectory(sessionRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(sessionRoot)) {
            return;
        }
        int cleaned = 0;
        try (var children = Files.list(sessionRoot)) {
            for (Path child : children.toList()) {
                Path target = child.toAbsolutePath().normalize();
                if (!isSafeSessionDirectory(target)
                        || Files.isSymbolicLink(target)
                        || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                        || (activeDirectory != null && target.equals(activeDirectory.toAbsolutePath().normalize()))
                        || !findManagedEdgeProcesses(target).isEmpty()) {
                    continue;
                }
                if (deleteSessionDirectory(target)) {
                    cleaned++;
                }
            }
        } catch (IOException exception) {
            System.err.println("[独立 Edge] 无法完成孤儿会话目录审计：" + friendlyMessage(exception));
        }
        if (cleaned > 0) {
            System.out.println("[独立 Edge] 已安全清理 " + cleaned + " 个孤儿会话目录");
        }
    }

    private boolean isCdpEndpointReady() {
        if (cdpPort <= 0) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cdpEndpoint() + "/json/version"))
                    .timeout(Duration.ofSeconds(1))
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * 仅读取本地 CDP target 的地址和标题，用于纠正失效 Playwright 页面引用造成的状态误报。
     */
    private CdpPageTarget readCurrentCdpPageTarget() {
        if (cdpPort <= 0) {
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cdpEndpoint() + "/json/list"))
                    .timeout(Duration.ofSeconds(1))
                    .header("Cache-Control", "no-cache")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            List<Map<String, Object>> targets = objectMapper.readValue(response.body(), new TypeReference<>() {
            });
            for (Map<String, Object> target : targets) {
                if (!"page".equals(String.valueOf(target.get("type")))) {
                    continue;
                }
                String url = String.valueOf(target.getOrDefault("url", ""));
                if (isBossUrl(url)) {
                    return new CdpPageTarget(url, String.valueOf(target.getOrDefault("title", "")),
                            String.valueOf(target.getOrDefault("id", "")));
                }
            }
            return null;
        } catch (Exception exception) {
            return null;
        }
    }

    private String cdpEndpoint() {
        return "http://127.0.0.1:" + cdpPort;
    }

    private boolean isEdgeProcessAlive() {
        try {
            return edgeProcess != null && edgeProcess.isAlive();
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * Edge 可能在 Windows 兼容层启动后更换主进程，因此运行状态同时参考进程句柄和本地 CDP 端点。
     */
    private boolean isManagedEdgeRunning() {
        return isEdgeProcessAlive() || isCdpEndpointReady();
    }

    private void disconnectAutomation() {
        // 撤销用户确认状态后再断开短时自动化客户端，独立 Edge 继续保留。
        businessConnected = false;
        detachAutomationClient();
    }

    private void terminateEdgeProcess() {
        try {
            List<ProcessHandle> managedProcesses = findManagedEdgeProcesses();
            // 先结束带有本次临时会话目录标识的 Edge 子进程，避免兼容层重启后遗漏真实主进程。
            managedProcesses.stream()
                    .filter(ProcessHandle::isAlive)
                    .forEach(ProcessHandle::destroy);
            if (edgeProcess != null && edgeProcess.isAlive()) {
                edgeProcess.destroy();
            }
            waitForManagedProcessesToExit(managedProcesses, 3000);
            managedProcesses.stream()
                    .filter(ProcessHandle::isAlive)
                    .forEach(ProcessHandle::destroyForcibly);
            if (edgeProcess != null && edgeProcess.isAlive()) {
                edgeProcess.destroyForcibly();
            }
            waitForManagedProcessesToExit(managedProcesses, 3000);
        } catch (Exception ignored) {
            // 停止阶段忽略进程已经退出或无法再次终止的异常。
        } finally {
            edgeProcess = null;
            cdpPort = 0;
            cdpReady = false;
        }
    }

    /**
     * 只查找命令行中包含本次临时会话目录的 Edge/Chrome 进程，避免影响用户自己的浏览器窗口。
     */
    private List<ProcessHandle> findManagedEdgeProcesses() {
        return findManagedEdgeProcesses(sessionDirectory);
    }

    private List<ProcessHandle> findManagedEdgeProcesses(Path directory) {
        if (directory == null || !isSafeSessionDirectory(directory)) {
            return List.of();
        }
        String sessionMarker = directory.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        try (var processes = ProcessHandle.allProcesses()) {
            return processes.filter(process -> isManagedBrowserProcess(process, sessionMarker)).toList();
        }
    }

    private boolean isManagedBrowserProcess(ProcessHandle process, String sessionMarker) {
        ProcessHandle.Info info = process.info();
        String command = info.command().orElse("").toLowerCase(Locale.ROOT);
        if (!command.endsWith("msedge.exe") && !command.endsWith("chrome.exe")) {
            return false;
        }
        String commandLine = info.commandLine().orElseGet(() -> String.join(" ", info.arguments().orElse(new String[0])));
        return commandLine.toLowerCase(Locale.ROOT).contains(sessionMarker);
    }

    private void waitForManagedProcessesToExit(List<ProcessHandle> processes, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline && processes.stream().anyMatch(ProcessHandle::isAlive)) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void cleanupSessionDirectory() {
        if (sessionDirectory == null) {
            return;
        }
        Path target = sessionDirectory.toAbsolutePath().normalize();
        if (!isSafeSessionDirectory(target)) {
            System.err.println("[独立 Edge] 拒绝清理超出临时会话根目录的路径");
            return;
        }
        if (deleteSessionDirectory(target)) {
            sessionDirectory = null;
        } else {
            System.err.println("[独立 Edge] 临时会话目录仍被占用，将在下次启动前继续清理");
        }
    }

    private boolean deleteSessionDirectory(Path target) {
        if (!isSafeSessionDirectory(target) || Files.isSymbolicLink(target)) {
            return false;
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    try (var paths = Files.walk(target)) {
                        paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException exception) {
                                throw new SessionCleanupException(exception);
                            }
                        });
                    }
                }
                return true;
            } catch (IOException | SessionCleanupException exception) {
                try {
                    Thread.sleep(250L * (attempt + 1));
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private void closeResources() {
        disconnectAutomation();
        terminateEdgeProcess();
        cleanupSessionDirectory();
        deleteManagedSessionMetadata();
        restoreAttempted = true;
        restoredManagedSession = false;
        pageClosed = false;
        browserDisconnected = false;
        lastKnownTitle = "";
    }

    /**
     * 将流式删除中的受检异常包装为运行时异常，便于统一执行有限次数重试。
     */
    private static class SessionCleanupException extends RuntimeException {
        private SessionCleanupException(IOException cause) {
            super(cause);
        }
    }

    private record CdpPageTarget(String url, String title, String targetId) {
        private boolean samePage(CdpPageTarget other) {
            return other != null
                    && targetId.equals(other.targetId)
                    && url.equals(other.url)
                    && title.equals(other.title);
        }
    }

    private record BrowserSessionMetadata(int cdpPort, String sessionDirectory) {
    }
}


