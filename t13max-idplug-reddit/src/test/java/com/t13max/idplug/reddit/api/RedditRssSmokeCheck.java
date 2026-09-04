package com.t13max.idplug.reddit.api;

/**
 * 手动执行的公开 RSS 网络探针，不参与默认测试，不读取账号或密码库。
 */
public final class RedditRssSmokeCheck {
    /**
     * 禁止创建命令行测试实例。
     */
    private RedditRssSmokeCheck() {
    }

    /**
     * 读取专区或指定帖子评论，并仅输出数量和首条编号。
     */
    public static void main(String[] arguments) {
        try (RedditRssClient client = new RedditRssClient()) {
            var request = arguments.length > 0 ? client.loadComments(arguments[0], "programming") : client.loadPosts("programming", "hot");
            var result = request.join();
            System.out.println("RSS parsed successfully; items=" + result.items.size() + "; firstId=" + (result.items.isEmpty() ? "none" : result.items.getFirst().getId()));
        } catch (Exception exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            System.err.println("RSS probe failed: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            System.exit(1);
        }
    }
}
