# Mika-based 云开：开发分支同步说明

本分支从 GitHub 原 main `2aa6cd83165bfc201b0128c250e80892895d63af` 建立，保留原 Android/Harmony 项目，仅新增 `yunkai-next/`。恢复来源为已保存的 `6ad50fd38326746088aa76d89cc09435d465fc6f` 源码归档；归档不含 Git 历史，因此本次是新的同步提交，不冒称恢复了原提交对象。

## 来源与许可

- Mika 固定上游 `d68159366abaea523c5e4e6fd4dc83aad78f9698`，Apache-2.0；保留 LICENSE、NOTICE 和第三方声明。
- 原云开素材与旧项目 MIT，不代表新项目所有组件均为 MIT。
- 发布补充仅为修改声明、旁置声明、文档和对应源码；未有意改变运行逻辑、提示词或测试快照。`UPSTREAM_CHANGES.json` 保留原归档哈希，`PUBLICATION_CHANGES.json` 记录声明修改后的哈希。
- 内嵌运行环境对应源码完整分段存于 [third-party-sources](../third-party-sources/README.md)。必须同供全部分段，按说明重组及校验。源码清单哈希已核对，不等于所有二进制完成可重现构建。

## 验证事实与限制

固定归档此前通过 3195 项 JVM 测试及 Android 构建。原签名 APK 在云端模拟器完成脚本规划驱动的真实 helper 配对、Settings 控制与虚拟屏清理；不是实际大模型规划或真机广泛兼容性验收。

历史 31 项 UI 测试仅有 23 项终态（15 通过、8 失败），其余中断，未全量重跑。新签名 APK 安装及中文启动已验证，但系统 ANR 限制了新签名 helper 握手复验。arm64 沙箱未因 x86 模拟器安装成功而获得验证。

本次同步不重新声称上述测试全绿，也不作为生产发布。仅检验归档恢复、许可材料、旁置声明、受影响 XML 语法和原项目树未变；尚未在本环境重跑全部 Gradle/UI 测试。

新版包名 `com.zhuolin.yunkai.next`，版本 `0.1.10-yunkai / 11`。旧应用数据、模型凭据、历史和权限不会自动迁移。本次不上传 APK、签名密钥或私人恢复包；不部署、不合并 main。
