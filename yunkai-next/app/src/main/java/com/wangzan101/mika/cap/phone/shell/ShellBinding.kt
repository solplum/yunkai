package com.wangzan101.mika.cap.phone.shell

import kotlinx.coroutines.flow.StateFlow
import com.wangzan101.mika.shell.IMikaShell

/** 到 shell 身份服务的一条连接;[SharedShell] 按引用计数绑定/解绑,调用方只看这几样。 */
interface ShellBinding {
    /** 绑定期间连着时是服务的接口,否则为 null。 */
    val shell: StateFlow<IMikaShell?>

    /** 连上过、仍需要,但现在断了。重新连上或解绑后复位。 */
    val dropped: StateFlow<Boolean>

    fun bind()

    /** 解绑后不留下虚拟屏、静音和剪贴板改动。 */
    fun unbind()

    /** 「重新连接」:断开后没能自动连回时手动再试一次。 */
    fun reconnect()
}
