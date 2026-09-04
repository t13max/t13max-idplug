package com.t13max.idplug.reddit.model;

/**
 * Reddit 帖子或评论数据。
 */
public final class RedditItem {
    private final String id;
    private final String text;
    private final String author;
    private final String subreddit;
    private final String permalink;

    /**
     * 创建一条 Reddit 内容。
     */
    public RedditItem(String id, String text, String author, String subreddit, String permalink) {
        this.id = id;
        this.text = text;
        this.author = author;
        this.subreddit = subreddit;
        this.permalink = permalink;
    }

    /**
     * 获取内容编号。
     */
    public String getId() {
        return id;
    }

    /**
     * 获取显示文本。
     */
    public String getText() {
        return text;
    }

    /**
     * 获取作者。
     */
    public String getAuthor() {
        return author;
    }

    /**
     * 获取所属专区。
     */
    public String getSubreddit() {
        return subreddit;
    }

    /**
     * 获取 Reddit 永久链接。
     */
    public String getPermalink() {
        return permalink;
    }
}
