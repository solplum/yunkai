package com.wangzan101.mika.chat

/** 导出到「下载/Mika」的一个文件,在对话里显示成文件卡片。[uri] 是 MediaStore 的内容 URI,用来打开。 */
data class SharedFile(val name: String, val size: Long, val savedAs: String, val uri: String)
