const BRIDGE_SOURCE = "BOSS_LOCAL_ADMIN_BRIDGE";
const ALLOWED_ORIGINS = new Set([
    "http://127.0.0.1:18080",
    "http://localhost:18080"
]);
const ALLOWED_MESSAGES = new Set([
    "GET_EXTENSION_STATUS",
    "REFRESH_EXTENSION_STATUS",
    "COLLECT_CURRENT_BOSS_PAGE"
]);

// 管理端页面不能直接访问扩展 API 时，由本地管理页桥接到扩展服务 Worker。
window.addEventListener("message", (event) => {
    if (event.source !== window || !ALLOWED_ORIGINS.has(event.origin)) return;
    const data = event.data || {};
    if (data.source !== BRIDGE_SOURCE || !ALLOWED_MESSAGES.has(data.type) || !data.requestId) return;

    chrome.runtime.sendMessage(data.payload && data.payload.type === data.type ? data.payload : {type: data.type})
        .then((response) => {
            window.postMessage({source: BRIDGE_SOURCE, requestId: data.requestId, response: response || {}}, event.origin);
        })
        .catch((error) => {
            window.postMessage({source: BRIDGE_SOURCE, requestId: data.requestId, response: {error: error.message}}, event.origin);
        });
});
