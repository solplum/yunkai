# 桌面检查「用 ChatGPT 登录」

上手机之前,先在电脑(macOS)上把 OpenAI 那一侧走通。这个检查用的是 App 同一套代码:登录在 `core/src/main/kotlin/com/wangzan101/mika/core/chatgpt/`,模型列表是 `ModelCatalog`,调用是 `ResponsesModel`;入口在 `core/src/chatgptCheck/`,不进 App。

## 命令

在仓库根目录:

```
./gradlew :core:chatgptSignInCheck
```

可选参数:

- `-Ptimeout=<秒>`:等浏览器回调多久,默认 300(5 分钟)。
- `-Pmodel=<slug>`:指定用哪个模型;不写就从列表里挑第一个推理模型(没有就挑第一个)。
- `-Pkeep`:结束时把登录保存下来(见下面「隐私」)。

## 会发生什么

1. 在 `~/.mika-dev/chatgpt-check/` 建目录(权限 0700),本机标识和注册到的 client_id 记在里面的 host.json;再跑沿用同一份注册,和 App 一样。第一次跑会注册新的 client。
2. 在 127.0.0.1 上开回调监听,打印授权地址并用默认浏览器打开(打不开就自己复制地址)。
3. 在浏览器里登录 ChatGPT 账号并同意授权。终端里依次出现:收到回调、换令牌、验 ID 令牌、授予了 `chatgpt.tokens.use.direct`,以及授予的权限、邮箱、令牌有效期。
4. 列出模型(slug 和显示名)。
5. 用一个模型发一次「Reply with the single word: pong」,打印回答和用量。
6. 工具调用往返:模型调用 `get_time`,把结果作为 function_call_output 回给它,打印最后的回答;同时说明有没有加密思考随调用回放(store:false)。
7. 用刷新令牌换一次新令牌。
8. 最后是小结:每步 ✓ 或 ✗,有 ✗ 时进程以非零退出(Gradle 会显示 BUILD FAILED,看上面的小结即可)。

常见错误会说成人话,例如:`subscription_sharing_user_not_eligible`(账号套餐、地区不允许在其他 App 用额度)、`usage_limit_exceeded`(额度到上限)、`unsupported_capability`(会写出是请求里哪一项)、`access_denied`(授权页点了取消)、client_id 不一致(删掉 host.json 里的 clientId 再来)。

想只看启动是否正常,可以 `-Ptimeout=5`:打印授权地址、打开浏览器后 5 秒超时退出。

## 隐私

- 访问令牌、刷新令牌、授权码、ID 令牌一律不打印。授权地址里不带 ID 令牌。
- 默认令牌只在这次运行的内存里,进程退出就没了。
- 加 `-Pkeep` 才会写到 `~/.mika-dev/chatgpt-check/session.json`(权限 0600);下次运行只用它里面的邮箱当登录提示。不需要了直接删掉这个文件。
- 这些文件都在家目录下,不在仓库里。
