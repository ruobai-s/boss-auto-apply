const SECURITY_URL_MARKERS = ["/403.html", "/web/passport/zp/403", "code=32", "/verify", "captcha", "security-check"];
const LOGIN_URL_MARKERS = ["/web/user", "/passport/", "ka=header-login"];

function includesAny(value, markers) {
    return markers.some((marker) => value.includes(marker));
}

function pageType(url) {
    if (includesAny(url, SECURITY_URL_MARKERS)) return "SECURITY";
    if (includesAny(url, LOGIN_URL_MARKERS)) return "LOGIN";
    if (url.includes("/web/geek/jobs")) return "JOB_LIST";
    if (url.includes("/job_detail/")) return "JOB_DETAIL";
    if (url.includes("/web/geek/chat")) return "CHAT";
    return "HOME";
}

function securityState(url, title) {
    if (url.includes("/403.html") || url.includes("/web/passport/zp/403") || url.includes("code=32")) {
        return "ACCESS_RESTRICTED";
    }
    if (includesAny(url, ["/verify", "captcha", "security-check"])
        || title.includes("安全验证") || title.includes("验证码")) {
        return "SECURITY_CHECK_REQUIRED";
    }
    return "NORMAL";
}

function loginState(url, security) {
    if (security !== "NORMAL") return "UNKNOWN";
    if (includesAny(url, LOGIN_URL_MARKERS)) return "NOT_LOGGED_IN";

    // 只检查页头中的登录入口和个人入口，不读取完整页面正文。
    const loginEntry = document.querySelector(
        'a[href*="ka=header-login"], a[href*="/web/user"], .user-nav .btn-login, [class*="login-btn"]');
    const accountEntry = document.querySelector(
        '.user-nav .nav-figure, .nav-figure, [class*="user-avatar"], [class*="geek-avatar"], a[href*="/web/geek/recommend"]');
    if (loginEntry && !accountEntry) return "NOT_LOGGED_IN";
    if (accountEntry && !loginEntry) return "LOGGED_IN";
    return "UNKNOWN";
}

function currentStatus() {
    const url = location.href.toLowerCase();
    const title = String(document.title || "").toLowerCase();
    const security = securityState(url, title);
    const login = loginState(url, security);
    let message = "BOSS 页面连接脚本已生效";
    if (security === "ACCESS_RESTRICTED") message = "检测到 BOSS 访问限制";
    else if (security === "SECURITY_CHECK_REQUIRED") message = "检测到安全验证，需要用户人工处理";
    else if (login === "NOT_LOGGED_IN") message = "检测到尚未登录 BOSS";
    else if (login === "UNKNOWN") message = "页面已连接，但登录状态尚不能确认";
    else message = "BOSS 页面和登录状态正常";
    return {pageType: pageType(url), loginState: login, securityState: security, message};
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message?.type === "BOSS_EXTENSION_STATUS") {
        sendResponse(currentStatus());
    }
});
