# 内置知识与技能的格式

知识与技能随 App 发布,放在 `core/src/main/resources/` 下的 `knowledge/apps/`、`knowledge/sites/`、`skills/`;用户自己的个人技能由 Mika 在手机上自学起草(见 `docs/architecture.md`「知识与技能」)。

三种文件,各管一件事:

| 文件 | 管什么 | 不管什么 |
|---|---|---|
| `knowledge/apps/<id>/APP.md` | 这个 App 是什么、界面怎么组织、有哪些功能、能看到什么 | 行为规定、做事方法 |
| `knowledge/sites/<主域名>/SITE.md` | 这个网站是什么、页面和网址怎么组织、有哪些功能、公开接口 | 行为规定、做事方法 |
| `skills/<id>/SKILL.md` | 一类事怎么做(可复用的方法,可跨 App/网站) | 某个行业、某个品类的专门做法 |

## 共同要求

- **只写事实**。授权、确认、停止条件、「不要编造」「交给用户」这类规定归通用准则和审批层,知识里不写。
- **用 Mika 自己的话写**。这类格式词都不要:「本批」「证据」「总则」「通道」「scope/stops/features」。
- **说明不引用技能**。技能可以点名 App/网站(`app:<id>`、`site:<域名>`),App/网站说明里不出现「按 XX 技能」。
- **现象写成现象**。「首页网格里的『外卖』和底部标签的『外卖』是两个入口,前者进外卖频道」,而不是「勿点底部标签」。
- 讲界面时说「界面」「页面」「入口」「按钮」,不说「看屏幕」。
- 正文不超过 2500 字;大段资料(站名代码表、网址格式、搜索语法)放 `references/`,正文里写清什么时候去读哪个附件。
- 包名、域名、网址、接口地址、字段名这些事实原样保留;拿不准的事实宁可不写。

## APP.md

```markdown
---
name: bilibili                     # 目录名,小写字母数字连字符
display: 哔哩哔哩
description: 视频、直播与动态(不超过 60 字,说这个 App 能做什么)
packages: ["tv.danmaku.bili", "com.bilibili.app.in"]
package_aliases: ["国际版 | com.bilibili.app.in"]     # 可选:某个叫法对应哪个包
aliases: ["B站", "哔哩哔哩"]
sites: ["bilibili.com"]            # 可选:对应的网站知识
open: 哔哩哔哩                       # 打开 App 时用的名字
links: ["首页 | bilibili://... | tv.danmaku.bili"]       # 可选
---
## 是什么
一两句:这个 App 提供什么服务,有几个独立的包时各是什么。

## 界面
主要页面、底部标签、搜索入口、常用功能在哪个位置。

## 功能
- **功能名**:从哪进去;要不要登录;能看到哪些信息(价格、状态、字段)。

## 已知现象
容易混的入口、常见弹窗、加载或刷新上的特点。没有就不写这一节。
```

## SITE.md

```markdown
---
name: bilibili.com                  # 主域名
display: 哔哩哔哩
description: 视频、搜索与用户主页
domains: ["bilibili.com", "b23.tv"]
aliases: ["B站", "bilibili"]
apps: ["bilibili"]                   # 可选:对应的 App 知识
open: bilibili.com                  # 打开时用的网址
mode: desktop                      # desktop / mobile:用哪种页面
pace: careful                      # 可选:这个网站限制浏览快慢(看得太快、太多会被拦)
links: ["..."]                     # 可选
---
## 是什么

## 页面与网址
主要页面、网址的写法(带参数的格式)、搜索入口。

## 功能
- **功能名**:在哪个页面;要不要登录;能看到哪些信息。

## 接口
可选:公开接口的地址、参数、返回格式。

## 已知现象
可选。
```

`pace` 是这个网站的一条事实,不是给模型的规定:只能写 `careful`,表示这个网站限制一个访客浏览的快慢(翻页、点开详情太快或太多会被拦、要验证);不写就是没有这种限制。写了 `careful` 的网站,Mika 在同一个网站上换页之前停得更久(约 10–22 秒,看得越多越久,至多 30 秒),一个任务里在这个网站上看的页数上限也更低(普通网站 20 页,这类网站 12 页;用户答应接着看后重新计数;只管浏览,在这个网站上做成过发送、提交一类的对外动作之后就不再设)。构建时校验:只有网站能写,值写错构建失败。

网站目录下可以有 `scripts/`:站点命令(带 `/* mika-command` 头部的 .js)或沙箱脚本(列在 `scripts/manifest.json` 里)。

## SKILL.md

和自学沉淀的个人技能同一种格式(见 `prompts/roles/skill_draft.md`):

```markdown
---
name: pdf-text
display: 取 PDF 文字
description: 一句话:什么情况下用这份技能
aliases: ["读 PDF", "PDF 转文字"]
apps: []                           # 可选:用到的 App 知识名
sites: []                          # 可选:用到的网站知识名
---
## 入口
## 步骤
## 容易出错的地方
## 怎样算办成(核实证据)
## 什么时候停下交给用户
```

技能写「这类事」怎么做,不分行业和品类;可以带 `references/` 附件和 `scripts/` 脚本。

## 头部字段的去留

去掉:`risk`、`status`、`updated`、`verified_on`(运行时不用的元数据)。
`terms` 只留给技能用;App 和网站靠 `aliases`。

## Agent 怎么用这些文件

知识(App、网站)和技能各有一对「查找 / 阅读」工具:

| 工具 | 查什么 | 参数 |
|---|---|---|
| `knowledge_search` | App 与网站知识,最多 10 条 `{name, display, kind, description}` | `query`;可选 `kind`(`app` / `site`) |
| `knowledge_read` | 读一份 App 或网站知识,或它的附件 | `name`(可写成 `app:<id>`、`site:<域名>`);可选 `file_path`(`references/` 里的附件) |
| `skill_search` | 技能,内置的和用户的个人技能一起查,最多 10 条 | `query` |
| `skill_read` | 读一份技能全文,或它的附件 | `name`(可写成 `skill:<id>`、个人技能 `personal:<id>`);可选 `file_path` |

- 进入某个 App 或网站时,它的知识正文已经自动给了模型,`knowledge_read` 主要用来读附件,或读还没进入的 App/网站。
- `knowledge_read`、`skill_read` 的结果会列出可运行的脚本和它们的 `scriptSource`;用 `run_bundled_script`(`source`、`script`、`args`)在沙箱里运行,见 `docs/sandbox/skill-scripts.md`。
- 网站的站点命令用 `web_run_site_command` 按名字运行,和 `web_evaluate_js` 一样只读。
- 知识、技能、提示词里出现的工具名必须是现在登记的工具,由测试检查。

