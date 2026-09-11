# BOSS 自动投递本地 MVP

## 启动

在当前目录执行：

```powershell
mvn spring-boot:run
```

然后使用电脑访问 `http://localhost:18080`。手机访问时，使用电脑局域网 IP，例如 `http://192.168.1.10:18080`。当前管理端未配置登录鉴权，只应在本机或可信局域网使用，不要暴露到公网。

# Chrome 扩展采集（推荐）

职位采集改为由用户当前使用的 Chrome 扩展完成，不再通过独立 Edge 读取职位列表：

1. 在管理端“连接管理”生成一次性配对码。
2. 在 Chrome 加载 `chrome-extension/`，打开扩展弹窗并输入配对码。
3. 在同一个 Chrome 中手动登录 BOSS，打开职位列表并设置搜索条件。
4. 仅在确认页面正常、已登录后，点击管理端“从 Chrome 当前页采集”，或在扩展弹窗点击“采集当前职位页”。
5. 扩展只读取当前页面已加载的职位卡片，最多 50 条，并采集经验、学历、公司规模/行业、福利/职位标签、发布时间、急招和在线状态。
6. 如需职位描述，由用户手动打开单个职位详情，再点击扩展“采集当前职位详情”；扩展不会自动批量打开详情页。
7. 全流程不自动滚动、翻页、刷新、处理验证码，也不会在采集时点击投递。

当前版本完全使用 Chrome 扩展连接 BOSS，不启动、控制或预览独立 Edge，也不使用 Playwright/CDP。已取消单条投递准备入口；候选队列确认后会在同一事务中自动创建投递任务，Chrome 扩展后台定时轮询、领取并按顺序串行执行，用户无需再次点击投递按钮。

扩展只向本机管理端提交职位结构化字段，不读取或上传 Cookie、完整 DOM、密码和验证码。管理端会按 `source + sourceJobId` 去重，并执行外包排除和人工复核规则。
## 当前能力

- 最多配置五个优先城市。
- 城市投递比例必须合计 100%。
- 每日投递总量上限为 150 个岗位。
- 使用最大余数法生成整数城市额度。
- SQLite 本地持久化策略和职位记录，重启后保留。
- 按 `source + sourceJobId` 对职位去重。
- 保存职位描述、公司简介、发布时间等详情字段。
- 明确外包公司/岗位自动排除，并持久化过滤原因。
- 疑似外包岗位进入 `WAIT_CONFIRM`，需要人工复核。
- 响应式布局适配手机和电脑。
- Chrome 扩展从当前 BOSS 列表页只读采集已加载职位的经验、学历、公司规模/行业、福利/职位标签、发布时间、急招和在线状态。
- 用户主动打开单个职位详情后，可只读补充职位描述和详情字段，不自动批量打开详情页。
- 导入标准化职位快照，生成城市主额度和补量候选队列。
- 候选队列确认后自动创建 `delivery_task` 和 `delivery_task_item`，进入投递任务管理。 确认几个就按队列顺序执行几个。
- 兼容 BOSS 城市字段中的区域后缀（例如“北京·朝阳区”），统一匹配到策略城市。
- Chrome 扩展以租约方式领取单条任务；同一时刻只执行一个岗位，结果回报在事务内同步职位、候选队列和任务状态。

## Chrome 扩展接口

```http
GET  /api/extension/status
POST /api/extension/pairing/start
POST /api/extension/client/jobs（由 Chrome 扩展在用户主动采集时调用）
POST /api/extension/unpair

GET  /api/delivery-tasks?plannedDate=YYYY-MM-DD
GET  /api/delivery-tasks/{taskId}/items
GET  /api/delivery-tasks/{taskId}/progress
POST /api/extension/client/delivery-tasks/lease
POST /api/extension/client/delivery-tasks/{itemId}/stage
POST /api/extension/client/delivery-tasks/{itemId}/result
```

职位采集只读取 Chrome 当前页面已加载的职位卡片，提交后由管理端去重并执行外包筛选；不会自动翻页、刷新、点击投递或处理验证码。
## 当前限制

- BOSS 页面结构会变化，首次真实采集仍需在用户完成安全验证后校准选择器。
- 若 BOSS 风控主动清空或关闭页面，系统只能给出 `PAGE_UNAVAILABLE` / `SECURITY_CHECK_REQUIRED` 诊断并等待人工处理，不能保证阻止页面关闭，也不会绕过风控。
- 系统不会读取或保存密码、验证码、Cookie。
- 系统不会处理验证码或绕过风控；自动投递遇到安全验证、访问限制或无法确认结果时会立即停止。
- 已取消单条投递准备入口；自动投递仅执行已经人工确认的候选职位。

## 职位注册接口示例

```json
{
  "source": "BOSS",
  "sourceJobId": "job-001",
  "companyName": "示例科技",
  "companyIntroduction": "软件产品研发",
  "jobName": "Java开发",
  "jobDescription": "负责后端接口开发",
  "city": "北京",
  "salary": "20-30K",
  "jobUrl": "https://www.zhipin.com/job_detail/xxx.html",
  "publishedAt": "更新于今天",
  "experienceRequirement": "3-5年",
  "educationRequirement": "本科",
  "companySize": "100-499人",
  "companyIndustry": "互联网",
  "welfareTags": ["五险一金", "年终奖"],
  "jobTags": ["Java", "Spring Boot"],
  "urgent": true,
  "online": true
}
```

## 本地管理端安全启动


本机启动：

```powershell
java -jar target/boss-auto-apply-0.1.0.jar
```

如确需手机通过可信局域网访问，必须同时开启局域网访问并设置强随机管理令牌：

```powershell
$env:SERVER_ADDRESS = "0.0.0.0"
$env:BOSS_ALLOW_LAN = "true"
$env:BOSS_ADMIN_TOKEN = ([guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N"))
Write-Host "本次管理令牌：$env:BOSS_ADMIN_TOKEN"
java -jar target/boss-auto-apply-0.1.0.jar
```

手机第一次访问接口时会要求输入管理令牌；令牌只保存在当前浏览器标签页的 `sessionStorage`，关闭标签页后清除。不要通过聊天、URL、截图或日志传递令牌。


```powershell
```



## 自动投递任务线路（当前实现）

```text
候选队列确认
    ↓
SQLite 创建 delivery_task / delivery_task_item
    ↓
Chrome 扩展后台定时轮询并领取一条任务
    ↓
扩展单任务串行打开职位详情并执行安全点击
    ↓
扩展回报 SUCCESS / FAILED / UNKNOWN / BLOCKED
    ↓
事务更新 job_record、delivery_queue、delivery_task_item 和 delivery_task
```

管理端只负责确认候选岗位、查看任务状态和展示风险结果，不再通过按钮触发浏览器动作。扩展遇到验证码、安全验证、访问限制或点击后无法确认结果时立即停止；不会处理验证码、绕过风控或盲目重试。

当前真实 BOSS 自动投递现场冒烟尚未执行，本地服务和扩展任务链路已通过自动化测试验证。

