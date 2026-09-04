package com.t13max.idplug.reddit.api;

import com.t13max.idplug.reddit.model.RedditItem;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 安全解析 Reddit 公开 Atom 订阅源，不执行 HTML 或加载外部 XML 实体。
 */
public final class RedditRssParser {
    private static final String ATOM_NAMESPACE = "http://www.w3.org/2005/Atom";

    /**
     * 禁止创建解析工具实例。
     */
    private RedditRssParser() {
    }

    /**
     * 将帖子或评论订阅源转换为只读内容列表。
     */
    public static List<RedditItem> parse(String xml, boolean comments, String subreddit) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                /**
                 * 将非法 XML 转换为解析失败，禁止输出订阅源原文。
                 */
                @Override
                public void fatalError(org.xml.sax.SAXParseException error) throws SAXException {
                    throw error;
                }
            });
            Element feed = builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
            if (!"feed".equals(feed.getLocalName()) || !ATOM_NAMESPACE.equals(feed.getNamespaceURI())) {
                throw new IllegalArgumentException("Not a Reddit Atom feed");
            }
            List<RedditItem> items = new ArrayList<>();
            NodeList entries = feed.getElementsByTagNameNS(ATOM_NAMESPACE, "entry");
            for (int index = 0; index < Math.min(entries.getLength(), 150); index++) {
                Element entry = (Element) entries.item(index);
                String fullId = text(entry, "id");
                String expectedPrefix = comments ? "t1_" : "t3_";
                if (!fullId.matches(expectedPrefix + "[A-Za-z0-9]+")) {
                    continue;
                }
                Element author = child(entry, "author");
                Element category = child(entry, "category");
                String username = author == null ? "[deleted]" : text(author, "name").replaceFirst("^/?u/", "");
                String community = category == null ? subreddit : category.getAttribute("term");
                Element title = child(entry, "title");
                boolean htmlTitle = title != null && "html".equals(title.getAttribute("type"));
                String content = comments ? plainText(text(entry, "content")) : (htmlTitle ? plainText(text(entry, "title")) : text(entry, "title").replaceAll("\\s+", " ").trim());
                items.add(new RedditItem(fullId.substring(3), content, username, community, permalink(entry)));
            }
            return List.copyOf(items);
        } catch (Exception exception) {
            throw new IllegalStateException("Reddit RSS is unavailable or returned an invalid feed", exception);
        }
    }

    /**
     * 获取直接子元素，避免误读评论或作者中的同名字段。
     */
    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName()) && ATOM_NAMESPACE.equals(element.getNamespaceURI())) {
                return element;
            }
        }
        return null;
    }

    /**
     * 读取可缺省的 Atom 文本字段。
     */
    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? "" : element.getTextContent();
    }

    /**
     * 保留 Reddit 站内永久链接，不接受第三方或脚本地址。
     */
    private static String permalink(Element entry) {
        Element link = child(entry, "link");
        if (link == null) {
            return "";
        }
        try {
            URI uri = URI.create(link.getAttribute("href"));
            String host = uri.getHost();
            if ("https".equalsIgnoreCase(uri.getScheme()) && host != null && (host.equalsIgnoreCase("reddit.com") || host.toLowerCase(java.util.Locale.ROOT).endsWith(".reddit.com"))) {
                return uri.getPath();
            }
        } catch (IllegalArgumentException ignored) {
            return "";
        }
        return "";
    }

    /**
     * 解码 HTML 实体并提取单行文字，保留表情与链接文字。
     */
    private static String plainText(String html) throws java.io.IOException {
        StringBuilder result = new StringBuilder();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            /**
             * 收集 HTML 文本节点。
             */
            @Override
            public void handleText(char[] data, int position) {
                result.append(data);
            }

            /**
             * 使用空格分隔块级元素。
             */
            @Override
            public void handleEndTag(HTML.Tag tag, int position) {
                if (tag.isBlock()) {
                    result.append(' ');
                }
            }

            /**
             * 将 HTML 换行符转换为空格。
             */
            @Override
            public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                if (tag == HTML.Tag.BR || tag == HTML.Tag.HR) {
                    result.append(' ');
                }
            }
        }, true);
        return result.toString().replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }
}
