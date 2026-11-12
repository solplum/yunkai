package com.wangzan101.mika.task

import androidx.test.platform.app.InstrumentationRegistry

/** 真机测试用:被测 App 进程里那一份任务环境(换模型来源、评测钩子、看屏幕画面)。 */
internal val testTasks: TaskEnvironment
    get() = TaskEnvironment.of(InstrumentationRegistry.getInstrumentation().targetContext)
