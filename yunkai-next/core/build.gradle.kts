plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // 沙箱路径契约测试放在 testFixtures,app 的 AndroidSandbox 也跑同一套
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.commons.compress)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    // 测试里的 TLS 1.3 与导出密钥:用和 Android 平台同源的 TLS 实现,只进测试类路径
    testImplementation(libs.conscrypt.openjdk.uber)
    testFixturesImplementation(libs.junit)
}

// 运行时列不了资源目录:构建时扫 knowledge/apps、knowledge/sites、skills 的头部,校验后生成索引资源;校验不过构建失败
val knowledgeIndex by tasks.registering(JavaExec::class) {
    val resources = file("src/main/resources")
    val out = layout.buildDirectory.dir("generated/knowledge-index")
    inputs.dir(resources.resolve("knowledge")).withPropertyName("knowledge")
    inputs.dir(resources.resolve("skills")).withPropertyName("skills")
    outputs.dir(out)
    classpath = sourceSets.main.get().output.classesDirs + configurations.runtimeClasspath.get()
    mainClass.set("com.wangzan101.mika.core.knowledge.KnowledgeIndex")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(resources.absolutePath, out.get().file("knowledge/index.json").asFile.absolutePath)
    })
}
sourceSets.main { resources.srcDir(knowledgeIndex) }

// README 里会变的清单(内置知识、服务商预设)从源头重新生成,见 testFixtures 里的 ReadmeSections;ReadmeSectionsTest 守着两边一致
val updateReadme by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "重新生成 README.md、README.en.md 里 generated 标记之间的段落"
    classpath = sourceSets["testFixtures"].runtimeClasspath
    mainClass.set("com.wangzan101.mika.core.docs.ReadmeSections")
    args(rootProject.projectDir.absolutePath)
    outputs.upToDateWhen { false }
}

// 桌面上对着 OpenAI 真实服务走一遍「用 ChatGPT 登录」(登录、列模型、Responses、工具调用、换新令牌),见 docs/dev/chatgpt-signin-check.md。
// 单独的源码集:不进 core 的产物,也不进 app
val chatgptCheck by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[chatgptCheck.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[chatgptCheck.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

val chatgptSignInCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "用 App 的登录代码对着 OpenAI 走一遍 ChatGPT 登录;-Ptimeout=<秒> -Pmodel=<slug> -Pkeep"
    classpath = chatgptCheck.runtimeClasspath
    mainClass.set("com.wangzan101.mika.core.chatgpt.check.ChatGptSignInCheckKt")
    args(listOfNotNull(
        providers.gradleProperty("timeout").orNull?.let { "--timeout=$it" },
        providers.gradleProperty("model").orNull?.let { "--model=$it" },
        if (providers.gradleProperty("keep").isPresent) "--keep" else null,
    ))
    outputs.upToDateWhen { false }
}

tasks.test {
    // testFixtures 会把 core 的 jar 放到测试类路径前面;让目录排在前面,按目录读资源的测试才找得到文件
    classpath = sourceSets.main.get().output + classpath
    // TaskSetTest 读仓库根目录的评测任务集
    inputs.dir(rootProject.file("eval")).withPropertyName("evalTasks")
    // DocsConsistencyTest 读仓库根目录的文档,并对照 app 的源码;文档或 app 源码变了要重跑
    inputs.files(rootProject.file("README.md"), rootProject.file("README.en.md"), rootProject.file("tools/sandbox/README.md")).withPropertyName("docsFiles")
    inputs.dir(rootProject.file("docs")).withPropertyName("docsDir")
    inputs.dir(rootProject.file("app/src")).withPropertyName("appSources")
    // updateReadme 先改写 README,同一次构建里测试要读改写后的
    mustRunAfter(updateReadme)
    // 循环场景的消息序列金样;-PupdateSnapshots 时重新生成(见 LoopSnapshot)
    systemProperty("mika.snapshotDir", file("src/test/resources/loop-snapshots").absolutePath)
    // 提示词表面(系统提示词、工具定义、反馈文字)的金样;同样用 -PupdateSnapshots 重新生成(见 PromptSurface)
    systemProperty("mika.promptSurfaceDir", file("src/test/resources/prompt-surface").absolutePath)
    val updateSnapshots = project.hasProperty("updateSnapshots")
    inputs.property("updateSnapshots", updateSnapshots)
    if (updateSnapshots) {
        systemProperty("mika.updateSnapshots", "true")
        outputs.upToDateWhen { false }
    }
}
