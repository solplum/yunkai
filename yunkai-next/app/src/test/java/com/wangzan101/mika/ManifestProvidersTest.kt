// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** 清单里会让进程一启动就崩的配置,在电脑上先拦住(真机才会暴露的那一类)。 */
class ManifestProvidersTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    @Test
    fun fileProvidersGrantUriPermissionsAndStayPrivate() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("manifest not found from ${File(".").absolutePath}", manifest.exists())
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val providers = doc.getElementsByTagName("provider")
        var fileProviders = 0
        for (i in 0 until providers.length) {
            val p = providers.item(i) as Element
            val metas = p.getElementsByTagName("meta-data")
            val isFileProvider = (0 until metas.length).any {
                (metas.item(it) as Element).getAttributeNS(androidNs, "name") == "android.support.FILE_PROVIDER_PATHS"
            }
            if (!isFileProvider) continue
            fileProviders++
            val name = p.getAttributeNS(androidNs, "name")
            // FileProvider.attachInfo 在 grantUriPermissions 不为 true 时抛 SecurityException,进程起不来
            assertEquals("$name grantUriPermissions", "true", p.getAttributeNS(androidNs, "grantUriPermissions"))
            assertEquals("$name exported", "false", p.getAttributeNS(androidNs, "exported"))
        }
        assertTrue("expected at least one FileProvider", fileProviders > 0)
    }

    @Test
    fun helperHandoffProviderIsOnlyReachableWithShellLevelPermission() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val providers = doc.getElementsByTagName("provider")
        val p = (0 until providers.length).map { providers.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == "com.wangzan101.mika.shell.HelperHandoffProvider" }
        // shell 要能调到它;普通 App 没有这个权限,连 provider 都拿不到
        assertEquals("true", p.getAttributeNS(androidNs, "exported"))
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", p.getAttributeNS(androidNs, "permission"))
        assertEquals("\${applicationId}.helper", p.getAttributeNS(androidNs, "authorities"))
    }

    /** 清单里登记的组件都按全名找得到类(搬了代码的入口留着同名的薄壳)。 */
    @Test
    fun everyComponentClassExists() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val names = listOf("application", "activity", "service", "receiver", "provider").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(androidNs, "name") }
        }
        assertTrue(names.size > 5)
        for (n in names) {
            val full = if (n.startsWith(".")) "com.wangzan101.mika$n" else n
            assertTrue("$full is not a class", runCatching { Class.forName(full, false, javaClass.classLoader) }.isSuccess)
        }
    }

    /**
     * 测试 APK 清单里的页面、服务运行在测试包自己的进程里,那里没有 Kotlin 标准库(和 App 重复的依赖不打进测试 APK):
     * 每个都要有同名的 Java 源文件。类名对不上、或写成 Kotlin,真机上一打开就崩,读屏只读到一块空屏。
     */
    @Test
    fun testApkComponentsAreJavaClassesWithMatchingNames() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/androidTest/AndroidManifest.xml"))
        val names = listOf("activity", "service", "receiver", "provider").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(androidNs, "name") }
        }
        assertTrue(names.isNotEmpty())
        for (n in names) {
            val path = "src/androidTest/java/${n.replace('.', '/')}"
            assertTrue("$n has no Java source at $path.java", File("$path.java").isFile)
        }
    }

    /** 内置助手进程按这个全名启动。 */
    @Test
    fun helperEntryClassExists() {
        val c = Class.forName(com.wangzan101.mika.core.shell.HelperHandoff.HELPER_CLASS, false, javaClass.classLoader)
        assertTrue(c.methods.any { it.name == "main" && java.lang.reflect.Modifier.isStatic(it.modifiers) })
    }
}
