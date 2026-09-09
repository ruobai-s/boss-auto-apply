const API_ROOT = "http://127.0.0.1:18080/api/extension/client";
const TOKEN_KEY = "bossExtensionToken";
const INSTANCE_KEY = "bossExtensionInstanceId";
const LAST_STATUS_KEY = "bossExtensionLastStatus";
const HEARTBEAT_ALARM = "boss-extension-heartbeat";
const DEFAULT_HEARTBEAT_SECONDS = 30;

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
        body: JSON.stringify({
            pairingCode,
            instanceId,
            extensionVersion: chrome.runtime.getManifest().version
        })
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
        return {
            bossTabFound: false,
            contentScriptReady: false,
            pageType: "NONE",
            loginState: "UNKNOWN",
            securityState: "UNKNOWN",
            message: "没有发现已打开的 BOSS 页面"
        };
    }

    const selected = tabs.find((tab) => tab.active) || tabs.sort((left, right) =>
        Number(right.lastAccessed || 0) - Number(left.lastAccessed || 0))[0];
    try {
        const status = await chrome.tabs.sendMessage(selected.id, {type: "BOSS_EXTENSION_STATUS"});
        return {
            bossTabFound: true,
            contentScriptReady: true,
            pageType: status.pageType || "UNKNOWN",
            loginState: status.loginState || "UNKNOWN",
            securityState: status.securityState || "UNKNOWN",
            message: status.message || "BOSS 页面连接脚本已响应"
        };
    } catch (error) {
        return {
            bossTabFound: true,
            contentScriptReady: false,
            pageType: "UNKNOWN",
            loginState: "UNKNOWN",
            securityState: "UNKNOWN",
            message: "页面连接脚本尚未生效，请刷新 BOSS 页面"
        };
    }
}

async function sendHeartbeat() {
    const stored = await chrome.storage.local.get([TOKEN_KEY, INSTANCE_KEY]);
    const token = stored[TOKEN_KEY];
    if (!token) {
        const status = {paired: false, message: "扩展尚未配对"};
        await chrome.storage.local.set({[LAST_STATUS_KEY]: status});
        return status;
    }

    const instanceId = stored[INSTANCE_KEY] || await getOrCreateInstanceId();
    const page = await detectBossPage();
    try {
        const response = await fetch(`${API_ROOT}/heartbeat`, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "X-Boss-Extension-Token": token
            },
            body: JSON.stringify({
                instanceId,
                extensionVersion: chrome.runtime.getManifest().version,
                ...page
            })
        });
        const result = await readJson(response);
        const status = {paired: true, online: true, ...page, serverState: result.state, serverTime: result.serverTime};
        await chrome.storage.local.set({[LAST_STATUS_KEY]: status});
        return status;
    } catch (error) {
        if (/重新配对|扩展身份|令牌/.test(error.message)) {
            await chrome.storage.local.remove(TOKEN_KEY);
        }
        const status = {paired: Boolean(token), online: false, ...page, message: error.message};
        await chrome.storage.local.set({[LAST_STATUS_KEY]: status});
        return status;
    }
}

async function localStatus() {
    const stored = await chrome.storage.local.get([TOKEN_KEY, INSTANCE_KEY, LAST_STATUS_KEY]);
    return {
        paired: Boolean(stored[TOKEN_KEY]),
        instanceId: stored[INSTANCE_KEY] || await getOrCreateInstanceId(),
        extensionVersion: chrome.runtime.getManifest().version,
        ...(stored[LAST_STATUS_KEY] || {})
    };
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message?.type === "PAIR_EXTENSION") {
        pair(String(message.pairingCode || "").trim())
            .then(sendResponse)
            .catch((error) => sendResponse({error: error.message}));
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
    return false;
});

chrome.runtime.onInstalled.addListener(async () => {
    await getOrCreateInstanceId();
    await chrome.alarms.create(HEARTBEAT_ALARM, {periodInMinutes: 0.5});
    await sendHeartbeat();
});

chrome.runtime.onStartup.addListener(async () => {
    await chrome.alarms.create(HEARTBEAT_ALARM, {periodInMinutes: 0.5});
    await sendHeartbeat();
});

chrome.alarms.onAlarm.addListener((alarm) => {
    if (alarm.name === HEARTBEAT_ALARM) sendHeartbeat().catch(() => {});
});

chrome.tabs.onActivated.addListener(() => sendHeartbeat().catch(() => {}));
chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
    if (changeInfo.status === "complete" && tab.url?.startsWith("https://www.zhipin.com/")) {
        sendHeartbeat().catch(() => {});
    }
});
