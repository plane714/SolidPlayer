package com.solidplayer.core.danmaku.track

import com.solidplayer.core.danmaku.model.DanmakuMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 轨道分配单测（文档 §8.4）。
 *
 * 固定场景：宽 1000、**显示区域高 100**，行高 25 → 4 条轨道；速度 v = 1000 / 8s = 125 px/s。
 * 因此宽度 100 的弹幕 `tEnter = 800ms`、`tLeave = 8800ms`。
 *
 * 注意 `updateLayout` 的第二个参数是**显示区域高度**（不是画布高度）——
 * 缩小显示区域就是缩小这个值，见文件末尾的「显示区域」一节。
 */
class TrackAllocatorTest {

    private lateinit var allocator: TrackAllocator

    @Before
    fun setUp() {
        allocator = TrackAllocator()
        allocator.updateLayout(
            widthPx = 1000,
            heightPx = 100,
            trackHeightPx = 25f,
            speedPxPerSecond = 125f,
            fixedDurationMs = 4_000L,
            minGapMs = 0L,
        )
    }

    // ── 轨道数 ────────────────────────────────────────────────────

    @Test
    fun `轨道数等于可用高度除以行高`() {
        assertEquals(4, allocator.trackCount)
    }

    @Test
    fun `行高超过画面高度时至少保留一条轨道`() {
        val a = TrackAllocator()
        a.updateLayout(1000, 10, 25f, 125f, 4_000L, 0L)
        assertEquals(1, a.trackCount)
    }

    @Test
    fun `尺寸变化后轨道数随之变化`() {
        allocator.updateLayout(1000, 200, 25f, 125f, 4_000L, 0L)
        assertEquals(8, allocator.trackCount)
    }

    // ── 进出场时长 ────────────────────────────────────────────────

    @Test
    fun `进入时长等于弹幕宽度除以速度`() {
        assertEquals(800L, allocator.enterDurationMs(100f))
        assertEquals(1_600L, allocator.enterDurationMs(200f))
    }

    @Test
    fun `离屏时长包含屏幕宽度`() {
        assertEquals(8_800L, allocator.leaveDurationMs(100f))
        assertEquals(9_600L, allocator.leaveDurationMs(200f))
    }

    // ── 滚动弹幕分配 ──────────────────────────────────────────────

    @Test
    fun `同一时刻的弹幕自上而下占用不同轨道`() {
        assertEquals(0, allocator.allocateScroll(0L, 100f))
        assertEquals(1, allocator.allocateScroll(0L, 100f))
        assertEquals(2, allocator.allocateScroll(0L, 100f))
        assertEquals(3, allocator.allocateScroll(0L, 100f))
    }

    @Test
    fun `轨道用尽时返回 null 以便调用方丢弃弹幕`() {
        repeat(4) { assertNotNull(allocator.allocateScroll(0L, 100f)) }
        assertNull(allocator.allocateScroll(0L, 100f))
    }

    @Test
    fun `前一条尚未完全进入屏幕时该轨道不可复用`() {
        allocator.allocateScroll(0L, 100f)          // 轨道 0，占用到 800ms
        assertEquals(1, allocator.allocateScroll(799L, 100f))
    }

    @Test
    fun `前一条完全进入屏幕后该轨道即可复用`() {
        allocator.allocateScroll(0L, 100f)          // 轨道 0，占用到 800ms
        assertEquals(0, allocator.allocateScroll(800L, 100f))
    }

    @Test
    fun `宽度越大占用时间越久`() {
        allocator.allocateScroll(0L, 400f)          // tEnter = 3200ms
        assertEquals(1, allocator.allocateScroll(3_199L, 400f))
        assertEquals(0, allocator.allocateScroll(3_200L, 400f))
    }

    @Test
    fun `最小间隔会额外推迟轨道复用`() {
        val a = TrackAllocator()
        a.updateLayout(1000, 100, 25f, 125f, 4_000L, minGapMs = 200L)
        a.allocateScroll(0L, 100f)                  // 占用到 800 + 200 = 1000ms
        assertEquals(1, a.allocateScroll(999L, 100f))
        assertEquals(0, a.allocateScroll(1_000L, 100f))
    }

    // ── 顶部 / 底部弹幕分配 ───────────────────────────────────────

    @Test
    fun `顶部弹幕自上而下找轨道`() {
        assertEquals(0, allocator.allocateFixed(DanmakuMode.TOP, 0L))
        assertEquals(1, allocator.allocateFixed(DanmakuMode.TOP, 0L))
    }

    @Test
    fun `底部弹幕自下而上找轨道`() {
        assertEquals(3, allocator.allocateFixed(DanmakuMode.BOTTOM, 0L))
        assertEquals(2, allocator.allocateFixed(DanmakuMode.BOTTOM, 0L))
    }

    @Test
    fun `顶部与底部弹幕分别从两端占用且互不冲突`() {
        assertEquals(0, allocator.allocateFixed(DanmakuMode.TOP, 0L))
        assertEquals(3, allocator.allocateFixed(DanmakuMode.BOTTOM, 0L))
        // 中间两条仍然空闲
        assertEquals(1, allocator.allocateScroll(0L, 100f))
        assertEquals(2, allocator.allocateScroll(0L, 100f))
        assertNull(allocator.allocateScroll(0L, 100f))
    }

    @Test
    fun `固定弹幕独占轨道直到展示时长结束`() {
        allocator.allocateFixed(DanmakuMode.TOP, 0L)        // 占用到 4000ms
        assertEquals(3, allocator.allocateFixed(DanmakuMode.BOTTOM, 0L))
        assertEquals(1, allocator.allocateScroll(3_999L, 100f))
        assertEquals(0, allocator.allocateScroll(4_000L, 100f))
    }

    // ── 几何换算与重置 ────────────────────────────────────────────

    @Test
    fun `轨道顶边坐标按行高累加`() {
        assertEquals(0f, allocator.trackTop(0), 0.001f)
        assertEquals(25f, allocator.trackTop(1), 0.001f)
        assertEquals(75f, allocator.trackTop(3), 0.001f)
    }

    @Test
    fun `底部轨道自显示区域底边向上排布`() {
        // 第 3 条轨道的底边贴着区域底部
        assertEquals(100f, allocator.trackBottom(3), 0.001f)
        assertEquals(75f, allocator.trackBottom(2), 0.001f)
        // 第 0 条轨道落在最上面那条的位置
        assertEquals(25f, allocator.trackBottom(0), 0.001f)
    }

    @Test
    fun `reset 后全部轨道重新可用`() {
        repeat(4) { allocator.allocateScroll(0L, 100f) }
        assertNull(allocator.allocateScroll(0L, 100f))
        allocator.reset()
        assertEquals(0, allocator.allocateScroll(0L, 100f))
    }

    @Test
    fun `无轨道可用时不写入占用状态`() {
        repeat(4) { allocator.allocateScroll(0L, 100f) }
        allocator.allocateScroll(0L, 100f)
        // 仍然要等到 800ms 之后才有轨道空出来
        assertEquals(0, allocator.allocateScroll(800L, 100f))
    }

    @Test
    fun `速度为零时不抛异常且时长为零`() {
        val a = TrackAllocator()
        a.updateLayout(0, 100, 25f, 0f, 4_000L, 0L)
        assertEquals(0L, a.enterDurationMs(100f))
        assertEquals(0L, a.leaveDurationMs(100f))
        assertTrue(a.trackCount >= 1)
    }

    // ── 显示区域 ──────────────────────────────────────────────────
    //
    // 「弹幕太密、挡画面」的主治项：区域越小轨道越少，挤不进来的弹幕被丢弃。
    // 注意底部弹幕贴的是**区域**下边缘 —— 否则这个设置对底部弹幕没有约束力。

    @Test
    fun `显示区域高度决定轨道数`() {
        val a = TrackAllocator()
        // 画布 200 高，只把上四分之一的 50px 让给弹幕 → 只有 2 条轨道
        a.updateLayout(1000, 50, 25f, 125f, 4_000L, 0L)
        assertEquals(2, a.trackCount)
    }

    @Test
    fun `显示区域缩小后同屏条数随之下降`() {
        val a = TrackAllocator()
        // 整屏 100px → 4 条轨道
        a.updateLayout(1000, 100, 25f, 125f, 4_000L, 0L)
        assertEquals(0, a.allocateScroll(0L, 100f))
        assertEquals(1, a.allocateScroll(0L, 100f))
        assertEquals(2, a.allocateScroll(0L, 100f))
        assertEquals(3, a.allocateScroll(0L, 100f))
        assertNull(a.allocateScroll(0L, 100f))

        // 区域压到一半 → 只剩 2 条，第三、四条同一时刻就挤不进来了
        a.updateLayout(1000, 50, 25f, 125f, 4_000L, 0L)
        assertEquals(0, a.allocateScroll(0L, 100f))
        assertEquals(1, a.allocateScroll(0L, 100f))
        assertNull(a.allocateScroll(0L, 100f))
    }

    @Test
    fun `只有轨道数变化时才重建轨道数组`() {
        val a = TrackAllocator()
        // 轨道数：200 / 25 = 8 → 50 / 25 = 2，变了 → 重建（并清空占用）
        assertTrue(a.updateLayout(1000, 200, 25f, 125f, 4_000L, 0L))
        repeat(8) { assertNotNull(a.allocateScroll(0L, 100f)) }

        assertTrue(a.updateLayout(1000, 50, 25f, 125f, 4_000L, 0L))
        assertEquals(2, a.trackCount)
        assertEquals(0, a.allocateScroll(0L, 100f))
    }

    @Test
    fun `轨道数不变时保留占用状态`() {
        // 显示区域滑杆按 1% 步进，多数档位算出的轨道数与上一档相同。
        // 那些档位若也清空占用，新弹幕会被分到正在飘的弹幕占着的轨道上 → 直接叠字。
        val a = TrackAllocator()
        a.updateLayout(1000, 600, 25f, 125f, 4_000L, 0L)   // 24 条轨道
        a.allocateScroll(0L, 100f)                          // 占用轨道 0 到 800ms

        // 600 → 601：轨道数仍是 24，不重建、也不该清空
        assertFalse(a.updateLayout(1000, 601, 25f, 125f, 4_000L, 0L))
        assertEquals(24, a.trackCount)
        // 轨道 0 仍被占着，因此这一条落到轨道 1
        assertEquals(1, a.allocateScroll(0L, 100f))
    }

    @Test
    fun `标量参数即使不重建也立即生效`() {
        // 不重建不等于不更新：宽/高/行高/速度的标量部分必须当次就位，
        // 否则「轨道数没变」的那些档位会拿旧速度算 `tEnter`，弹幕间距出错
        val a = TrackAllocator()
        a.updateLayout(1000, 600, 25f, 125f, 4_000L, 0L)
        assertFalse(a.updateLayout(500, 601, 25f, 250f, 6_000L, 0L))

        assertEquals(500, a.widthPx)
        assertEquals(601, a.areaHeight)
        assertEquals(250f, a.speed, 0.001f)
        assertEquals(6_000L, a.fixedDuration)
        // 速度翻倍 → 进入时长减半
        assertEquals(400L, a.enterDurationMs(100f))
    }

    @Test
    fun `区域高度为零时仍保留一条轨道`() {
        // 下限兜底：0 会让轨道数归零，调用方拿不到任何轨道，弹幕会整片消失
        val a = TrackAllocator()
        assertTrue(a.updateLayout(1000, 0, 25f, 125f, 4_000L, 0L))
        assertEquals(1, a.trackCount)
    }

    @Test
    fun `显示区域内的轨道坐标都落在区域内`() {
        val a = TrackAllocator()
        a.updateLayout(1000, 50, 25f, 125f, 4_000L, 0L)
        // 区域高 50、行高 25 → 2 条轨道，最后一条的底边正好压在区域下边缘
        assertEquals(25f, a.trackBottom(0), 0.001f)
        assertEquals(50f, a.trackBottom(1), 0.001f)
        // 顶边不得越过区域下边缘
        for (track in 0 until a.trackCount) {
            assertTrue(a.trackTop(track) < a.areaHeight)
        }
    }
}
