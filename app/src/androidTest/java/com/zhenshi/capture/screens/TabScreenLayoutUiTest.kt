package com.zhenshi.capture.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhenshi.capture.screens.components.ScreenHeader
import com.zhenshi.capture.screens.components.TabScreenLayout
import com.zhenshi.capture.screens.theme.ZhenShiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 布局组件仪器化测试：验证 Tab 页骨架可渲染、标题与内容可见。
 */
@RunWith(AndroidJUnit4::class)
class TabScreenLayoutUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tabScreenLayout_rendersHeaderAndContent() {
        composeRule.setContent {
            ZhenShiTheme {
                TabScreenLayout(contentPadding = PaddingValues()) {
                    ScreenHeader(title = "测试标题", subtitle = "测试副标题")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Text("面板正文")
                    }
                }
            }
        }

        composeRule.onNodeWithText("测试标题").assertIsDisplayed()
        composeRule.onNodeWithText("测试副标题").assertIsDisplayed()
        composeRule.onNodeWithText("面板正文").assertIsDisplayed()
    }
}
