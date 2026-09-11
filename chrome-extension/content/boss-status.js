const SECURITY_URL_MARKERS = ["/403.html", "/web/passport/zp/403", "code=32", "/verify", "captcha", "security-check"];
const LOGIN_URL_MARKERS = ["/web/user", "/passport/", "ka=header-login"];
const MAX_JOBS = 50;
const CARD_SELECTORS = ["li.job-card-box", "[ka=job-card]", ".job-card-wrapper", "li.job-card-wrapper"];
const LINK_SELECTOR = 'a.job-name[href*="/job_detail/"], a[href*="/job_detail/"]';
const APPLY_BUTTON_SELECTORS = [".btn-startchat", "button.btn-startchat", "[ka=job-detail-chat]", "[ka*=job-detail-chat]"];
const APPLY_SUCCESS_TEXTS = ["已投递", "投递成功", "已沟通"];
const APPLY_BLOCKED_TEXTS = ["安全验证", "验证码", "访问过于频繁", "操作频繁", "异常访问"];
const EXPERIENCE_PATTERN = /(经验不限|不限经验|无需经验|应届生|在校生|\d+(?:-\d+)?年(?:以上)?)/;
const EDUCATION_PATTERN = /(学历不限|不限学历|初中及以下|高中|中专|中技|大专|本科|硕士|博士)/;
const COMPANY_SIZE_PATTERN = /(少于\d+人|\d+-\d+人|\d+人以上)/;
const FINANCING_PATTERN = /^(未融资|天使轮|A轮|B轮|C轮|D轮及以上|战略融资|已上市|不需要融资)$/i;
const WELFARE_PATTERN = /(五险|一金|补充医疗|年终奖|股票期权|加班补助|全勤奖|餐补|房补|交通补助|节日福利|通讯补贴|零食下午茶|定期体检|带薪年假|员工旅游|包吃|包住|免费班车|团建|双休|弹性工作)/;
const PUBLISHED_PATTERN = /(发布于|更新于|发布|更新|刚刚|今天|昨日|\d+天前|\d{1,2}月\d{1,2}日|\d{4}[-/.年]\d{1,2}[-/.月]\d{1,2}日?)/;

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

function normalizeText(value, maxLength) {
    const normalized = String(value || "").replace(/\s+/g, " ").trim();
    return normalized.length > maxLength ? normalized.slice(0, maxLength) : normalized;
}

function firstText(root, selectors, maxLength) {
    for (const selector of selectors) {
        const element = root.querySelector(selector);
        const text = normalizeText(element?.textContent, maxLength);
        if (text) return text;
    }
    return "";
}

function allTexts(root, selectors, maxLength = 50, maxItems = 20) {
    const values = [];
    for (const selector of selectors) {
        for (const element of root.querySelectorAll(selector)) {
            const text = normalizeText(element.textContent, maxLength);
            if (text && !values.includes(text)) values.push(text);
            if (values.length >= maxItems) return values;
        }
    }
    return values;
}

function firstMatching(values, pattern) {
    return values.find((value) => pattern.test(value)) || "";
}

function firstPatternValue(values, pattern) {
    for (const value of values) {
        const match = value.match(pattern);
        if (match) return normalizeText(match[1] || match[0], 100);
    }
    return "";
}

// 只在页面明确给出结论时返回布尔值，未识别到状态时保留 null，避免误判。
function parseTriStateFlag(text, positivePattern, negativePattern) {
    // 先判断否定文本，避免“非急招”“非在线”先被正向关键词命中。
    if (negativePattern.test(text)) return false;
    if (positivePattern.test(text)) return true;
    return null;
}

// 详情页的“在线”状态只从招聘者信息区域读取，避免把页面其他位置的“在线”文本误判为招聘者在线。
function collectOnlineStatus(root) {
    const onlineText = normalizeText(allTexts(root, [
        ".job-boss-info", ".sider-boss", ".boss-info", ".recruiter-info",
        "[class*=boss-info]", "[class*=recruiter]", "[class*=online-status]", "[class*=online]"
    ], 100, 30).join(" "), 1000);
    return parseTriStateFlag(onlineText, /(?:招聘者|BOSS|HR)?在线/, /(离线|不在线|未在线|非在线)/);
}

// 兼容历史采集数据中带“.html”后缀的职位编号，比较时统一成平台编号。
function normalizeSourceJobId(value) {
    let text = String(value || "").trim();
    try {
        text = decodeURIComponent(text);
    } catch (error) {
        // 编号不是 URL 编码时继续使用原始文本，不因解码失败中断投递。
    }
    return text.replace(/\.html$/i, "");
}

function parseJobLink(link) {
    if (!link) return null;
    try {
        const url = new URL(link.getAttribute("href") || link.href, location.origin);
        if (url.hostname !== "www.zhipin.com") return null;
        const match = url.pathname.match(/\/job_detail\/([^/?#]+)/i);
        if (!match) return null;
        const sourceJobId = normalizeText(normalizeSourceJobId(match[1]), 128);
        if (!sourceJobId) return null;
        return {
            sourceJobId,
            jobUrl: `https://www.zhipin.com/job_detail/${encodeURIComponent(sourceJobId)}.html`
        };
    } catch (error) {
        return null;
    }
}

function parseCurrentJobLink() {
    // BOSS 使用 SPA 跳转时 canonical 可能暂时保留旧职位，当前地址才是实际打开的页面。
    return parseJobLink({
        getAttribute: () => location.href,
        href: location.href
    }) || parseJobLink(document.querySelector('link[rel="canonical"]'));
}

function collectCardMetadata(card) {
    const jobMeta = allTexts(card, [
        ".job-info .tag-list li", ".job-info .job-info-tag li", ".job-card-left .tag-list li",
        "[class*=job-info] [class*=tag-list] li", "[class*=job-limit] span"
    ]);
    const companyMeta = allTexts(card, [
        ".company-info .company-tag-list li", ".company-info .tag-list li",
        "[class*=company-info] [class*=tag-list] li", "[class*=company-tag] li"
    ]);
    const cardTags = allTexts(card, [
        ".job-card-footer .tag-list li", ".job-card-footer [class*=tag] span",
        ".job-card-footer [class*=tag] li", "[class*=job-keyword] li", "[class*=job-keyword] span"
    ]);
    const welfareTags = cardTags.filter((tag) => WELFARE_PATTERN.test(tag)).slice(0, 20);
    const jobTags = cardTags.filter((tag) => !WELFARE_PATTERN.test(tag)
        && !EXPERIENCE_PATTERN.test(tag) && !EDUCATION_PATTERN.test(tag)).slice(0, 20);
    const companyIndustry = companyMeta.find((value) => !COMPANY_SIZE_PATTERN.test(value)
        && !FINANCING_PATTERN.test(value)) || "";
    const publishedAt = firstText(card, [
        ".job-card-footer .job-status", ".job-card-footer .info-public", ".job-card-footer [class*=time]",
        "[class*=publish-time]", "[class*=update-time]", "[class*=job-status]"
    ], 100) || firstMatching(allTexts(card, ["span", "small"], 100, 80), PUBLISHED_PATTERN);
    const visibleText = normalizeText(card.innerText, 6000);
    return {
        experienceRequirement: firstPatternValue(jobMeta, EXPERIENCE_PATTERN),
        educationRequirement: firstPatternValue(jobMeta, EDUCATION_PATTERN),
        companySize: firstPatternValue(companyMeta, COMPANY_SIZE_PATTERN),
        companyIndustry,
        welfareTags,
        jobTags,
        publishedAt,
        urgent: parseTriStateFlag(visibleText, /(急招|急聘)/, /(非急招|非急聘|不急招|不急聘)/),
        // 列表卡片自身已是单个职位范围，可以直接读取；没有状态时不作推断。
        online: parseTriStateFlag(visibleText, /(?:招聘者|BOSS|HR)?在线/, /(离线|不在线|未在线|非在线)/)
    };
}

function collectCurrentJobs() {
    const status = currentStatus();
    if (status.pageType !== "JOB_LIST") throw new Error("只能采集当前 BOSS 职位列表页");
    if (status.loginState !== "LOGGED_IN") throw new Error("尚未确认 BOSS 登录状态，不能采集职位");
    if (status.securityState !== "NORMAL") throw new Error("检测到访问限制或安全验证，已停止职位采集");

    const cards = Array.from(document.querySelectorAll(CARD_SELECTORS.join(","))).slice(0, MAX_JOBS);
    const jobs = [];
    const seen = new Set();
    for (const card of cards) {
        const link = card.matches?.(LINK_SELECTOR) ? card : card.querySelector(LINK_SELECTOR);
        const parsedLink = parseJobLink(link);
        if (!parsedLink || seen.has(parsedLink.sourceJobId)) continue;
        const jobName = firstText(card, ["a.job-name", ".job-name", "[class*=job-name]", "h3", "h4"], 200);
        if (!jobName) continue;
        const metadata = collectCardMetadata(card);
        seen.add(parsedLink.sourceJobId);
        jobs.push({
            source: "BOSS",
            sourceJobId: parsedLink.sourceJobId,
            companyName: firstText(card, [".company-name", "[class*=company-name]", ".company-text", "span.boss-name"], 200),
            companyIntroduction: "",
            jobName,
            jobDescription: "",
            city: firstText(card, ["span.company-location", ".company-location", ".job-area", "[class*=job-area]"], 100),
            salary: firstText(card, ["span.job-salary", ".job-salary", ".salary", "[class*=salary]"], 100),
            jobUrl: parsedLink.jobUrl,
            ...metadata
        });
    }
    if (!jobs.length) {
        throw new Error("当前页面未识别到职位卡片，请确认职位列表已加载并刷新扩展后重试");
    }
    return {jobs, capturedAt: new Date().toISOString(), status};
}

function findSectionByHeading(labels) {
    const headings = document.querySelectorAll("h1, h2, h3, h4, .title, [class*=title]");
    for (const heading of headings) {
        const headingText = normalizeText(heading.textContent, 100);
        if (!labels.some((label) => headingText.includes(label))) continue;
        return heading.closest("section, .job-detail-section, .job-sec, .detail-section") || heading.parentElement;
    }
    return null;
}

function sectionText(labels, fallbackSelectors, maxLength) {
    const section = findSectionByHeading(labels);
    if (section) {
        const textRoot = section.querySelector(".job-sec-text, .text, .content, [class*=content]") || section;
        let text = normalizeText(textRoot.innerText, maxLength);
        for (const label of labels) text = text.replace(new RegExp(`^${label}\\s*`), "");
        if (text) return text;
    }
    return firstText(document, fallbackSelectors, maxLength);
}

function sectionTags(labels, fallbackSelectors) {
    const section = findSectionByHeading(labels);
    const tags = section ? allTexts(section, ["li", ".tag", "span"], 50, 30) : [];
    const fallback = allTexts(document, fallbackSelectors, 50, 30);
    return [...new Set([...tags, ...fallback])]
        .filter((tag) => !labels.some((label) => tag === label))
        .slice(0, 20);
}

function collectCurrentJobDetail() {
    const status = currentStatus();
    if (status.pageType !== "JOB_DETAIL") throw new Error("只能采集当前 BOSS 职位详情页");
    if (status.loginState !== "LOGGED_IN") throw new Error("尚未确认 BOSS 登录状态，不能采集职位详情");
    if (status.securityState !== "NORMAL") throw new Error("检测到访问限制或安全验证，已停止职位详情采集");

    const parsedLink = parseCurrentJobLink();
    if (!parsedLink) throw new Error("当前详情页缺少可识别的职位编号");
    const detailMeta = allTexts(document, [
        ".job-banner .job-primary p span", ".job-banner .job-primary p", ".job-primary .job-limit span",
        ".job-primary .text", "[class*=job-limit] span"
    ], 100, 30);
    const companyMeta = allTexts(document, [
        ".sider-company p span", ".company-info p span", ".sider-company p", ".company-info p",
        ".company-info [class*=tag]", ".company-card [class*=tag]"
    ], 200, 30);
    const rawTags = sectionTags(["职位标签", "职位关键词", "技能要求"], [
        ".job-keyword-list li", ".job-keyword-list span", ".job-tags span", "[class*=job-keyword] li"
    ]);
    const welfareTags = sectionTags(["职位福利", "福利待遇", "公司福利"], [
        "[class*=welfare] li", "[class*=welfare] span", ".job-boss-info .job-tags span"
    ]).filter((tag) => WELFARE_PATTERN.test(tag));
    const jobTags = rawTags.filter((tag) => !WELFARE_PATTERN.test(tag)
        && !EXPERIENCE_PATTERN.test(tag) && !EDUCATION_PATTERN.test(tag));
    const visibleText = normalizeText(document.body?.innerText, 20000);
    const companyIndustry = companyMeta.find((value) => !COMPANY_SIZE_PATTERN.test(value)
        && !FINANCING_PATTERN.test(value) && value.length <= 200) || "";
    const job = {
        source: "BOSS",
        sourceJobId: parsedLink.sourceJobId,
        companyName: firstText(document, [
            ".sider-company .company-name", ".company-info .company-name", ".job-company .company-name",
            "a[ka*=company-name]", "[class*=company-name]"
        ], 200),
        companyIntroduction: sectionText(["公司介绍", "公司简介"], [
            ".company-info-box .company-desc", ".company-info .company-desc", ".sider-company .company-desc"
        ], 5000),
        jobName: firstText(document, [".job-banner .name h1", ".job-primary .name h1", ".job-primary h1", "h1"], 200),
        jobDescription: sectionText(["职位描述", "工作内容", "岗位职责"], [
            ".job-detail-section .job-sec-text", ".job-detail .job-sec-text", ".job-sec-text"
        ], 10000),
        city: firstText(document, [
            ".job-banner .job-location", ".job-primary .job-location", ".location-address", "[class*=job-location]"
        ], 100),
        salary: firstText(document, [".job-banner .salary", ".job-primary .salary", "[class*=job-salary]", ".salary"], 100),
        jobUrl: parsedLink.jobUrl,
        publishedAt: firstText(document, [
            ".job-detail-section .job-status", ".job-detail-section [class*=time]", "[class*=publish-time]",
            "[class*=update-time]", "[class*=job-status]"
        ], 100) || firstMatching(detailMeta, PUBLISHED_PATTERN),
        experienceRequirement: firstPatternValue(detailMeta, EXPERIENCE_PATTERN),
        educationRequirement: firstPatternValue(detailMeta, EDUCATION_PATTERN),
        companySize: firstPatternValue(companyMeta, COMPANY_SIZE_PATTERN),
        companyIndustry,
        welfareTags: welfareTags.slice(0, 20),
        jobTags: jobTags.slice(0, 20),
        urgent: parseTriStateFlag(visibleText, /(急招|急聘)/, /(非急招|非急聘|不急招|不急聘)/),
        online: collectOnlineStatus(document)
    };
    if (!job.jobName) throw new Error("当前详情页未识别到职位名称，请刷新页面后重试");
    return {jobs: [job], capturedAt: new Date().toISOString(), status};
}

function waitMilliseconds(milliseconds) {
    return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

function pageTextContains(values) {
    const bodyText = normalizeText(document.body?.innerText, 20000);
    return values.some((value) => bodyText.includes(value));
}

function findApplyButton() {
    for (const selector of APPLY_BUTTON_SELECTORS) {
        const button = document.querySelector(selector);
        if (button && !button.disabled && button.getClientRects().length > 0) return button;
    }
    return null;
}

async function waitForJobDetail(expectedSourceJobId, timeoutMs = 8000) {
    const expected = normalizeSourceJobId(expectedSourceJobId);
    const deadline = Date.now() + timeoutMs;
    let lastLink = null;
    while (Date.now() < deadline) {
        const status = currentStatus();
        if (status.securityState !== "NORMAL") throw new Error("检测到访问限制或安全验证，已停止自动投递");
        lastLink = parseCurrentJobLink();
        const actual = normalizeSourceJobId(lastLink?.sourceJobId);
        if (status.pageType === "JOB_DETAIL" && actual) {
            if (expected && expected !== actual) throw new Error("职位详情页与待投递岗位不一致");
            if (status.loginState === "LOGGED_IN") return lastLink;
        }
        await waitMilliseconds(300);
    }
    if (lastLink?.sourceJobId && expected && normalizeSourceJobId(lastLink.sourceJobId) !== expected) {
        throw new Error("职位详情页与待投递岗位不一致");
    }
    throw new Error("职位详情页加载后未能确认岗位身份或登录状态");
}

async function applyCurrentJob(expectedSourceJobId) {
    const status = currentStatus();
    if (status.securityState !== "NORMAL") throw new Error("检测到访问限制或安全验证，已停止自动投递");

    // BOSS 详情页可能仍在 SPA 异步切换，统一等待页面身份和登录状态稳定后再操作。
    await waitForJobDetail(expectedSourceJobId);
    if (pageTextContains(APPLY_BLOCKED_TEXTS)) throw new Error("页面出现安全验证或访问限制，已停止自动投递");
    // “立即沟通”“继续沟通”“打招呼”是可操作按钮文案，不能作为已成功的依据。
    // 只有明确的已投递、投递成功或已沟通状态才允许跳过点击。
    if (pageTextContains(APPLY_SUCCESS_TEXTS)) return {success: true, alreadyApplied: true, message: "职位已处于投递或沟通状态"};
    const button = findApplyButton();
    if (!button) throw new Error("未识别到可用的投递按钮，已停止自动投递");
    const buttonText = normalizeText(button.textContent, 50);
    button.click();
    await waitMilliseconds(1200);

    const nextStatus = currentStatus();
    if (nextStatus.securityState !== "NORMAL" || pageTextContains(APPLY_BLOCKED_TEXTS)) {
        throw new Error("点击后出现安全验证或访问限制，已停止自动投递");
    }
    if (pageTextContains(APPLY_SUCCESS_TEXTS) || normalizeText(button.textContent, 50) !== buttonText) {
        return {success: true, alreadyApplied: false, message: "已点击投递并检测到页面状态变化"};
    }
    throw new Error("点击后无法确认投递结果，已停止以避免重复操作");
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message?.type === "BOSS_EXTENSION_STATUS") {
        sendResponse(currentStatus());
        return false;
    }
    if (message?.type === "BOSS_COLLECT_JOBS") {
        try {
            sendResponse(collectCurrentJobs());
        } catch (error) {
            sendResponse({error: error.message});
        }
        return false;
    }
    if (message?.type === "BOSS_COLLECT_JOB_DETAIL") {
        try {
            sendResponse(collectCurrentJobDetail());
        } catch (error) {
            sendResponse({error: error.message});
        }
        return false;
    }
    if (message?.type === "BOSS_APPLY_CURRENT_JOB") {
        applyCurrentJob(String(message.sourceJobId || "")).then(sendResponse)
            .catch((error) => sendResponse({error: error.message}));
        return true;
    }
    return false;
});



