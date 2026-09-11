const $ = (id) => document.getElementById(id);

function canCollect(status) {
    return Boolean(status.paired && status.online && status.bossTabFound && status.contentScriptReady
        && ["JOB_LIST", "JOB_DETAIL"].includes(status.pageType)
        && status.loginState === "LOGGED_IN" && status.securityState === "NORMAL");
}

function render(status) {
    const message = status.error || status.message || (status.paired ? "扩展已配对" : "扩展尚未配对");
    $("status").textContent = message;
    $("status").className = `status ${status.error || status.online === false ? "error" : canCollect(status) ? "ready" : ""}`;
    $("version").textContent = status.extensionVersion || "-";
    $("boss-page").textContent = status.bossTabFound ? (status.contentScriptReady ? "已连接" : "需要刷新页面") : "未发现";
    $("login-state").textContent = ({LOGGED_IN: "已登录", NOT_LOGGED_IN: "未登录", UNKNOWN: "待确认"})[status.loginState] || "待确认";
    $("security-state").textContent = ({NORMAL: "正常", SECURITY_CHECK_REQUIRED: "需要验证", ACCESS_RESTRICTED: "访问受限", UNKNOWN: "待确认"})[status.securityState] || "待确认";
    $("collect").disabled = !canCollect(status);
    $("collect").textContent = status.pageType === "JOB_DETAIL" ? "采集当前职位详情" : "采集当前职位页";
}

async function refresh() {
    const status = await chrome.runtime.sendMessage({type: "REFRESH_EXTENSION_STATUS"});
    render(status || {error: "无法读取扩展状态"});
    return status;
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

$("collect").addEventListener("click", async () => {
    const button = $("collect");
    const resultView = $("collect-result");
    button.disabled = true;
    resultView.className = "collection-result";
    resultView.textContent = "正在只读提取并提交当前页面职位数据……";
    try {
        const result = await chrome.runtime.sendMessage({type: "COLLECT_CURRENT_BOSS_PAGE"});
        if (result?.error) throw new Error(result.error);
        resultView.className = "collection-result ready";
        resultView.textContent = result.pageType === "JOB_DETAIL"
            ? `已补充当前职位详情，新增 ${result.newJobs} 个，更新 ${result.duplicates} 个。`
            : `页面识别 ${result.pageJobsFound} 个，提交 ${result.received} 个，新增 ${result.newJobs} 个，重复 ${result.duplicates} 个，排除外包 ${result.excluded} 个，待复核 ${result.manualReview} 个。`;
    } catch (error) {
        resultView.className = "collection-result error";
        resultView.textContent = error.message;
    } finally {
        await refresh().catch(() => {});
    }
});

$("refresh").addEventListener("click", refresh);
chrome.runtime.sendMessage({type: "GET_EXTENSION_STATUS"}).then(render).catch((error) => render({error: error.message}));

