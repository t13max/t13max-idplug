package com.t13max.idplug.wechat.model;

/** 保存消息标识、发送人和纯文本，避免在界面执行消息中的标记。 */
public record ChatMessage(String id, String clientId, String conversationId, String sender, String text, long time, boolean outgoing) {
}
