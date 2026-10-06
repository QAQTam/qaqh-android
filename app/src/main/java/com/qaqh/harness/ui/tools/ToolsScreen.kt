package com.qaqh.harness.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.qaqh.harness.ui.nav.GlassNavBarContentPadding

private data class PlannedTool(val icon: ImageVector, val title: String, val desc: String)

/**
 * 工具屏：手机端原生工具能力的入口（规划中）。本项目不止"监视电脑端"——
 * 后续会在手机侧长出独立工具，此处先行占位并公示规划项。
 */
@Composable
fun ToolsScreen(modifier: Modifier = Modifier) {
    val planned = listOf(
        PlannedTool(Icons.Outlined.Folder, "工作区浏览", "直接查看电脑端工作区文件与 diff"),
        PlannedTool(Icons.Outlined.Terminal, "快捷命令", "常用命令一键下发到电脑端执行"),
        PlannedTool(Icons.Outlined.Screenshot, "屏幕捕获", "截取电脑端画面给模型做上下文"),
        PlannedTool(Icons.Outlined.Build, "手机侧工具", "在手机本地运行的自定义工具（规划中）"),
    )
    Column(modifier.fillMaxSize()) {
        Text(
            "工具",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = GlassNavBarContentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Text(
                        "手机端正在长出自己的工具能力，以下为规划项——就绪后会在这里出现。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(planned.size) { index ->
                val tool = planned[index]
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            tool.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                        Column {
                            Text(tool.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                tool.desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
