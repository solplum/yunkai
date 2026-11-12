# 沙箱脚本

技能和网站知识可以带脚本,放在各自目录的 `scripts/` 下。打包后这些目录出现在沙箱的 `/opt/mika/skills/` 下,
由 `run_bundled_script` 在沙箱里运行。参数:

- `source`:脚本所在的目录,也就是 `knowledge_read`、`skill_read` 结果里给出的 `scriptSource`,例如 `skills/documents`;
- `script`:目录里的脚本路径,例如 `scripts/extract_pdf.py`;
- `args`:参数数组(最多 50 个),原样交给脚本,中间不经过 shell;
- `timeoutSec`:时限,1–600 秒,默认 60。

解释器按扩展名选:`.py` 用 python3,`.mjs`、`.js` 用 node,`.sh` 用 sh(`ScriptManifest.interpreterFor`)。

目前仓库里有一个脚本:

| 脚本 | 位置 | 做什么 | 需要什么 |
|---|---|---|---|
| `scripts/extract_pdf.py` | `skills/documents/` | 从文本型 PDF 取字,可按页、看元数据、写入文件;扫描件不处理 | Alpine 包 `py3-pypdf` |

以上位置都在 `core/src/main/resources/` 下。脚本都带 `--help`,出错时以非 0 退出,并在标准错误写一句原因。

## `scripts/manifest.json`

每个带脚本的目录有一份 `scripts/manifest.json`,是一个数组,每个脚本一项:

```json
[
  {"file": "scripts/extract_pdf.py", "runnable": true, "needs": ["apk:py3-pypdf"]}
]
```

- `file`:脚本相对该目录的路径;不是目录内相对路径的项会被丢掉。
- `runnable`:能否在沙箱里运行。`false` 的脚本只用来说明缺什么,`run_bundled_script` 会拒绝。
- `reason`:可选,`runnable` 为 `false` 时给出的原因。
- `needs`:依赖列表。`apk:<包名>` 表示需要这个 Alpine 包(必须在 `SANDBOX_PACKAGES` 里);`net` 表示要联网。
- `network`:是否需要联网。为 `true` 而设置 → 高级里的「允许脚本联网」关着(默认关)时,`run_bundled_script` 会拒绝运行并提示去打开。

解析与「此刻能不能跑」的判断在 `core/.../knowledge/ScriptManifest.kt`;清单、文档里提到的脚本与脚本文件是否对得上,由 `BundledScriptsManifestTest` 核对。

## 运行环境

沙箱基于 Alpine 3.22,`SANDBOX_PACKAGES`(`core/.../cap/sandbox/SandboxPackages.kt`)列出随 APK 自带的软件包:python3、nodejs、py3-pillow、py3-pypdf。其中 py3-pypdf 供 PDF 取字脚本用;py3-pillow 现在没有内置技能脚本用到,包本身先留着。
这些包在构建时装进随 APK 自带的运行环境(`tools/sandbox/build-runtime.py`,见 `tools/sandbox/README.md`);包有变化时,运行环境的版本随之改变,手机上启动时会自动重新解包。
