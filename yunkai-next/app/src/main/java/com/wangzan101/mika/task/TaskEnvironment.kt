package com.wangzan101.mika.task

import android.content.Context
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.settings.ProviderStore
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 任务从 App 拿的:模型从哪来、评测钩子、显示中的任务此刻有没有屏幕画面。整个进程一份,由 App 给出;
 * 测试换掉里面的模型来源与钩子。
 */
class TaskEnvironment {
    /** 显示中的任务的虚拟屏编号;没有任务、或任务还没打开过 App(屏到第一次 launch_app 才建)时为 -1。 */
    val agentDisplayId = MutableStateFlow(-1)
    /** 显示中的任务正在用内置浏览器(画中画要显示网页画面)。 */
    val webScreenOpen = MutableStateFlow(false)

    // 三个工厂的 ModelSession 是这次调用属于的对话(ModelSession.of(对话 id)):同一段对话里建的模型都给同一个,
    // 服务商要会话号的就照它路由、复用缓存

    /** 主模型来自设置里的模型配置;测试可换成脚本模型。 */
    @Volatile var modelFactory: (Context, ModelSession) -> ChatModel? = defaultModelFactory
    /** 轻量模型(列成功标准等);为空时由主模型兼任。测试换主模型时一并换掉,免得调到设置里的真模型。 */
    @Volatile var lightModelFactory: (Context, ModelSession) -> ChatModel? = defaultLightModelFactory
    /** 视觉模型(核查时看截图);设置里没有能看图的模型时为空。 */
    @Volatile var visionModelFactory: (Context, ModelSession) -> ChatModel? = defaultVisionModelFactory

    /** 评测跑器用的钩子;产品里全是默认值。 */
    val evalHooks = EvalHooks()

    /** 给出进程里那一份的 Application。 */
    interface Owner {
        val tasks: TaskEnvironment
    }

    companion object {
        val defaultModelFactory: (Context, ModelSession) -> ChatModel? = { c, s -> ProviderStore.get(c).modelFor(ModelRole.Main, s) }
        val defaultLightModelFactory: (Context, ModelSession) -> ChatModel? = { c, s -> ProviderStore.get(c).modelFor(ModelRole.Light, s) }
        val defaultVisionModelFactory: (Context, ModelSession) -> ChatModel? = { c, s -> ProviderStore.get(c).modelFor(ModelRole.Vision, s) }

        fun of(context: Context): TaskEnvironment = (context.applicationContext as Owner).tasks
    }
}
