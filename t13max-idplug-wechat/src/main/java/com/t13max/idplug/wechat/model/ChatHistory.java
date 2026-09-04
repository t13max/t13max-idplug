package com.t13max.idplug.wechat.model;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按账号隔离聊天历史，统一处理去重、未读计数和原子落盘。 */
public final class ChatHistory {
    private static final Gson JSON = new Gson();
    private final Path file;
    private Data data = new Data();

    /** 指定历史文件，文件中不保存登录票据或 Cookie。 */
    public ChatHistory(Path file) {
        this.file = file;
    }

    /** 从磁盘恢复历史，损坏时保留原文件供恢复。 */
    public synchronized void load() throws IOException {
        if (!Files.exists(file)) { return; }
        try {
            Data loaded = JSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            if (loaded == null || loaded.accounts == null) { throw new IllegalArgumentException("历史结构无效"); }
            for (Account account : loaded.accounts.values()) {
                if (account == null || account.contacts == null || account.messages == null || account.unread == null) { throw new IllegalArgumentException("账号历史结构无效"); }
            }
            data = loaded;
        } catch (RuntimeException error) {
            Files.copy(file, file.resolveSibling("history-damaged-" + System.currentTimeMillis() + ".json"));
            throw new IOException("聊天历史读取失败，原文件已备份", error);
        }
    }

    /** 切换账号，确保不同账号的联系人和消息不会混合。 */
    public synchronized void selectAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) { throw new IllegalArgumentException("账号标识不能为空"); }
        data.currentAccount = accountId;
        data.accounts.computeIfAbsent(accountId, key -> new Account());
    }

    /** 返回当前账号标识，未登录时为空。 */
    public synchronized String accountId() {
        return data.currentAccount;
    }

    /** 合并联系人资料，优先保留已经获取到的真实名称。 */
    public synchronized void updateContacts(List<Contact> contacts) {
        Account account = current();
        if (account == null) { return; }
        for (Contact contact : contacts) { account.contacts.put(contact.id(), contact); }
    }

    /** 加入消息并按服务端或客户端标识去重，只累加对应会话的未读数。 */
    public synchronized boolean append(ChatMessage message, boolean read) {
        Account account = current();
        if (account == null) { return false; }
        List<ChatMessage> messages = account.messages.computeIfAbsent(message.conversationId(), key -> new ArrayList<>());
        boolean duplicate = messages.stream().anyMatch(existing -> sameId(existing.id(), message.id()) || sameId(existing.clientId(), message.clientId()));
        if (duplicate) { return false; }
        messages.add(message);
        account.contacts.putIfAbsent(message.conversationId(), new Contact(message.conversationId(), message.conversationId(), ""));
        if (!message.outgoing() && !read) { account.unread.merge(message.conversationId(), 1, Integer::sum); }
        return true;
    }

    /** 只有非空消息标识才能用于去重。 */
    private static boolean sameId(String left, String right) {
        return left != null && !left.isBlank() && left.equals(right);
    }

    /** 标记指定会话已读，不影响其他联系人。 */
    public synchronized void markRead(String id) {
        Account account = current();
        if (account != null) { account.unread.remove(id); }
    }

    /** 返回联系人未读数。 */
    public synchronized int unread(String id) {
        Account account = current();
        return account == null ? 0 : account.unread.getOrDefault(id, 0);
    }

    /** 返回总未读数，状态栏仅据此显示提醒图标。 */
    public synchronized int unreadTotal() {
        Account account = current();
        return account == null ? 0 : account.unread.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 按最近聊天时间排列联系人，并返回独立列表。 */
    public synchronized List<Contact> contacts() {
        Account account = current();
        if (account == null) { return List.of(); }
        return account.contacts.values().stream().sorted(Comparator.comparingLong((Contact contact) -> lastTime(account, contact.id())).reversed().thenComparing(Contact::displayName)).toList();
    }

    /** 返回历史快照，调用方不能修改内部集合。 */
    public synchronized List<ChatMessage> messages(String id) {
        Account account = current();
        return account == null ? List.of() : List.copyOf(account.messages.getOrDefault(id, List.of()));
    }

    /** 获取会话最后一条消息时间。 */
    private static long lastTime(Account account, String id) {
        List<ChatMessage> messages = account.messages.getOrDefault(id, List.of());
        return messages.isEmpty() ? 0 : messages.getLast().time();
    }

    /** 获取当前账号数据。 */
    private Account current() {
        return data.accounts.get(data.currentAccount);
    }

    /** 在后台原子写入文件，避免重启时读取半截 JSON。 */
    public synchronized void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, JSON.toJson(data), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 保存账号索引和当前账号，不包含登录凭据。 */
    private static final class Data {
        String currentAccount = "";
        Map<String, Account> accounts = new LinkedHashMap<>();
    }

    /** 保存单个账号的联系人、消息和未读计数。 */
    private static final class Account {
        Map<String, Contact> contacts = new LinkedHashMap<>();
        Map<String, List<ChatMessage>> messages = new LinkedHashMap<>();
        Map<String, Integer> unread = new LinkedHashMap<>();
    }
}
