(() => {
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

    window.BossApi = Object.freeze({securityHeaders, secureFetch, requestJson});
})();
