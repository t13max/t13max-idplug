package com.t13max.idplug.heybox;

import java.util.List;

/** 统一条目类型标记与刷新内容比较，不使用账号昵称或等级。 */
public final class ReadingText {
    /** 禁止创建工具实例。 */
    private ReadingText() { }

    /** 将内容类型映射为固定单字母。 */
    public static String marker(String kind) { return switch (kind) { case "Post" -> "P"; case "Title" -> "T"; case "Body" -> "B"; case "Comment" -> "C"; default -> "?"; }; }

    /** 比较顺序、内容和地址，判断当前页面内容是否变化。 */
    public static boolean sameItems(List<PageSnapshot.Item> before, List<PageSnapshot.Item> after) {
        if (before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) { var left = before.get(i); var right = after.get(i); if (!left.url.equals(right.url) || !left.text.equals(right.text) || !left.kind.equals(right.kind)) return false; }
        return true;
    }
}
