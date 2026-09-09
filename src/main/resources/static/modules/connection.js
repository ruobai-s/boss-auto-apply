(() => {
    const $ = (id) => document.getElementById(id);
    const {requestJson} = window.BossApi;
    let refreshTimer = null;

    const stateLabels = {
        EXTENSION_UNPAIRED: "未配对",
        EXTENSION_OFFLINE: "扩展离线",
        BOSS_TAB_NOT_FOUND: "未发现 BOSS 页面",
        CONTENT_SCRIPT_UNAVAILABLE: "页面脚本未生效",
        SECURITY_CHECK_REQUIRED: "需要人工处理安全验证",
        NOT_LOGGED_IN: "尚未确认登录",
        READY: "连接就绪"
    };

    const pageLabels = {
        NONE: "无页面",
        UNKNOWN: "未知页面",
        HOME: "首页",
        LOGIN: "登录页",
        JOB_LIST: "职位列表",
        JOB_DETAIL: "职位详情",
        CHAT: "沟通页面",
        SECURITY: "安全验证页"
    };

    const loginLabels = {LOGGED_IN: "已登录", NOT_LOGGED_IN: "未登录", UNKNOWN: "待确认"};
    const securityLabels = {
        NORMAL: "正常",
        SECURITY_CHECK_REQUIRED: "需要验证",
        ACCESS_RESTRICTED: "访问受限",
        UNKNOWN: "待确认"
    };

    function setText(id, value) {
        $(id).textContent = value || "-";
    }

    function localTime(value) {
        if (!value) return "-";
        const date = new Date(value);
        return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", {hour12: false});
    }

    function render(status) {
        const badge = $("extension-state");
        badge.textContent = stateLabels[status.state] || status.state || "状态未知";
        badge.className = `status-pill ${status.ready ? "status-ok" : status.securityBlocked ? "status-danger" : "status-warn"}`;
        setText("extension-version", status.extensionVersion);
        setText("extension-online", status.online ? "在线" : status.paired ? "离线" : "未配对");
        setText("extension-boss-tab", status.bossTabFound ? "已发现" : "未发现");
        setText("extension-content-script", status.contentScriptReady ? "已生效" : "未生效");
        setText("extension-page-type", pageLabels[status.pageType] || status.pageType);
        setText("extension-login-state", loginLabels[status.loginState] || status.loginState);
        setText("extension-security-state", securityLabels[status.securityState] || status.securityState);
        setText("extension-last-heartbeat", localTime(status.lastHeartbeatAt));
        setText("extension-message", status.message || "暂无连接说明");
        $("unpair-extension").disabled = !status.paired;
    }

    function renderError(error) {
        const badge = $("extension-state");
        badge.textContent = "读取失败";
        badge.className = "status-pill status-danger";
        setText("extension-message", error.message);
    }

    async function refreshStatus() {
        try {
            render(await requestJson("/api/extension/status", {cache: "no-store"}));
        } catch (error) {
            renderError(error);
        } finally {
            if (refreshTimer) clearTimeout(refreshTimer);
            // 管理端仅低频读取状态，避免不必要的本地数据库轮询。
            refreshTimer = setTimeout(refreshStatus, 30000);
        }
    }

    async function startPairing() {
        const button = $("start-extension-pairing");
        button.disabled = true;
        try {
            const pairing = await requestJson("/api/extension/pairing/start", {method: "POST"});
            setText("extension-pairing-code", pairing.pairingCode);
            setText("extension-pairing-expiry", `有效期至 ${localTime(pairing.expiresAt)}`);
            setText("extension-message", "请打开 Chrome 扩展，在弹窗中输入一次性配对码。");
        } catch (error) {
            renderError(error);
        } finally {
            button.disabled = false;
        }
    }

    async function unpair() {
        if (!window.confirm("确定解除当前 Chrome 扩展配对吗？旧令牌会立即失效。")) return;
        const button = $("unpair-extension");
        button.disabled = true;
        try {
            render(await requestJson("/api/extension/unpair", {method: "POST"}));
            setText("extension-pairing-code", "尚未生成");
            setText("extension-pairing-expiry", "配对码只在内存中短时有效");
        } catch (error) {
            renderError(error);
        } finally {
            button.disabled = false;
        }
    }

    $("start-extension-pairing").addEventListener("click", startPairing);
    $("refresh-extension-status").addEventListener("click", refreshStatus);
    $("unpair-extension").addEventListener("click", unpair);
    refreshStatus();
})();
