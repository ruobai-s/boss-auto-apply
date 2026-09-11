const API_ROOT = "http://127.0.0.1:18080/api/extension/client";
const TOKEN_KEY = "bossExtensionToken";
const INSTANCE_KEY = "bossExtensionInstanceId";
const LAST_STATUS_KEY = "bossExtensionLastStatus";
const LAST_COLLECTION_KEY = "bossExtensionLastCollection";
const HEARTBEAT_ALARM = "boss-extension-heartbeat";
const DEFAULT_HEARTBEAT_SECONDS = 30;
const MAX_REQUEST_BYTES = 30 * 1024;
const ALLOWED_MANAGEMENT_ORIGINS = new Set(["http://127.0.0.1:18080", "http://localhost:18080"]);
let deliveryRunning = false;

// 扩展实例标识只用于绑定当前 Chrome Profile，不包含设备或账号明文。
async function getOrCreateInstanceId() {
    const stored = await chrome.storage.local.get(INSTANCE_KEY);
    if (stored[INSTANCE_KEY]) return stored[INSTANCE_KEY];
    const instanceId = crypto.randomUUID();
    await chrome.storage.local.set({[INSTANCE_KEY]: instanceId});
    return instanceId;
}

async function readJson(response) {
    const text = await response.text();
    let data = {};
    if (text) {
        try {
            data = JSON.parse(text);
        } catch (error) {
            data = {message: text};
        }
    }
    if (!response.ok) throw new Error(data.message || "本地管理端请求失败");
    return data;
}

async function pair(pairingCode) {
    const instanceId = await getOrCreateInstanceId();
    const response = await fetch(`${API_ROOT}/pair`, {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({pairingCode, instanceId, extensionVersion: chrome.runtime.getManifest().version})
    });
    const result = await readJson(response);
    await chrome.storage.local.set({[TOKEN_KEY]: result.extensionToken});
    const intervalSeconds = Math.max(15, Number(result.heartbeatIntervalSeconds) || DEFAULT_HEARTBEAT_SECONDS);
    await chrome.alarms.create(HEARTBEAT_ALARM, {periodInMinutes: Math.max(0.5, intervalSeconds / 60)});
    await sendHeartbeat();
    return {paired: true, pairedAt: result.pairedAt};
}

async function detectBossPage() {
    const tabs = await chrome.tabs.query({url: ["https://www.zhipin.com/*"]});
    if (!tabs.length) {
        return {bossTabFound: false, contentScriptReady: false, pageType: "NONE", loginState: "UNKNOWN",
            securityState: "UNKNOWN", message: "没有发现已打开的 BOSS 页面"};
    }

    const selected = tabs.find((tab) => tab.active) || tabs.sort((left, right) =>
        Number(right.lastAccessed || 0) - Number(left.lastAccessed || 0))[0];
    try {
        const status = await chrome.tabs.sendMessage(selected.id, {type: "BOSS_EXTENSION_STATUS"});
        return {tabId: selected.id, bossTabFound: true, contentScriptReady: true,
            pageType: status.pageType || "UNKNOWN", loginState: status.loginState || "UNKNOWN",
            securityState: status.securityState || "UNKNOWN", message: status.message || "BOSS 页面连接脚本已响应"};
    } catch (error) {
        return {tabId: selected.id, bossTabFound: true, contentScriptReady: false, pageType: "UNKNOWN",
            loginState: "UNKNOWN", securityState: "UNKNOWN", message: "页面连接脚本尚未生效，请刷新 BOSS 页面"};
    }
}

async function sendHeartbeat(detectedPage = null) {
    const stored = await chrome.storage.local.get([TOKEN_KEY, INSTANCE_KEY]);
    const token = stored[TOKEN_KEY];
    if (!token) {
        const status = {paired: false, message: "扩展尚未配对"};
        await chrome.storage.local.set({[LAST_STATUS_KEY]: status});
        return status;
    }

    const instanceId = stored[INSTANCE_KEY] || await getOrCreateInstanceId();
    const page = detectedPage || await detectBossPage();
    const {tabId, ...pageStatus} = page;
    try {
        const response = await fetch(`${API_ROOT}/heartbeat`, {
            method: "POST",
            headers: {"Content-Type": "application/json", "X-Boss-Extension-Token": token},
            body: JSON.stringify({instanceId, extensionVersion: chrome.runtime.getManifest().version, ...pageStatus})
        });
        const result = await readJson(response);
        const status = {paired: true, online: true, ...pageStatus, serverState: result.state, serverTime: result.serverTime};
        await chrome.storage.local.set({[LAST_STATUS_KEY]: status});
        return {...status, tabId};
    } catch (error) {
        if (/重新配对|扩展身份|令牌/.test(error.message)) await chrome.storage.local.remove(TOKEN_KEY);
        const status = {paired: Boolean(token), online: false, ...pageStatus, message: error.message};
        await chrome.storage.local.set({[LAST_STATUS_KEY]: status});
        return {...status, tabId};
    }
}

function assertCollectible(status) {
    if (!status.paired) throw new Error("扩展尚未配对");
    if (!status.online) throw new Error(status.message || "本地管理端当前不可用");
    if (!status.bossTabFound) throw new Error("没有发现已打开的 BOSS 页面");
    if (!status.contentScriptReady) throw new Error("页面连接脚本尚未生效，请刷新 BOSS 页面");
    if (!["JOB_LIST", "JOB_DETAIL"].includes(status.pageType)) throw new Error("请先在 Chrome 中打开 BOSS 职位列表页或职位详情页");
    if (status.loginState !== "LOGGED_IN") throw new Error("尚未确认 BOSS 登录状态，不能采集职位");
    if (status.securityState !== "NORMAL") throw new Error("检测到访问限制或安全验证，已停止职位采集");
}

function requestSize(payload) {
    return new TextEncoder().encode(JSON.stringify(payload)).length;
}

async function collectCurrentBossPage() {
    const stored = await chrome.storage.local.get([TOKEN_KEY, INSTANCE_KEY]);
    const token = stored[TOKEN_KEY];
    if (!token) throw new Error("扩展尚未配对，请先输入管理端一次性配对码");

    const page = await detectBossPage();
    const status = await sendHeartbeat(page);
    assertCollectible(status);
    const messageType = status.pageType === "JOB_DETAIL" ? "BOSS_COLLECT_JOB_DETAIL" : "BOSS_COLLECT_JOBS";
    const extracted = await chrome.tabs.sendMessage(status.tabId, {type: messageType});
    if (extracted?.error) throw new Error(extracted.error);
    if (!Array.isArray(extracted?.jobs) || !extracted.jobs.length) throw new Error("当前页面没有可采集职位");

    const instanceId = stored[INSTANCE_KEY] || await getOrCreateInstanceId();
    const payload = {
        instanceId,
        extensionVersion: chrome.runtime.getManifest().version,
        pageType: status.pageType,
        loginState: status.loginState,
        securityState: status.securityState,
        jobs: extracted.jobs.slice(0, 50),
        capturedAt: extracted.capturedAt || new Date().toISOString()
    };
    // 扩展接口有严格请求体上限；只缩减本次职位数量，不截断为不完整 JSON。
    while (payload.jobs.length > 1 && requestSize(payload) > MAX_REQUEST_BYTES) payload.jobs.pop();
    if (requestSize(payload) > MAX_REQUEST_BYTES) throw new Error("当前职位数据超过本地接口大小限制，请减少页面已加载职位后重试");

    const response = await fetch(`${API_ROOT}/jobs`, {
        method: "POST",
        headers: {"Content-Type": "application/json", "X-Boss-Extension-Token": token},
        body: JSON.stringify(payload)
    });
    const result = await readJson(response);
    const summary = {...result, pageType: status.pageType, pageJobsFound: extracted.jobs.length, submittedJobs: payload.jobs.length};
    // 本地仅保存统计结果，不保存完整职位内容。
    await chrome.storage.local.set({[LAST_COLLECTION_KEY]: summary});
    return summary;
}


async function postDeliveryTask(path, token, body) {
    const response = await fetch(`${API_ROOT}/delivery-tasks${path}`, {
        method: "POST",
        headers: {"Content-Type": "application/json", "X-Boss-Extension-Token": token},
        body: JSON.stringify(body)
    });
    return readJson(response);
}

async function reportDeliveryStage(token, instanceId, itemId, executionToken, stage) {
    const response = await fetch(`${API_ROOT}/delivery-tasks/${itemId}/stage`, {
        method: "POST",
        headers: {"Content-Type": "application/json", "X-Boss-Extension-Token": token},
        body: JSON.stringify({instanceId, executionToken, stage})
    });
    if (!response.ok) await readJson(response);
}

async function reportDeliveryResult(token, instanceId, itemId, executionToken, resultStatus, failureReason, operatorNote) {
    return postDeliveryTask(`/${itemId}/result`, token, {
        instanceId, executionToken, resultStatus, failureReason: failureReason || "", operatorNote: operatorNote || ""
    });
}

function deliveryErrorStatus(message) {
    const text = String(message || "");
    if (/验证码|安全验证|访问限制|风控|403|code32/.test(text)) return "BLOCKED";
    if (/无法确认|租约|加载超时|页面不一致|点击后/.test(text)) return "UNKNOWN";
    return "FAILED";
}

async function pollDeliveryTasks() {
    if (deliveryRunning) return;
    deliveryRunning = true;
    try {
        const stored = await chrome.storage.local.get([TOKEN_KEY, INSTANCE_KEY]);
        const token = stored[TOKEN_KEY];
        const instanceId = stored[INSTANCE_KEY] || await getOrCreateInstanceId();
        if (!token) return;

        const status = await sendHeartbeat();
        if (!status.online || !status.bossTabFound || !status.contentScriptReady
            || status.loginState !== "LOGGED_IN" || status.securityState !== "NORMAL") return;
        const lease = await postDeliveryTask("/lease", token, {instanceId});
        if (!lease.available) return;

        const tabs = await chrome.tabs.query({url: ["https://www.zhipin.com/*"]});
        const targetTab = tabs.find((tab) => tab.id === status.tabId) || tabs.find((tab) => tab.active) || tabs[0];
        if (!targetTab?.id) {
            await reportDeliveryResult(token, instanceId, lease.taskItemId, lease.executionToken, "UNKNOWN", "没有发现可执行的 BOSS 标签页", "扩展租约已领取但未找到执行标签页");
            return;
        }

        try {
            await reportDeliveryStage(token, instanceId, lease.taskItemId, lease.executionToken, "RUNNING");
            // 先注册完成监听，再开始导航，避免页面加载过快导致错过 complete 事件。
            const tabLoad = waitForTabComplete(targetTab.id, 15000);
            await chrome.tabs.update(targetTab.id, {url: lease.jobUrl, active: true});
            await tabLoad;
            const result = await sendApplyMessageWithRetry(targetTab.id, lease.sourceJobId);
            if (result?.error) throw new Error(result.error);
            await reportDeliveryStage(token, instanceId, lease.taskItemId, lease.executionToken, "CLICKED_PENDING_CONFIRMATION");
            await reportDeliveryResult(token, instanceId, lease.taskItemId, lease.executionToken, "SUCCESS", "", result?.message || "扩展已完成投递并确认页面状态变化");
        } catch (error) {
            const message = error?.message || "扩展执行失败";
            try {
                await reportDeliveryResult(token, instanceId, lease.taskItemId, lease.executionToken,
                    deliveryErrorStatus(message), message, "扩展已停止当前任务，不会自动重复点击");
            } catch (reportError) {
                await chrome.storage.local.set({[LAST_STATUS_KEY]: {
                    ...status, deliveryError: `投递结果回报失败：${reportError.message || reportError}`
                }});
            }
        }
    } catch (error) {
        await chrome.storage.local.set({[LAST_STATUS_KEY]: {
            paired: true, online: false, message: error?.message || "投递任务轮询失败"
        }});
    } finally {
        deliveryRunning = false;
    }
}
// 页面 complete 事件早于 document_idle 注入时，短暂重试消息发送，避免误报投递失败。
async function sendApplyMessageWithRetry(tabId, sourceJobId, timeoutMs = 10000) {
    const deadline = Date.now() + timeoutMs;
    let lastError = null;
    while (Date.now() < deadline) {
        try {
            return await chrome.tabs.sendMessage(tabId, {
                type: "BOSS_APPLY_CURRENT_JOB", sourceJobId
            });
        } catch (error) {
            lastError = error;
            const message = String(error?.message || error || "");
            // 只有接收端尚未注入的竞态才重试，真正的执行错误交给上层回报。
            if (!/Receiving end does not exist|Could not establish connection|message port closed|消息接收端|消息端口/i.test(message)) {
                throw error;
            }
            await new Promise((resolve) => setTimeout(resolve, 300));
        }
    }
    throw new Error(`页面连接脚本未就绪：${lastError?.message || "投递消息发送超时"}`);
}

function waitForTabComplete(tabId, timeoutMs) {
    return new Promise((resolve, reject) => {
        let timer;
        const listener = (updatedTabId, changeInfo) => {
            if (updatedTabId !== tabId || changeInfo.status !== "complete") return;
            chrome.tabs.onUpdated.removeListener(listener);
            clearTimeout(timer);
            resolve();
        };
        chrome.tabs.onUpdated.addListener(listener);
        timer = setTimeout(() => {
            chrome.tabs.onUpdated.removeListener(listener);
            reject(new Error("职位详情页加载超时，已停止自动投递"));
        }, timeoutMs);
    });
}

async function localStatus() {
    const stored = await chrome.storage.local.get([TOKEN_KEY, INSTANCE_KEY, LAST_STATUS_KEY, LAST_COLLECTION_KEY]);
    return {paired: Boolean(stored[TOKEN_KEY]), instanceId: stored[INSTANCE_KEY] || await getOrCreateInstanceId(),
        extensionVersion: chrome.runtime.getManifest().version, ...(stored[LAST_STATUS_KEY] || {}),
        lastCollection: stored[LAST_COLLECTION_KEY] || null};
}

function handleMessage(message, sendResponse) {
    if (message?.type === "PAIR_EXTENSION") {
        pair(String(message.pairingCode || "").trim()).then(sendResponse).catch((error) => sendResponse({error: error.message}));
        return true;
    }
    if (message?.type === "REFRESH_EXTENSION_STATUS") {
        sendHeartbeat().then(sendResponse).catch((error) => sendResponse({error: error.message}));
        return true;
    }
    if (message?.type === "GET_EXTENSION_STATUS") {
        localStatus().then(sendResponse).catch((error) => sendResponse({error: error.message}));
        return true;
    }
    if (message?.type === "COLLECT_CURRENT_BOSS_PAGE") {
        collectCurrentBossPage().then(sendResponse).catch((error) => sendResponse({error: error.message}));
        return true;
    }
    return false;
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => handleMessage(message, sendResponse));
chrome.runtime.onMessageExternal.addListener((message, sender, sendResponse) => {
    let origin = "";
    try {
        origin = new URL(sender.url || "").origin;
    } catch (error) {
        sendResponse({error: "无法确认管理页面来源"});
        return false;
    }
    if (!ALLOWED_MANAGEMENT_ORIGINS.has(origin)) {
        sendResponse({error: "该页面无权调用职位采集功能"});
        return false;
    }
    return handleMessage(message, sendResponse);
});

chrome.runtime.onInstalled.addListener(async () => {
    await getOrCreateInstanceId();
    await chrome.alarms.create(HEARTBEAT_ALARM, {periodInMinutes: 0.5});
    await sendHeartbeat();
    await pollDeliveryTasks();
});
chrome.runtime.onStartup.addListener(async () => {
    await chrome.alarms.create(HEARTBEAT_ALARM, {periodInMinutes: 0.5});
    await sendHeartbeat();
    await pollDeliveryTasks();
});
chrome.alarms.onAlarm.addListener((alarm) => {
    if (alarm.name === HEARTBEAT_ALARM) {
        sendHeartbeat().then(() => pollDeliveryTasks()).catch(() => {});
    }
});
chrome.tabs.onActivated.addListener(() => sendHeartbeat().catch(() => {}));
chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
    if (changeInfo.status === "complete" && tab.url?.startsWith("https://www.zhipin.com/")) sendHeartbeat().catch(() => {});
});





