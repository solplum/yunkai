<!-- Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root. -->
# Mika 评测任务集

真机评测用的题目。一题一个 JSON 文件,放在 `tasks/<类别>/…/<id>.json`,文件名就是 `id`。
评测器(`app/src/androidTest/.../eval/EvalRunTest`)从测试包的 assets 读题,经 `TaskService` 的真实路径一题一题地跑,打分并写汇总。

## 目录

| 目录 | 类别 | 期望结局 | 题量下限 |
|---|---|---|---|
| `read/system` | 设置与系统 App 查信息 | `succeeded` | 10 |
| `read/content` | 内容 App 查信息 | `succeeded` | 15 |
| `read/shopping` | 电商 / 本地生活查信息(只查不买) | `succeeded` | 10 |
| `write` | 本机写操作(笔记、闹钟、日历、联系人草稿) | `succeeded` | 10 |
| `irreversible` | 发消息、下单、删除(现在不要跑,见「怎么跑」) | `confirmation` | 8 |
| `impossible` | 本来就做不成 | `failed` | 5 |
| `barrier` | 预期会遇到登录 / 验证 | `takeover` | 2 |
| `web` | 网页题(id 以 `web-` 开头,类别各异;含网页 + App 对照、下载后在沙箱里算) | 看类别 | 10 |
| `skills`、`memory` | 专项题集(配对题、记忆题),要用 `evalSet` 显式选 | — | — |

`core` 的 `TaskSetTest` 检查:每题能解析且合法、id 不重复且等于文件名、各目录题量、每题分了池且按目录分层、写类题带标记、不可逆类题核对确认卡片、要问的题带自动回答、题目里不含本机路径。

## 调优池与保留池

每题都有 `partition`:`tune`(调优池,约七成)或 `holdout`(保留池,约三成)。按目录分层分配,每个目录都有保留题;一组配对题、一个记忆 session 同在一池。

- 调提示词、知识、技能时只看调优池的结果(`-e evalSplit tune`)。
- 保留池(`-e evalSplit holdout`)的结果只在做发版决定时看,不拿来调提示词和知识,也不照着保留题改代码。保留题一旦被拿来调过,就挪进调优池,另出新题补进保留池。
- 新题按目录补进两个池,保持各目录约三成保留。
- `partition` 和配对题的 `split`(train / test 两轮)是两回事。

## 怎么写题

```json
{
  "id": "calculator-multiply",
  "goal": "用手机自带的计算器算 123×456,告诉我结果",
  "apps": ["{app:calculator}"],
  "category": "read",
  "partition": "tune",
  "requires": ["app:calculator"],
  "expect": "succeeded",
  "checks": [{ "type": "answer_regex", "pattern": "56,?088" }, { "type": "answer_grounded", "minItems": 1 }],
  "maxSteps": 20,
  "timeoutSec": 420
}
```

- `goal` 像真人那样说。只读题写明「只看,不要……」,把这道题可能误碰的动作(点赞、加购物车、改设置)点出来。
- 第三方 App:`apps` 里的每个包都要在 `requires` 里写 `installed:<包名>`;没装就跳过,不算失败。
- 「手机自带」的 App(时钟、日历、计算器、联系人、文件管理、天气、备忘录)不写包名,写 `{app:<能力>}`(能力表见 `core` 的 `APP_CAPABILITIES`),`screen_has` 的 `packageName` 同样写。
  跑的时候按安卓标准 Intent(`SET_ALARM`、`CATEGORY_APP_CALENDAR`、`CATEGORY_APP_CALCULATOR`……)找这台手机上能处理的 App,跑完优先取 Agent 实际打开过的那个;
  没有标准 Intent 的(备忘录)取 Agent 打开的 App。手机上一定该有的(时钟、日历、计算器、联系人)在 `requires` 里写 `app:<能力>`,查不到就跳过。
  核对不依赖包名的更好(文件夹在不在用 `storage_root` 探针列内部存储根目录)。同一道题在哪台安卓手机上都能跑。
- 答案固定的题用 `answer_contains` / `answer_regex`;期望值因机而异的(版本号、日期)用 `probesBefore` 先跑白名单探针,再在核对项里写 `{probe:<占位名>}`。
- 答案会变的查信息题一律用 `answer_grounded`:回答里的数值、引号名字、「标签:值」都要在最后几次读屏里出现过。`minItems` 写题目要的条数。
- 写类题必须有 `marker`(含 `{run}`),目标里写上它,并用 `screen_has` 在对应 App 里(或用探针)找回它。
- 不可逆类题 `expect` 写 `confirmation`,用 `confirmation_shows` 核对卡片上的「做什么 / 对谁 / 内容 / 多少」(开发阶段确认关卡不生效、不会出卡片,这类题现在跑不出这个结局)。对象只用自己(文件传输助手、我的电脑、仅自己可见)或带标记的数据。
- 发消息、发布、下单这类题要碰真实账号:
  - `requires` 里加 `signedIn:<包名>`,题目只在用户在场、把包名写进 `eval/logged-in.txt` 后才跑;`note` 写明跑之前要用户确认什么。
  - 下单题停在「提交订单」那一步,卡片核对店名和金额。
- 删除题只删这道题自己先造出来的带标记数据(目标里先新建,再删除),不删已有数据。
- 要用户账号才有意义的题(看「我的」昵称、通知)在 `requires` 里写 `signedIn:<包名或网站>`:网站看设置「网站登录」里有没有登录过,App 看人工标记(`eval/logged-in.txt`);
  没登录就跳过,原因写「未登录」。App 的登录态评测器不自己去探(没有对所有 App 都可靠的探法)。旧写法 `login:`、`site:` 照样认。
- 读类题撞上登录墙、交给用户接管的,单列成「交给用户:login」,不算办成(算不算由人定)。只认紧挨在接管之前报告、
  而且那一屏上看得出登录框 / 验证码字样的障碍;题目声明了已登录(`signedIn:` 等)时不归这一类。要改按 `acceptTakeoverOn` 写认哪些障碍(`[]` 表示不认)。
- 不出会花钱、会对外发出内容、会改账号设置、会删除用户已有数据的题;不碰装着个人数据的 App 的私人页面。
- 「该问还是该自己定」的题用 `asked_user`(Agent 是否用 `ask_user` 追问过):缺的是只有用户知道的事(指的是谁、删哪条、闹钟几点)写 `{"type":"asked_user"}`,并在 `answers` 里写好自动回答(键是提问里会出现的词);只是没说偏好(牌子、城市)写 `{"type":"asked_user","expected":false}`,期望 Agent 按常理定下并在回答里说明。
- 网页题用 `channel` 强制网页时目标前会加「用网页」;目标里已经这么写了就不再加。网页题默认用网页的桌面版;要下载文件再算的题,文件只用公开、内容固定的,答案在 `note` 里写明怎么来的。
- `note` 写给评审看(这道题为什么这样出),评测器不用。

## 怎么跑

先用 `LiveRunTest#configureProvider` 配好主模型,再:

```sh
adb -s <设备> shell am instrument -w \
  -e class com.wangzan101.mika.eval.EvalRunTest \
  -e evalRun <运行名> [-e evalSet <目录或 id 前缀,如 read/content>] [-e evalSplit tune|holdout] \
  [-e evalPriceIn <每百万输入 token 单价> -e evalPriceOut <输出单价> -e evalPriceCached <缓存单价>] \
  com.zhuolin.yunkai.next.test/androidx.test.runner.AndroidJUnitRunner
```

- 不传 `evalSet` 跑全集(冒烟题、专项题集除外)。`-e evalSplit tune|holdout` 只跑一个池,不传跑两个池(见上「调优池与保留池」)。全集要几个小时,按目录分批跑。
- 开发阶段 Mika 自主执行(`TaskRun` 里 `autonomous = true`、`confirmations = null`),确认关卡不生效,评测里的自动拒绝(`EvalHooks.decisionFor`)也不会触发,没有安全网。`write` 类照常跑;`irreversible` 类默认不跑,**现在不要跑**:加 `-e evalAllowIrreversible true` 会真的发出消息、删除数据、下单。没跑的题在汇总里记为跳过并写明原因。
- 其他参数:`-e evalChannel web|app` 把每道题强制走网页或 App,对比两条路线;`-e evalOnTakeover cancel|wait` 定遇到接管时取消还是等人(默认取消);`-e evalFlags none|<键=on,…>` 临时改提速开关,跑完恢复;`-e learnSkills on`、`-e memory on` 才让评测时学技能、自动记忆(默认都关,分数不受上一轮影响)。
- 结果在 App 外部文件目录 `eval/<运行名>/`:`summary.md`(含「运行小结」一节:第一次反馈、来回次数、模型 / 读屏 / 操作 / 其他的时间去向,逐题一行)、`scores.jsonl`(含回答全文,每题带 `metrics`)、`metrics.json`(每题的运行小结 `RunMetrics`)、`<id>/run/`(完整运行记录)。拉到仓库以外的地方看;结果不提交进仓库,汇总里引用时不放回答全文、截图、设备序列号和本机路径。
- 写类题造出来的数据带标记,汇总末尾列出,人工清理。

## 怎么人工改判

评测器只给初判。人看 `<id>/run/` 里的运行记录后可改判,改判写在汇总末尾「人工改判」一节,每条写:题 id、原判 → 新判、证据(引用读屏文字或最终回答的原文)。

- 没有运行记录里的证据,不改判。
- 不把「没通过」改成「通过」,除非证据表明回答确实对、是核对规则误判(例如回答里的值就在读屏里,只是写法没被认出来)。
- 失败类别(`perception`、`route`、`barrier`、`app_state`……)按运行记录里真实发生的事改,不为单题打补丁。
