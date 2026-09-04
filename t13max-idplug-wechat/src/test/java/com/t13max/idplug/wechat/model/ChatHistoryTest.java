package com.t13max.idplug.wechat.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 验证历史恢复、账号隔离、消息去重和未读计数。 */
class ChatHistoryTest {
    @TempDir Path directory;

    /** 未读只属于收到消息的联系人，刷新资料和重启不会丢失。 */
    @Test
    void unreadSurvivesRefreshAndRestart() throws Exception {
        ChatHistory history = history();
        history.updateContacts(List.of(new Contact("a", "昵称甲", "备注甲"), new Contact("b", "昵称乙", "")));
        history.append(message("1", "a", false), false);
        history.append(message("2", "b", false), false);
        history.markRead("a");
        history.updateContacts(List.of(new Contact("b", "更新昵称", "")));
        history.save();
        ChatHistory restored = new ChatHistory(directory.resolve("history.json"));
        restored.load();
        assertEquals(0, restored.unread("a"));
        assertEquals(1, restored.unread("b"));
        assertEquals(1, restored.unreadTotal());
        assertTrue(restored.contacts().stream().anyMatch(contact -> contact.displayName().equals("备注甲")));
        assertEquals("你好\n世界 😀", restored.messages("a").getFirst().text());
    }

    /** 服务端回显或重复拉取不会重复显示和重复提醒。 */
    @Test
    void deduplicatesServerAndClientIds() {
        ChatHistory history = history();
        assertTrue(history.append(new ChatMessage("server", "client", "a", "我", "消息", 1, true), false));
        assertFalse(history.append(new ChatMessage("server", "", "a", "我", "消息", 1, true), false));
        assertFalse(history.append(new ChatMessage("other", "client", "a", "我", "消息", 1, true), false));
        assertEquals(1, history.messages("a").size());
        assertEquals(0, history.unreadTotal());
    }

    /** 切换账号时相同联系人标识也不会混入其他账号消息。 */
    @Test
    void isolatesAccounts() throws Exception {
        ChatHistory history = history();
        history.append(message("1", "a", false), false);
        history.selectAccount("account-b");
        assertTrue(history.contacts().isEmpty());
        assertTrue(history.messages("a").isEmpty());
        assertEquals(0, history.unreadTotal());
        history.save();
        history.selectAccount("account-a");
        assertEquals(1, history.messages("a").size());
    }

    /** 损坏历史会保留备份并显式报错。 */
    @Test
    void backsUpDamagedHistory() throws Exception {
        Path file = directory.resolve("history.json");
        Files.writeString(file, "{broken");
        assertThrows(IOException.class, () -> new ChatHistory(file).load());
        assertEquals("{broken", Files.readString(file));
        try (var files = Files.list(directory)) { assertEquals(2, files.count()); }
    }

    /** 新消息优先排列，读取返回的集合不可改变内部状态。 */
    @Test
    void sortsRecentConversationsAndProtectsSnapshots() {
        ChatHistory history = history();
        history.append(new ChatMessage("1", "", "a", "甲", "早", 10, false), false);
        history.append(new ChatMessage("2", "", "b", "乙", "晚", 20, false), false);
        assertEquals("b", history.contacts().getFirst().id());
        assertThrows(UnsupportedOperationException.class, () -> history.messages("a").clear());
    }

    /** 创建一个已经选择账号的测试历史。 */
    private ChatHistory history() {
        ChatHistory history = new ChatHistory(directory.resolve("history.json"));
        history.selectAccount("account-a");
        return history;
    }

    /** 构造包含中文和表情的测试消息。 */
    private ChatMessage message(String id, String conversation, boolean outgoing) {
        return new ChatMessage(id, "", conversation, "昵称", "你好\n世界 😀", 100, outgoing);
    }
}
