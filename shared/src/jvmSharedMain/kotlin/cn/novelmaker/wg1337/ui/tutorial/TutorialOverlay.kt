package cn.novelmaker.wg1337.ui.tutorial

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 新手教程步骤数据类
 */
data class TutorialStep(
    val title: String,
    val description: String,
    /** 高亮区域的对齐方式，用于定位箭头/指示 */
    val highlightAlignment: Alignment.Horizontal = Alignment.CenterHorizontally
)

/**
 * 新手教程全屏遮罩组件
 *
 * 在屏幕上层显示半透明遮罩，中间展示引导卡片，
 * 底部有点指示器和导航按钮。
 *
 * @param steps 教程步骤列表
 * @param currentStep 当前步骤索引
 * @param onNext 点击"下一步"回调
 * @param onSkip 点击"跳过"回调
 * @param onFinish 点击"完成"回调
 */
@Composable
fun TutorialOverlay(
    steps: List<TutorialStep>,
    currentStep: Int,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit
) {
    // 防止点击穿透到下层
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = { /* 阻止穿透 */ }
            )
    ) {
        // 顶部跳过按钮
        TextButton(
            onClick = onSkip,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 48.dp, end = 16.dp)
        ) {
            Text("跳过", color = Color.White, fontSize = 14.sp)
        }

        // 中间内容区域
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (currentStep < steps.size) {
                val step = steps[currentStep]

                // 步骤编号徽章
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${currentStep + 1}",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(24.dp))

                // 标题
                Text(
                    step.title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(12.dp))

                // 描述
                Text(
                    step.description,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp
                )
            }
        }

        // 底部导航
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 点状指示器
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                steps.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier
                            .size(if (index == currentStep) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == currentStep) MaterialTheme.colorScheme.primary
                                else Color.White.copy(alpha = 0.4f)
                            )
                    )
                }
            }

            // 按钮
            Button(
                onClick = {
                    if (currentStep < steps.size - 1) {
                        onNext()
                    } else {
                        onFinish()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp)
            ) {
                Text(
                    if (currentStep < steps.size - 1) "下一步" else "开始使用",
                    fontSize = 16.sp
                )
            }
        }
    }
}

/**
 * 编辑器内的新手教程遮罩
 * 与 TutorialOverlay 类似，但适配编辑器场景，
 * 显示在编辑器内容的上层。
 */
@Composable
fun EditorTutorialOverlay(
    steps: List<TutorialStep>,
    currentStep: Int,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = { /* 阻止穿透 */ }
            )
    ) {
        // 顶部跳过按钮
        TextButton(
            onClick = onSkip,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp)
        ) {
            Text("跳过", color = Color.White, fontSize = 14.sp)
        }

        // 中间内容
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (currentStep < steps.size) {
                val step = steps[currentStep]

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${currentStep + 1}",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    step.title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    step.description,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )
            }
        }

        // 底部按钮
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 点状指示器
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                steps.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier
                            .size(if (index == currentStep) 10.dp else 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == currentStep) MaterialTheme.colorScheme.primary
                                else Color.White.copy(alpha = 0.4f)
                            )
                    )
                }
            }

            Button(
                onClick = {
                    if (currentStep < steps.size - 1) {
                        onNext()
                    } else {
                        onFinish()
                    }
                },
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(if (currentStep < steps.size - 1) "下一步" else "完成")
            }
        }
    }
}
