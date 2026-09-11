let policy = null;
let currentQueue = [];
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

const {requestJson} = window.BossApi;

const activeTabStorageKey = "boss-auto-apply-active-tab";

// 统一切换主功能面板，并在当前标签页会话中记住用户选择。
function activateTab(tabName, shouldFocus = false) {
    const tabs = Array.from(document.querySelectorAll("[data-tab-target]"));
    const panels = Array.from(document.querySelectorAll("[data-tab-panel]"));
    const targetTab = tabs.find((tab) => tab.dataset.tabTarget === tabName) || tabs[0];
    if (!targetTab) return;

    const activeName = targetTab.dataset.tabTarget;
    tabs.forEach((tab) => {
        const active = tab === targetTab;
        tab.classList.toggle("active", active);
        tab.setAttribute("aria-selected", String(active));
        tab.tabIndex = active ? 0 : -1;
    });
    panels.forEach((panel) => {
        panel.hidden = panel.dataset.tabPanel !== activeName;
    });
    sessionStorage.setItem(activeTabStorageKey, activeName);
    if (activeName === "delivery") {
        loadDeliveryTasks().catch(() => {});
    }
    if (shouldFocus) targetTab.focus();
}

// 支持鼠标、触摸和方向键切换，避免 Tab 导航只能用鼠标操作。
function initTabs() {
    const tabs = Array.from(document.querySelectorAll("[data-tab-target]"));
    if (tabs.length === 0) return;

    tabs.forEach((tab, index) => {
        tab.addEventListener("click", () => activateTab(tab.dataset.tabTarget));
        tab.addEventListener("keydown", (event) => {
            let targetIndex = null;
            if (event.key === "ArrowRight") targetIndex = (index + 1) % tabs.length;
            if (event.key === "ArrowLeft") targetIndex = (index - 1 + tabs.length) % tabs.length;
            if (event.key === "Home") targetIndex = 0;
            if (event.key === "End") targetIndex = tabs.length - 1;
            if (targetIndex === null) return;
            event.preventDefault();
            activateTab(tabs[targetIndex].dataset.tabTarget, true);
        });
    });

    activateTab(sessionStorage.getItem(activeTabStorageKey) || tabs[0].dataset.tabTarget);
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
        const details = [
            job.experienceRequirement ? `经验：${job.experienceRequirement}` : "",
            job.educationRequirement ? `学历：${job.educationRequirement}` : "",
            job.companySize ? `规模：${job.companySize}` : "",
            job.companyIndustry ? `行业：${job.companyIndustry}` : "",
            job.publishedAt ? `时间：${job.publishedAt}` : ""
        ].filter(Boolean);
        const stateTags = [job.urgent === true ? "急招" : "", job.online === true ? "在线" : ""].filter(Boolean);
        const collectedTags = [...(job.jobTags || []), ...(job.welfareTags || []), ...stateTags];
        return `
        <article class="job-item">
            <div class="job-main">
                <strong>${escapeHtml(job.jobName || "未命名职位")}</strong>
                <span>${escapeHtml(job.companyName || "未知公司")} · ${escapeHtml(job.city || "城市未知")} · ${escapeHtml(job.salary || "薪资面议")}</span>
                ${details.length ? `<div class="job-collected-fields">${details.map((detail) => `<span>${escapeHtml(detail)}</span>`).join("")}</div>` : ""}
                ${collectedTags.length ? `<div class="job-collected-tags">${collectedTags.slice(0, 12).map((tag) => `<span>${escapeHtml(tag)}</span>`).join("")}</div>` : ""}
                ${job.jobDescription ? `<small class="job-description-preview">职位描述：${escapeHtml(job.jobDescription)}</small>` : ""}
                <small>${escapeHtml(job.outsourcingReason || "未执行外包判断")}${job.outsourcingKeyword ? ` · 命中：${escapeHtml(job.outsourcingKeyword)}` : ""}</small>
                ${jobUrl ? `<a href="${escapeHtml(jobUrl)}" target="_blank" rel="noopener noreferrer">查看 BOSS 职位详情</a>` : ""}
            </div>
            <span class="${statusClass(job.applyStatus)}">${escapeHtml(statusLabel(job.applyStatus))}</span>
        </article>`;
    }).join("");
}

function queueStatusLabel(status) {
    return {QUEUED: "待人工确认", APPROVED: "已确认（已进入投递任务）", REJECTED: "已拒绝", APPLIED: "已投递"}[status] || status;
}

function deliveryTaskStatusLabel(status) {
    return {WAITING: "等待扩展领取", RUNNING: "扩展执行中", COMPLETED: "已完成", BLOCKED: "已阻断，需人工处理"}[status] || status;
}

async function loadDeliveryTasks() {
    const plannedDate = $("planned-date").value || localDateString();
    const tasks = await requestJson(`/api/delivery-tasks?plannedDate=${encodeURIComponent(plannedDate)}`);
    const list = $("delivery-task-list");
    if (!tasks.length) {
        list.innerHTML = '<p class="empty-state">确认候选岗位后，投递任务会自动出现在这里。</p>';
        return;
    }
    list.innerHTML = tasks.slice(0, 5).map((task) => `
        <article class="queue-item">
            <div class="queue-item-main">
                <strong>${escapeHtml(task.taskName || "投递任务")}</strong>
                <small>${escapeHtml(deliveryTaskStatusLabel(task.taskStatus))} · 共 ${task.totalCount} 个 · 待处理 ${task.waitingCount} 个</small>
                <small>成功 ${task.successCount} · 失败 ${task.failedCount} · 未知 ${task.unknownCount}</small>
            </div>
            <span class="queue-state">${escapeHtml(task.updatedAt || task.createdAt || "")}</span>
        </article>`).join("");
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
        return `<article class="queue-item">
            <div class="queue-item-main">
                <div class="queue-item-title"><strong>${escapeHtml(job.jobName || "未命名职位")}</strong><span class="queue-rank">#${item.queueRank}</span></div>
                <span>${escapeHtml(job.companyName || "未知公司")} · ${escapeHtml(job.city || "城市未知")} · ${escapeHtml(job.salary || "薪资面议")}</span>
                <small>计划城市：${escapeHtml(item.quotaCity)} · ${item.allocationType === "TRANSFER" ? "补量候选" : "主额度"} · ${escapeHtml(job.outsourcingReason || "已通过外包筛选")}</small>
                ${jobUrl ? `<a href="${escapeHtml(jobUrl)}" target="_blank" rel="noopener noreferrer">查看职位详情</a>` : ""}
            </div>
            <div class="queue-item-actions">${selector}</div>
        </article>`;
    }).join("");

}

function selectedQueueIds() {
    return [...document.querySelectorAll("[data-queue-id]:checked")].map((element) => Number(element.dataset.queueId));
}

async function loadQueue() {
    const plannedDate = $("planned-date").value || localDateString();
    const items = await requestJson(`/api/queue?plannedDate=${encodeURIComponent(plannedDate)}`);
    renderQueue(items);
    await loadDeliveryTasks().catch((error) => {
        $("queue-message").textContent = `候选队列已加载，但投递任务状态读取失败：${error.message}`;
    });
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
    if (!window.confirm(`确定确认 ${ids.length} 个岗位吗？确认后将自动创建投递任务，由 Chrome 扩展后台按顺序执行。`)) return;
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
        $("queue-message").textContent = "候选岗位已确认，已自动创建投递任务；Chrome 扩展会在后台按顺序执行。";
        // 确认完成后直接展示任务管理，避免用户再次手动查找已创建的任务。
        activateTab("delivery");
    } catch (error) {
        $("queue-message").textContent = error.message;
    }
}


function isChromeExtensionPageAvailable() {
    return Boolean(globalThis.chrome?.runtime?.sendMessage);
}

function chromePageUnavailableMessage() {
    return "当前管理页面不在安装扩展的 Chrome 中，无法直接调用扩展。请把管理端地址复制到普通 Chrome 打开，或在 Chrome 工具栏扩展弹窗中点击“采集当前职位页”。";
}

function sendChromeExtensionBridgeMessage(message, timeoutMs = 2500) {
    return new Promise((resolve, reject) => {
        const requestId = String(Date.now()) + "-" + Math.random().toString(16).slice(2);
        const targetOrigin = window.location.origin;
        let timer;
        const onMessage = (event) => {
            if (event.source !== window || event.origin !== targetOrigin) return;
            const data = event.data || {};
            if (data.source !== "BOSS_LOCAL_ADMIN_BRIDGE" || data.requestId !== requestId) return;
            window.removeEventListener("message", onMessage);
            window.clearTimeout(timer);
            if (data.response?.error) reject(new Error(data.response.error));
            else resolve(data.response || {});
        };
        window.addEventListener("message", onMessage);
        timer = window.setTimeout(() => {
            window.removeEventListener("message", onMessage);
            reject(new Error("未检测到 Chrome 扩展桥接。请确认已在 Chrome 重新加载扩展，并刷新管理页面。"));
        }, timeoutMs);
        window.postMessage({source: "BOSS_LOCAL_ADMIN_BRIDGE", requestId, type: message.type, payload: message}, targetOrigin);
    });
}

function sendChromeExtensionMessage(extensionId, message, timeoutMs = 2500) {
    if (!isChromeExtensionPageAvailable()) {
        return sendChromeExtensionBridgeMessage(message, timeoutMs);
    }
    return new Promise((resolve, reject) => {
        const timer = window.setTimeout(() => reject(new Error("Chrome 扩展自动投递超时，请检查 BOSS 页面状态。")), timeoutMs);
        chrome.runtime.sendMessage(extensionId, message, (response) => {
            window.clearTimeout(timer);
            const runtimeError = chrome.runtime.lastError;
            if (runtimeError) {
                reject(new Error("Chrome 扩展未响应。请确认扩展已加载、管理端地址已在 Chrome 打开，并刷新管理页面。"));
                return;
            }
            if (response?.error) {
                reject(new Error(response.error));
                return;
            }
            resolve(response || {});
        });
    });
}

async function updateChromeCollectionGuide() {
    const guide = $("chrome-collection-guide");
    if (!guide) return;
    if (isChromeExtensionPageAvailable()) {
        guide.hidden = true;
        return;
    }
    try {
        await sendChromeExtensionBridgeMessage({type: "GET_EXTENSION_STATUS"}, 1200);
        guide.hidden = true;
    } catch (error) {
        guide.hidden = false;
    }
}

async function copyManagementUrl() {
    const url = window.location.href;
    try {
        await navigator.clipboard.writeText(url);
        $("collector-result").className = "result-text result-normal";
        $("collector-result").textContent = "已复制管理端地址：" + url;
    } catch (error) {
        window.prompt("请复制下面的管理端地址并在 Chrome 中打开", url);
    }
}

function showCollectionSteps() {
    alert("操作步骤：\n1. 打开普通 Chrome，并访问 http://127.0.0.1:18080/；\n2. 确认已加载 BOSS 本地投递助手扩展；\n3. 打开 BOSS 职位列表并确认扩展显示“已连接、已登录、正常”；\n4. 回到管理端点击“从 Chrome 当前页采集”，或直接在扩展弹窗点击“采集当前职位页”。");
}

async function collectFromChrome() {
    const view = $("collector-result");
    const button = $("collect-from-chrome");
    view.className = "result-text";
    button.disabled = true;
    try {
        if (!isChromeExtensionPageAvailable()) {
            throw new Error(chromePageUnavailableMessage());
        }
        view.textContent = "正在通过 Chrome 扩展只读采集当前职位列表或详情……";
        const status = await requestJson("/api/extension/status", {cache: "no-store"});
        if (!status.extensionId) throw new Error("Chrome 扩展尚未配对，请先在连接管理中完成配对");
        const result = await sendChromeExtensionMessage(status.extensionId, {type: "COLLECT_CURRENT_BOSS_PAGE"});
        view.className = "result-text result-normal";
        view.textContent = result.pageType === "JOB_DETAIL"
            ? `已补充当前职位详情，新增 ${result.newJobs} 个，更新 ${result.duplicates} 个。`
            : `Chrome 页面识别 ${result.pageJobsFound} 个，提交 ${result.received} 个，新增 ${result.newJobs} 个，重复 ${result.duplicates} 个，排除外包 ${result.excluded} 个，待复核 ${result.manualReview} 个。`;
        await loadJobs();
    } catch (error) {
        view.className = "result-text result-danger";
        view.textContent = error.message;
    } finally {
        button.disabled = false;
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
        const system = await requestJson("/api/system/status", {cache: "no-store"});
        $("system-status").textContent = "管理端运行正常";
        $("system-status").className = "status-pill status-ok";
        $("system-detail").textContent = system.message || "Chrome 扩展状态请在连接管理模块查看";
    } catch (error) {
        $("system-status").textContent = "管理端不可用";
        $("system-status").className = "status-pill status-danger";
        $("system-detail").textContent = error.message;
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
$("collect-from-chrome").addEventListener("click", collectFromChrome);
updateChromeCollectionGuide();
$("copy-management-url").addEventListener("click", copyManagementUrl);
$("show-collection-steps").addEventListener("click", showCollectionSteps);
initTabs();
init().catch((error) => {
    $("policy-message").textContent = error.message;
    $("system-detail").textContent = "本地服务尚未准备好，请确认 18080 端口已启动。";
});

window.setInterval(() => {
    const activeTab = document.querySelector("[data-tab-target].active")?.dataset.tabTarget;
    if (document.visibilityState === "visible" && activeTab === "delivery") {
        loadDeliveryTasks().catch(() => {});
    }
}, 15000);
