package com.t13max.idplug.heybox;

/** 将滚动限制在文字实际占用的像素范围内。 */
public final class TextScroll {
    /** 禁止创建工具实例。 */
    private TextScroll() { }

    /** 文字短于视口时不能滚动，长文本最多滚到末尾对齐右边缘。 */
    public static int clamp(long requested, int textWidth, int viewportWidth) { return (int) Math.max(0, Math.min(requested, Math.max(0, textWidth - Math.max(1, viewportWidth)))); }
}
