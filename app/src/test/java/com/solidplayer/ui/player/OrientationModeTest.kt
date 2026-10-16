package com.solidplayer.ui.player

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 播放页方向策略的常量映射。
 *
 * 三个枚举值本身没什么可测的，要钉住的是**默认态下发的到底是哪条系统常量** ——
 * 它一度是 `SENSOR`（官方定义：无视用户的旋转锁定），现改为 `UNSPECIFIED`（尊重锁定）。
 * 两者在系统锁定**关着**时表现完全一样，只有锁着时才分岔，所以这个差别平时看不出来，
 * 只能靠这条断言守住，否则很容易在「让它跟随手机」这类直觉下被改回去。
 *
 * 纯常量比较：`SCREEN_ORIENTATION_*` 都是编译期常量（会被内联进调用点），
 * 不会碰任何 Android 运行时方法，JVM 单测里可以直接比。
 */
class OrientationModeTest {

    @Test
    fun `跟随下发 UNSPECIFIED 而不是 SENSOR`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            OrientationMode.FOLLOW.activityOrientation,
        )
        // SENSOR 会无视系统方向锁定，是这次刻意不选的常量
        assertNotEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            OrientationMode.FOLLOW.activityOrientation,
        )
    }

    @Test
    fun `跟随态不与任何锁档共用常量`() {
        // 「跟随」必须是「交给系统」，不能退化成某个方向的硬锁 ——
        // 与 `PlayerScreen.onDispose` 恢复方向时用的也是同一条 UNSPECIFIED。
        assertNotEquals(
            OrientationMode.LANDSCAPE.activityOrientation,
            OrientationMode.FOLLOW.activityOrientation,
        )
        assertNotEquals(
            OrientationMode.PORTRAIT.activityOrientation,
            OrientationMode.FOLLOW.activityOrientation,
        )
    }

    @Test
    fun `两个锁档下发的仍是传感器版横竖屏`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            OrientationMode.LANDSCAPE.activityOrientation,
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            OrientationMode.PORTRAIT.activityOrientation,
        )
    }

    @Test
    fun `按钮循环顺序是 跟随 到 锁横 到 锁竖 再回跟随`() {
        assertEquals(OrientationMode.LANDSCAPE, OrientationMode.FOLLOW.next())
        assertEquals(OrientationMode.PORTRAIT, OrientationMode.LANDSCAPE.next())
        assertEquals(OrientationMode.FOLLOW, OrientationMode.PORTRAIT.next())
    }
}
