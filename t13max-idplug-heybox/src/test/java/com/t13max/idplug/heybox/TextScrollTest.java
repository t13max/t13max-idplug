package com.t13max.idplug.heybox;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证实际文字宽度对应的滚动边界。 */
final class TextScrollTest {
    /** 短文本和空文本不能向右滚动。 */
    @Test
    void shortTextDoesNotScroll() { assertEquals(0, TextScroll.clamp(1000, 100, 302)); assertEquals(0, TextScroll.clamp(1000, 0, 302)); }

    /** 长文本最多移动到右边缘对齐，不留下空白尾部。 */
    @Test
    void stopsAtRightEdge() { assertEquals(698, TextScroll.clamp(Long.MAX_VALUE, 1000, 302)); assertEquals(0, TextScroll.clamp(-100, 1000, 302)); assertEquals(36, TextScroll.clamp(36, 1000, 302)); }

    /** 宽度变化后偏移立即重新限制。 */
    @Test
    void clampsAfterResize() { assertEquals(200, TextScroll.clamp(698, 1000, 800)); assertEquals(0, TextScroll.clamp(698, 1000, 1200)); }
}
