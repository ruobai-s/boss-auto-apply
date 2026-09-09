let policy = null;
let currentQueue = [];
let embeddedStatusTimer = null;
let outsourcingRuleConfig = {excludedCompanies: [], conditions: []};
const maxCities = 5;
const maxOutsourcingRules = 100;
const outsourcingFieldOptions = [
    ["COMPANY_NAME", "公司名称"],
    ["COMPANY_INTRODUCTION", "公司简介"],
    ["JOB_NAME", "职位名称"],
    ["JOB_DESCRIPTION", "职位描述"],
    ["ALL_TEXT", "全部文本"]
];
const $ = (id) => document.getElementById(id);

const adminTokenKey = "boss-auto-apply-admin-token";

// 管理令牌只保存在当前标签页会话中，关闭标签页后自动清除。
function securityHeaders(headers = {}) {
    const token = sessionStorage.getItem(adminTokenKey);
    return {
        "Content-Type": "application/json",
        "X-Boss-Requested-With": "BossAutoApply",
        ...(token ? {"X-Boss-Admin-Token": token} : {}),
        ...headers
    };
}

async function secureFetch(url, options = {}) {
    return fetch(url, {...options, headers: securityHeaders(options.headers || {})});
}

async function requestJson(url, options = {}, retried = false) {
    const response = await secureFetch(url, options);
    const text = await response.text();
    let data = {};
    if (text) {
        try {
            data = JSON.parse(text);
        } catch (error) {
            data = {message: text};
        }
    }
    if (response.status === 401 && data.code === "ADMIN_TOKEN_REQUIRED" && !retried) {
        const token = window.prompt("当前通过局域网访问，请输入本地管理端令牌：");
        if (token && token.trim()) {
            sessionStorage.setItem(adminTokenKey, token.trim());
            return requestJson(url, options, true);
        }
    }
    if (!response.ok) throw new Error(data.message || "请求失败");
    return data;
}

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
}

function localDateString() {
    const now = new Date();
    const month = String(now.getMonth() + 1).padStart(2, "0");
    const day = String(now.getDate()).padStart(2, "0");
    return `${now.getFullYear()}-${month}-${day}`;
}

// 只允许打开 HTTP/HTTPS 职位链接，避免采集内容被当作脚本执行。
function safeHttpUrl(value) {
    try {
        const url = new URL(String(value ?? ""), window.location.origin);
        return url.protocol === "http:" || url.protocol === "https:" ? url.href : "";
    } catch (error) {
        return "";
    }
}

function renderCities() {
    const list = $("city-list");
    list.innerHTML = "";
    policy.cities.forEach((city, index) => {
        const row = document.createElement("div");
        row.className = "city-row";
        row.innerHTML = `
            <span class="priority">${index + 1}</span>
            <input data-field="city" data-index="${index}" value="${escapeHtml(city.city)}" placeholder="城市">
            <input data-field="ratio" data-index="${index}" type="number" min="0.1" max="100" step="0.1" value="${city.ratio}">
            <label class="check-label"><input data-field="enabled" data-index="${index}" type="checkbox" ${city.enabled ? "checked" : ""}> 启用</label>
            <label class="check-label"><input data-field="allowQuotaTransfer" data-index="${index}" type="checkbox" ${city.allowQuotaTransfer ? "checked" : ""}> 允许补量</label>
            <button class="remove-city" data-remove="${index}" title="删除城市">×</button>`;
        list.appendChild(row);
    });
    list.querySelectorAll("[data-field]").forEach((element) => {
        element.addEventListener("input", updatePolicyFromForm);
        element.addEventListener("change", updatePolicyFromForm);
    });
    list.querySelectorAll("[data-remove]").forEach((element) => element.addEventListener("click", () => {
        policy.cities.splice(Number(element.dataset.remove), 1);
        policy.cities.forEach((city, index) => city.priority = index + 1);
        renderCities();
    }));
    updateRatioSummary();
}

function updatePolicyFromForm() {
    policy.dailyTotal = Number($("daily-total").value);
    document.querySelectorAll("[data-field]").forEach((element) => {
        const city = policy.cities[Number(element.dataset.index)];
        if (!city) return;
        if (element.dataset.field === "city") city.city = element.value;
        if (element.dataset.field === "ratio") city.ratio = Number(element.value);
        if (element.dataset.field === "enabled") city.enabled = element.checked;
        if (element.dataset.field === "allowQuotaTransfer") city.allowQuotaTransfer = element.checked;
    });
    updateRatioSummary();
}

function updateRatioSummary() {
    const total = policy.cities.filter((city) => city.enabled).reduce((sum, city) => sum + Number(city.ratio || 0), 0);
    $("ratio-total").textContent = `${total.toFixed(1).replace(".0", "")}%`;
    $("ratio-total").classList.toggle("ratio-invalid", Math.abs(total - 100) > 0.001);
    $("metric-total").textContent = policy.dailyTotal || 0;
}

function renderQuota(quota) {
    $("quota-list").innerHTML = quota.map((item) => `
        <div class="quota-item">
            <span class="quota-name">${item.priority}. ${escapeHtml(item.city)} · ${item.ratio}%</span>
            <strong class="quota-number">${item.plannedCount}个</strong>
            <div class="quota-bar"><span style="width:${item.ratio}%"></span></div>
        </div>`).join("");
}

async function preview() {
    updatePolicyFromForm();
    try {
        const quota = await requestJson("/api/policy/preview", {method: "POST", body: JSON.stringify(policy)});
        renderQuota(quota);
        $("policy-message").textContent = "额度计算成功，城市计划合计为 " + quota.reduce((sum, item) => sum + item.plannedCount, 0) + " 个。";
    } catch (error) {
        $("policy-message").textContent = error.message;
    }
}

async function save() {
    updatePolicyFromForm();
    try {
        policy = await requestJson("/api/policy", {method: "POST", body: JSON.stringify(policy)});
        $("policy-message").textContent = "策略已保存到本地 SQLite，重启服务后仍会保留。";
        await preview();
    } catch (error) {
        $("policy-message").textContent = error.message;
    }
}

function ruleSelectOptions(options, selected) {
    return options.map(([value, label]) =>
        `<option value="${value}" ${value === selected ? "selected" : ""}>${label}</option>`
    ).join("");
}

function updateOutsourcingRulesFromForm() {
    outsourcingRuleConfig.excludedCompanies = $("excluded-companies").value
        .split(/\r?\n/)
        .map((value) => value.trim())
        .filter(Boolean);
    document.querySelectorAll("[data-condition-index]").forEach((row) => {
        const condition = outsourcingRuleConfig.conditions[Number(row.dataset.conditionIndex)];
        if (!condition) return;
        condition.field = row.querySelector('[data-rule-field="field"]').value;
        condition.matchType = row.querySelector('[data-rule-field="matchType"]').value;
        condition.keyword = row.querySelector('[data-rule-field="keyword"]').value;
        condition.action = row.querySelector('[data-rule-field="action"]').value;
        condition.enabled = row.querySelector('[data-rule-field="enabled"]').checked;
    });
}

function renderOutsourcingConditions() {
    const list = $("outsourcing-condition-list");
    if (!outsourcingRuleConfig.conditions.length) {
        list.innerHTML = '<p class="empty-state">暂无自定义条件，可点击“添加条件”。</p>';
        return;
    }
    list.innerHTML = outsourcingRuleConfig.conditions.map((condition, index) => `
        <div class="condition-row" data-condition-index="${index}">
            <select data-rule-field="field" aria-label="检查字段">
                ${ruleSelectOptions(outsourcingFieldOptions, condition.field)}
            </select>
            <select data-rule-field="matchType" aria-label="匹配方式">
                ${ruleSelectOptions([["CONTAINS", "包含"], ["EQUALS", "完全等于"]], condition.matchType)}
            </select>
            <input data-rule-field="keyword" maxlength="120" value="${escapeHtml(condition.keyword)}" placeholder="匹配内容">
            <select data-rule-field="action" aria-label="处理动作">
                ${ruleSelectOptions([["EXCLUDE", "自动排除"], ["REVIEW", "人工复核"]], condition.action)}
            </select>
            <label class="check-label"><input data-rule-field="enabled" type="checkbox" ${condition.enabled ? "checked" : ""}> 启用</label>
            <button class="remove-condition" data-remove-condition="${index}" type="button" title="删除条件">×</button>
        </div>
    `).join("");
    list.querySelectorAll("input, select").forEach((element) => {
        element.addEventListener("input", updateOutsourcingRulesFromForm);
        element.addEventListener("change", updateOutsourcingRulesFromForm);
    });
    list.querySelectorAll("[data-remove-condition]").forEach((button) => button.addEventListener("click", () => {
        updateOutsourcingRulesFromForm();
        outsourcingRuleConfig.conditions.splice(Number(button.dataset.removeCondition), 1);
        renderOutsourcingConditions();
    }));
}

async function loadOutsourcingRules() {
    outsourcingRuleConfig = await requestJson("/api/filter/rules");
    outsourcingRuleConfig.excludedCompanies ||= [];
    outsourcingRuleConfig.conditions ||= [];
    $("excluded-companies").value = outsourcingRuleConfig.excludedCompanies.join("\n");
    renderOutsourcingConditions();
}

async function saveOutsourcingRules() {
    const message = $("outsourcing-rule-message");
    updateOutsourcingRulesFromForm();
    if (outsourcingRuleConfig.excludedCompanies.length > maxOutsourcingRules) {
        message.textContent = "最多配置100家公司";
        return;
    }
    if (outsourcingRuleConfig.conditions.length > maxOutsourcingRules) {
        message.textContent = "最多配置100条自定义条件";
        return;
    }
    if (outsourcingRuleConfig.conditions.some((condition) => !condition.keyword.trim())) {
        message.textContent = "自定义条件的匹配内容不能为空";
        return;
    }
    try {
        const result = await requestJson("/api/filter/rules", {
            method: "POST",
            body: JSON.stringify(outsourcingRuleConfig)
        });
        outsourcingRuleConfig = result.config;
        $("excluded-companies").value = outsourcingRuleConfig.excludedCompanies.join("\n");
        renderOutsourcingConditions();
        message.textContent = `规则已保存，已重新检查 ${result.reevaluatedJobs} 个本地职位。`;
        await loadJobs();
        await loadQueue();
    } catch (error) {
        message.textContent = error.message;
    }
}

async function checkOutsourcing() {
    const result = await requestJson("/api/filter/outsourcing", {
        method: "POST",
        body: JSON.stringify({
            companyName: $("company-name").value,
            companyIntroduction: $("company-intro").value,
            jobName: $("job-name").value,
            jobDescription: $("job-description").value
        })
    });
    const view = $("outsourcing-result");
    view.className = "result-text " + (result.excluded ? "result-danger" : result.manualReview ? "result-warning" : "result-normal");
    view.textContent = `${result.level}：${result.reason}${result.matchedKeyword ? `（命中：${result.matchedKeyword}）` : ""}`;
}

function statusLabel(status) {
    const labels = {
        DISCOVERED: "待筛选",
        WAIT_CONFIRM: "待人工复核",
        EXCLUDED_OUTSOURCING: "已排除外包",
        APPLIED: "已投递",
        FAILED: "投递失败",
        UNKNOWN: "结果未知"
    };
    return labels[status] || status;
}

function statusClass(status) {
    if (status === "APPLIED") return "job-status status-success";
    if (status === "EXCLUDED_OUTSOURCING" || status === "FAILED") return "job-status status-danger";
    if (status === "WAIT_CONFIRM" || status === "UNKNOWN") return "job-status status-review";
    return "job-status";
}

// 将连接器状态转换为用户可执行的处理提示，不展示敏感页面内容。
function embeddedStatusHint(embedded) {
    const hints = {
        BROWSER_DISCONNECTED: "浏览器进程已断开，请重新启动连接器。",
        PAGE_UNAVAILABLE: "BOSS 页面不可用，请检查 Edge 窗口；系统不会自动刷新或反复重启。",
        SECURITY_CHECK_REQUIRED: "BOSS 页面可能触发安全验证，请在 Edge 窗口中人工处理后再继续。",
        WRONG_PAGE: "请在 Edge 窗口中回到 BOSS 职位列表页面。",
        STARTED_NEEDS_LOGIN: "请在 Edge 窗口中手动完成 BOSS 登录，完成后点击“登录后建立连接”。",
        READY: "已检测到登录，可进行只读职位采集。",
        CONNECTED: "连接已建立，可进行只读职位采集。"
    };
    return hints[embedded.state] || embedded.message || "";
}

async function loadJobs() {
    const page = await requestJson("/api/jobs?page=0&size=50");
    const jobs = page.items || [];
    $("metric-excluded").textContent = page.outsourcingExcluded || 0;
    $("metric-review").textContent = page.outsourcingManualReview || 0;
    $("metric-applied").textContent = page.applied || 0;

    const list = $("job-list");
    if (!jobs.length) {
        list.innerHTML = '<p class="empty-state">暂无职位记录，请先导入职位快照。</p>';
        return;
    }
    list.innerHTML = jobs.slice(0, 20).map((job) => {
        const jobUrl = safeHttpUrl(job.jobUrl);
        return `
        <article class="job-item">
            <div class="job-main">
                <strong>${escapeHtml(job.jobName || "未命名职位")}</strong>
                <span>${escapeHtml(job.companyName || "未知公司")} · ${escapeHtml(job.city || "城市未知")} · ${escapeHtml(job.salary || "薪资面议")}</span>
                <small>${escapeHtml(job.outsourcingReason || "未执行外包判断")}${job.outsourcingKeyword ? ` · 命中：${escapeHtml(job.outsourcingKeyword)}` : ""}</small>
                ${jobUrl ? `<a href="${escapeHtml(jobUrl)}" target="_blank" rel="noopener noreferrer">查看 BOSS 职位详情</a>` : ""}
            </div>
            <span class="${statusClass(job.applyStatus)}">${escapeHtml(statusLabel(job.applyStatus))}</span>
        </article>`;
    }).join("");
}

function queueStatusLabel(status) {
    return {QUEUED: "待人工确认", APPROVED: "已确认", REJECTED: "已拒绝", APPLIED: "已投递"}[status] || status;
}

function renderQueue(items) {
    currentQueue = items || [];
    $("queue-count").textContent = `${currentQueue.length} 个`;
    const list = $("queue-list");
    if (!currentQueue.length) {
        list.innerHTML = '<p class="empty-state">当前日期没有符合策略的候选岗位。</p>';
        return;
    }
    list.innerHTML = currentQueue.map((item) => {
        const job = item.job || {};
        const jobUrl = safeHttpUrl(job.jobUrl);
        const selector = item.queueStatus === "QUEUED"
            ? `<label class="queue-check"><input type="checkbox" data-queue-id="${item.queueId}"> 选择</label>`
            : `<span class="queue-state">${escapeHtml(queueStatusLabel(item.queueStatus))}</span>`;
        const applyAction = item.queueStatus === "APPROVED"
            ? `<button class="button secondary compact" data-prepare-apply="${item.queueId}">准备单条投递</button>` : "";
        return `<article class="queue-item">
            <div class="queue-item-main">
                <div class="queue-item-title"><strong>${escapeHtml(job.jobName || "未命名职位")}</strong><span class="queue-rank">#${item.queueRank}</span></div>
                <span>${escapeHtml(job.companyName || "未知公司")} · ${escapeHtml(job.city || "城市未知")} · ${escapeHtml(job.salary || "薪资面议")}</span>
                <small>计划城市：${escapeHtml(item.quotaCity)} · ${item.allocationType === "TRANSFER" ? "补量候选" : "主额度"} · ${escapeHtml(job.outsourcingReason || "已通过外包筛选")}</small>
                ${jobUrl ? `<a href="${escapeHtml(jobUrl)}" target="_blank" rel="noopener noreferrer">查看职位详情</a>` : ""}
            </div>
            <div class="queue-item-actions">${selector}${applyAction}</div>
        </article>`;
    }).join("");
    list.querySelectorAll("[data-prepare-apply]").forEach((button) => button.addEventListener("click", () => {
        prepareSingleApply(Number(button.dataset.prepareApply)).catch((error) => {
            $("queue-message").textContent = error.message;
        });
    }));
}

function selectedQueueIds() {
    return [...document.querySelectorAll("[data-queue-id]:checked")].map((element) => Number(element.dataset.queueId));
}

async function loadQueue() {
    const plannedDate = $("planned-date").value || localDateString();
    const items = await requestJson(`/api/queue?plannedDate=${encodeURIComponent(plannedDate)}`);
    renderQueue(items);
}

async function rebuildQueue() {
    const plannedDate = $("planned-date").value || localDateString();
    try {
        const items = await requestJson(`/api/queue/rebuild?plannedDate=${encodeURIComponent(plannedDate)}`, {method: "POST"});
        renderQueue(items);
        $("queue-message").textContent = `候选队列已生成，共 ${items.length} 个；未执行任何投递。`;
    } catch (error) {
        $("queue-message").textContent = error.message;
    }
}

async function confirmQueue() {
    const ids = selectedQueueIds();
    if (!ids.length) {
        $("queue-message").textContent = "请至少选择一个待人工确认岗位。";
        return;
    }
    if (!window.confirm(`确定确认 ${ids.length} 个岗位吗？确认后仍需要逐条准备投递。`)) return;
    try {
        const confirmation = await requestJson("/api/queue/confirmation-token", {
            method: "POST",
            body: JSON.stringify({queueIds: ids})
        });
        await requestJson("/api/queue/confirm", {
            method: "POST",
            body: JSON.stringify({queueIds: ids, confirm: true, confirmationToken: confirmation.token})
        });
        await loadQueue();
        $("queue-message").textContent = "人工确认已保存；系统仍不会批量投递。";
    } catch (error) {
        $("queue-message").textContent = error.message;
    }
}

async function prepareSingleApply(queueId) {
    if (!window.confirm("确认准备这一条投递吗？系统会在已连接 Edge 的当前页面打开职位详情，不新开标签页，也不会代替你点击投递。")) return;
    const confirmation = await requestJson(`/api/applications/${queueId}/confirmation-token`, {
        method: "POST"
    });
    const result = await requestJson(`/api/applications/${queueId}/apply`, {
        method: "POST",
        body: JSON.stringify({confirm: true, confirmationToken: confirmation.token})
    });
    $("queue-message").textContent = result.message;
}

async function collectFromEmbeddedBrowser() {
    const view = $("collector-result");
    view.className = "result-text";
    view.textContent = "正在读取独立 Edge 当前职位列表……";
    try {
        const result = await requestJson("/api/browser/embedded/collect", {method: "POST"});
        if (result.persisted) {
            const saved = result.persisted;
            view.className = "result-text result-normal";
            view.textContent = `独立 Edge 采集 ${result.jobsFound} 个，新增 ${saved.newJobs} 个，重复 ${saved.duplicates} 个，排除外包 ${saved.excluded} 个，待复核 ${saved.manualReview} 个。`;
            await loadJobs();
        } else {
            view.className = "result-text result-danger";
            view.textContent = result.message;
        }
        await loadSystemStatus();
    } catch (error) {
        view.className = "result-text result-danger";
        view.textContent = error.message;
    }
}

async function startEmbeddedBrowser() {
    const detail = $("system-detail");
    detail.textContent = "正在启动独立浏览器窗口……";
    try {
        const result = await requestJson("/api/browser/embedded/start", {method: "POST"});
        detail.textContent = result.message;
        await loadSystemStatus();
    } catch (error) {
        detail.textContent = error.message;
    }
}

async function connectEmbeddedBrowser() {
    const detail = $("system-detail");
    const button = $("connect-embedded-browser");
    const originalText = button.textContent;
    button.disabled = true;
    button.textContent = "正在检测……";
    detail.textContent = "正在检测登录状态并建立连接……";
    try {
        const result = await requestJson("/api/browser/embedded/connect", {method: "POST"});
        await loadSystemStatus();
        // 状态刷新完成后再次展示本次连接结果，避免具体成功或失败原因被通用提示覆盖。
        detail.textContent = `${result.message}${result.state ? ` · 状态：${result.state}` : ""}`;
    } catch (error) {
        detail.textContent = error.message;
    } finally {
        button.disabled = false;
        button.textContent = originalText;
    }
}

async function stopEmbeddedBrowser() {
    try {
        const result = await requestJson("/api/browser/embedded/stop", {method: "POST"});
        if (embeddedStatusTimer) {
            clearTimeout(embeddedStatusTimer);
            embeddedStatusTimer = null;
        }
        $("system-detail").textContent = result.message;
        await loadSystemStatus();
    } catch (error) {
        $("system-detail").textContent = error.message;
    }
}

async function collectJobs() {
    const view = $("collector-result");
    try {
        const parsed = JSON.parse($("jobs-json").value);
        const payload = Array.isArray(parsed) ? {jobs: parsed, capturedAt: new Date().toISOString()} : parsed;
        if (!payload || !Array.isArray(payload.jobs)) throw new Error("JSON 必须包含 jobs 数组");
        const result = await requestJson("/api/collector/boss/jobs", {
            method: "POST",
            body: JSON.stringify(payload)
        });
        view.className = "result-text result-normal";
        view.textContent = `已接收 ${result.received} 个，新增 ${result.newJobs} 个，重复 ${result.duplicates} 个，排除外包 ${result.excluded} 个，待复核 ${result.manualReview} 个。`;
        await loadJobs();
    } catch (error) {
        view.className = "result-text result-danger";
        view.textContent = error.message;
    }
}

function loadSample() {
    $("jobs-json").value = JSON.stringify({
        capturedAt: new Date().toISOString(),
        jobs: [{
            source: "BOSS",
            sourceJobId: "sample-001",
            companyName: "示例科技",
            companyIntroduction: "软件产品研发",
            jobName: "Java开发",
            jobDescription: "负责后端接口开发",
            city: "北京",
            salary: "20-30K",
            jobUrl: "https://www.zhipin.com/job_detail/example.html",
            publishedAt: localDateString()
        }]
    }, null, 2);
}

async function loadSystemStatus() {
    try {
        const system = await requestJson("/api/system/status");
        const embedded = system.embeddedBrowser || {};
        const connected = system.browserConnected;
        const loggedIn = system.loginValid;
        const managedRunning = Boolean(embedded.running);
        const managedReady = Boolean(embedded.readyForCollection);
        // 普通 Edge 启动不等于 Playwright 已连接，登录阶段必须明确显示为待人工处理。
        $("system-status").textContent = managedRunning
            ? (managedReady && loggedIn ? "静默连接已就绪 · BOSS 职位页" : "独立 Edge 已启动 · 待登录/连接")
            : (connected && loggedIn ? "浏览器已连接 · BOSS 已登录" : connected ? "浏览器已连接 · 待处理" : "浏览器待连接");
        $("system-status").className = "status-pill " + (managedReady && loggedIn ? "status-ok" : "status-warn");
        const state = embedded.state ? ` · 状态：${embedded.state}` : "";
        const event = embedded.lastEvent
            ? ` · 最近事件：${embedded.lastEvent}${embedded.lastEventAt ? `（${embedded.lastEventAt}）` : ""}`
            : "";
        const message = embedded.state && embedded.state !== "DISCONNECTED"
            ? embeddedStatusHint(embedded)
            : system.message;
        $("system-detail").textContent = `${message}${state}${event}${embedded.currentUrl ? ` · 页面：${embedded.currentUrl}` : ""}`;
        // 状态轮询只读取轻量状态，不再自动截取登录页面。
        if (!embedded.running) await loadBrowserPreview(false);
        scheduleEmbeddedStatusPolling(embedded.running);
    } catch (error) {
        $("system-status").textContent = "状态读取失败";
        $("system-status").className = "status-pill status-warn";
        $("system-detail").textContent = error.message;
    }
}

function scheduleEmbeddedStatusPolling(running) {
    if (embeddedStatusTimer) {
        clearTimeout(embeddedStatusTimer);
        embeddedStatusTimer = null;
    }
    if (!running) return;
    embeddedStatusTimer = setTimeout(() => {
        loadSystemStatus().catch(() => {
            embeddedStatusTimer = null;
        });
    }, 15000);
}

async function loadBrowserPreview(running) {
    const image = $("browser-preview");
    const empty = $("browser-preview-empty");
    if (!running) {
        image.hidden = true;
        image.removeAttribute("src");
        empty.hidden = false;
        empty.textContent = "启动独立 Edge 并建立连接后，这里会显示当前页面预览。";
        return;
    }
    try {
        const response = await secureFetch(`/api/browser/embedded/preview?timestamp=${Date.now()}`, {cache: "no-store"});
        if (response.status === 403) throw new Error("页面预览默认关闭，可通过 BOSS_PREVIEW_ENABLED=true 显式开启");
        if (!response.ok) throw new Error("preview unavailable");
        const blob = await response.blob();
        if (image.dataset.objectUrl) URL.revokeObjectURL(image.dataset.objectUrl);
        const objectUrl = URL.createObjectURL(blob);
        image.dataset.objectUrl = objectUrl;
        image.src = objectUrl;
        image.hidden = false;
        empty.hidden = true;
    } catch (error) {
        image.hidden = true;
        empty.hidden = false;
        empty.textContent = error.message === "preview unavailable"
            ? "暂时无法读取浏览器预览，请刷新状态或检查独立浏览器窗口。"
            : error.message;
    }
}

async function init() {
    policy = await requestJson("/api/policy");
    $("daily-total").value = policy.dailyTotal;
    $("planned-date").value = localDateString();
    renderCities();
    await preview();
    await loadOutsourcingRules();
    await loadJobs();
    await loadQueue();
    await loadSystemStatus();
}

$("add-city").addEventListener("click", () => {
    if (policy.cities.length >= maxCities) return alert("最多只能配置五个城市");
    policy.cities.push({city: "", priority: policy.cities.length + 1, ratio: 1, enabled: true, allowQuotaTransfer: true});
    renderCities();
});
$("preview-policy").addEventListener("click", preview);
$("save-policy").addEventListener("click", save);
$("refresh-jobs").addEventListener("click", () => loadJobs().catch((error) => alert(error.message)));
$("add-outsourcing-condition").addEventListener("click", () => {
    updateOutsourcingRulesFromForm();
    if (outsourcingRuleConfig.conditions.length >= maxOutsourcingRules) {
        return alert("最多配置100条自定义条件");
    }
    outsourcingRuleConfig.conditions.push({
        field: "ALL_TEXT",
        matchType: "CONTAINS",
        keyword: "",
        action: "REVIEW",
        enabled: true
    });
    renderOutsourcingConditions();
});
$("save-outsourcing-rules").addEventListener("click", saveOutsourcingRules);
$("check-outsourcing").addEventListener("click", () => checkOutsourcing().catch((error) => {
    $("outsourcing-result").textContent = error.message;
}));
$("load-sample").addEventListener("click", loadSample);
$("collect-jobs").addEventListener("click", collectJobs);
$("rebuild-queue").addEventListener("click", rebuildQueue);
$("refresh-queue").addEventListener("click", () => loadQueue().catch((error) => $("queue-message").textContent = error.message));
$("confirm-queue").addEventListener("click", confirmQueue);
$("refresh-system").addEventListener("click", loadSystemStatus);
$("connect-embedded-browser").addEventListener("click", connectEmbeddedBrowser);
$("refresh-browser-preview").addEventListener("click", async () => {
    const empty = $("browser-preview-empty");
    empty.hidden = false;
    empty.textContent = "正在按你的操作读取一次页面预览……";
    await loadBrowserPreview(true);
});
$("start-embedded-browser").addEventListener("click", startEmbeddedBrowser);
$("stop-embedded-browser").addEventListener("click", stopEmbeddedBrowser);
$("start-embedded-collector").addEventListener("click", collectFromEmbeddedBrowser);
init().catch((error) => {
    $("policy-message").textContent = error.message;
    $("system-detail").textContent = "本地服务尚未准备好，请确认 18080 端口已启动。";
});





