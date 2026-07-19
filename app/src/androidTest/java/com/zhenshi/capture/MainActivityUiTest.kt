package com.zhenshi.capture

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 主流程仪器化冒烟：导航、Tab 切换、关键控件可见。
 * 需在已连接模拟器/真机上运行 connectedDebugAndroidTest。
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun startDestination_isUsbDevices() {
        composeRule.onNodeWithText("设备").assertIsDisplayed()
        composeRule.onNodeWithText("刷新").assertIsDisplayed()
        composeRule.onNodeWithText("选择设备").assertIsDisplayed()
    }

    @Test
    fun bottomNav_switchesToNetworkTab() {
        composeRule.onNodeWithText("网络").performClick()
        composeRule.onNodeWithText("开始观看").assertIsDisplayed()
    }

    @Test
    fun bottomNav_switchesToPushTab() {
        composeRule.onNodeWithText("推流").performClick()
        composeRule.onNodeWithText("添加").assertIsDisplayed()
        composeRule.onNodeWithText("已保存").assertIsDisplayed()
    }

    @Test
    fun bottomNav_switchesToSettingsTab() {
        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNodeWithText("关于").assertIsDisplayed()
    }

    @Test
    fun bottomNav_returnsToUsbFromNetwork() {
        composeRule.onNodeWithText("网络").performClick()
        composeRule.onNodeWithText("开始观看").assertIsDisplayed()
        composeRule.onNodeWithText("设备").performClick()
        composeRule.onNodeWithText("刷新").assertIsDisplayed()
    }
}
