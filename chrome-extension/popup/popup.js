const $ = (id) => document.getElementById(id);

function render(status) {
    const message = status.error || status.message || (status.paired ? "扩展已配对" : "扩展尚未配对");
    $("status").textContent = message;
    $("status").className = `status ${status.error || status.online === false ? "error" : status.loginState === "LOGGED_IN" && status.securityState === "NORMAL" ? "ready" : ""}`;
    $("version").textContent = status.extensionVersion || "-";
    $("boss-page").textContent = status.bossTabFound ? (status.contentScriptReady ? "已连接" : "需要刷新页面") : "未发现";
    $("login-state").textContent = ({LOGGED_IN: "已登录", NOT_LOGGED_IN: "未登录", UNKNOWN: "待确认"})[status.loginState] || "待确认";
    $("security-state").textContent = ({NORMAL: "正常", SECURITY_CHECK_REQUIRED: "需要验证", ACCESS_RESTRICTED: "访问受限", UNKNOWN: "待确认"})[status.securityState] || "待确认";
}

async function refresh() {
    const status = await chrome.runtime.sendMessage({type: "REFRESH_EXTENSION_STATUS"});
    render(status || {error: "无法读取扩展状态"});
}

$("pair").addEventListener("click", async () => {
    const pairingCode = $("pairing-code").value.trim();
    if (!pairingCode) return render({error: "请先输入管理端生成的一次性配对码"});
    $("pair").disabled = true;
    try {
        const result = await chrome.runtime.sendMessage({type: "PAIR_EXTENSION", pairingCode});
        if (result?.error) throw new Error(result.error);
        $("pairing-code").value = "";
        await refresh();
    } catch (error) {
        render({error: error.message});
    } finally {
        $("pair").disabled = false;
    }
});

$("refresh").addEventListener("click", refresh);
chrome.runtime.sendMessage({type: "GET_EXTENSION_STATUS"}).then(render).catch((error) => render({error: error.message}));
