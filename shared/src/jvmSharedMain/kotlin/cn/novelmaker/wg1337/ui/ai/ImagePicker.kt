package cn.novelmaker.wg1337.ui.ai

import androidx.compose.runtime.Composable

/**
 * 图片选择器（用于给 AI 发送参考图）。
 *
 * Android：系统图片选择器（SAF，GetContent）
 * Windows：AWT FileDialog
 *
 * @param onPicked 回调 (文件字节, 原始文件名)；用户取消或读取失败时为 null
 * @return 触发选择器的函数
 */
@Composable
expect fun rememberImagePicker(onPicked: (Pair<ByteArray, String>?) -> Unit): () -> Unit
