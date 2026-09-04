package com.t13max.idplug.wechat.windows;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.t13max.idplug.wechat.model.ChatMessage;
import com.t13max.idplug.wechat.model.Contact;
import com.t13max.idplug.wechat.service.WechatService;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeListener;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

/** 联系人、聊天和二维码三页界面，全部 Swing 更新在界面线程执行。 */
public final class WechatPanel extends JPanel implements Disposable {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final WechatService service = WechatService.getInstance();
    private final ToolWindow toolWindow;
    private final CardLayout layout = new CardLayout();
    private final JPanel pages = new JPanel(layout);
    private final JBTextArea status = textArea();
    private final JBTextArea warning = textArea();
    private final DefaultListModel<Contact> listModel = new DefaultListModel<>();
    private final JBList<Contact> contactList = new JBList<>(listModel);
    private final JLabel title = label("消息");
    private final JLabel qrLabel = label("点击扫码登录获取二维码");
    private final JBTextArea messages = textArea();
    private final JBScrollPane messageScroll = new JBScrollPane(messages);
    private final JTextField input = new JTextField();
    private final JButton send = new JButton("发送");
    private final JButton login = new JButton("扫码登录");
    private final JButton reconnect = new JButton("重连");
    private final JButton logout = new JButton("退出");
    private final JBTextArea sendStatus = textArea();
    private final Map<String, String> drafts = new HashMap<>();
    private final Map<String, String> errors = new HashMap<>();
    private final Set<String> sending = new HashSet<>();
    private final PropertyChangeListener focusListener = event -> markVisibleRead();
    private Contact selected;
    private String page = "list";
    private String accountId = "";
    private List<ChatMessage> renderedMessages = List.of();
    private BufferedImage shownQr;
    private WechatService.State lastState;
    private boolean disposed;

    /** 构造工具窗口内容并订阅共享服务。 */
    public WechatPanel(Project project, ToolWindow toolWindow) {
        super(new BorderLayout(JBUI.scale(6), JBUI.scale(6)));
        this.toolWindow = toolWindow;
        setBorder(JBUI.Borders.empty(8));
        setPreferredSize(new Dimension(JBUI.scale(340), JBUI.scale(540)));
        JPanel header = new JPanel(new BorderLayout());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4)));
        actions.add(login);
        actions.add(reconnect);
        actions.add(logout);
        header.add(status, BorderLayout.NORTH);
        header.add(actions, BorderLayout.CENTER);
        header.add(warning, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);
        buildContacts();
        buildChat();
        buildLogin();
        add(pages, BorderLayout.CENTER);
        login.addActionListener(event -> { rememberDraft(); showPage("login"); service.login(); });
        reconnect.addActionListener(event -> service.reconnect());
        logout.addActionListener(event -> service.logout());
        service.subscribe(this, this::refresh);
        project.getMessageBus().connect(this).subscribe(ToolWindowManagerListener.TOPIC, new ToolWindowManagerListener() {
            /** 工具窗口显隐变化时更新当前会话已读状态。 */
            @Override
            public void stateChanged(ToolWindowManager manager) { markVisibleRead(); }
        });
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("activeWindow", focusListener);
        addHierarchyListener(event -> markVisibleRead());
        service.start();
        refresh();
    }

    /** 创建可换行的纯文本区域。 */
    private static JBTextArea textArea() {
        JBTextArea area = new JBTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        return area;
    }

    /** 创建禁用 HTML 解释的标签，避免昵称被当作界面标记。 */
    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.putClientProperty("html.disable", true);
        return label;
    }

    /** 创建联系人列表，单击或回车进入聊天。 */
    private void buildContacts() {
        contactList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        contactList.setCellRenderer(new ContactRenderer());
        contactList.setEmptyText("登录后显示联系人；历史消息会保存在本机");
        contactList.addMouseListener(new MouseAdapter() {
            /** 单击有效联系人行进入会话，空白区域不触发。 */
            @Override
            public void mouseClicked(MouseEvent event) {
                int index = contactList.locationToIndex(event.getPoint());
                if (SwingUtilities.isLeftMouseButton(event) && index >= 0 && contactList.getCellBounds(index, index).contains(event.getPoint())) { openChat(listModel.get(index)); }
            }
        });
        contactList.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "openChat");
        contactList.getActionMap().put("openChat", new AbstractAction() {
            /** 从键盘打开选中的联系人。 */
            @Override
            public void actionPerformed(ActionEvent event) {
                if (contactList.getSelectedValue() != null) { openChat(contactList.getSelectedValue()); }
            }
        });
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.add(label("联系人 · 最近聊天优先"), BorderLayout.NORTH);
        panel.add(new JBScrollPane(contactList), BorderLayout.CENTER);
        pages.add(panel, "list");
    }

    /** 创建聊天页，底部输入框支持回车发送。 */
    private void buildChat() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        JPanel header = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        JButton back = new JButton("返回");
        back.addActionListener(event -> { rememberDraft(); showPage("list"); });
        header.add(back, BorderLayout.WEST);
        header.add(title, BorderLayout.CENTER);
        panel.add(header, BorderLayout.NORTH);
        panel.add(messageScroll, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        JPanel composer = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        input.getAccessibleContext().setAccessibleName("微信消息输入框，回车发送");
        input.addActionListener(event -> sendMessage());
        send.addActionListener(event -> sendMessage());
        composer.add(input, BorderLayout.CENTER);
        composer.add(send, BorderLayout.EAST);
        footer.add(composer, BorderLayout.CENTER);
        footer.add(sendStatus, BorderLayout.SOUTH);
        panel.add(footer, BorderLayout.SOUTH);
        pages.add(panel, "chat");
    }

    /** 在工具窗口内展示二维码，并允许返回查看历史。 */
    private void buildLogin() {
        JPanel panel = new JPanel(new BorderLayout());
        qrLabel.setHorizontalAlignment(JLabel.CENTER);
        panel.add(qrLabel, BorderLayout.CENTER);
        JButton back = new JButton("返回联系人列表");
        back.addActionListener(event -> showPage("list"));
        panel.add(back, BorderLayout.SOUTH);
        pages.add(panel, "login");
    }

    /** 保存离开当前聊天前的草稿。 */
    private void rememberDraft() {
        if (selected != null) { drafts.put(selected.id(), input.getText()); }
    }

    /** 打开联系人消息并恢复该联系人的草稿。 */
    private void openChat(Contact contact) {
        rememberDraft();
        selected = contact;
        renderedMessages = List.of();
        messages.setText("");
        input.setText(drafts.getOrDefault(contact.id(), ""));
        showPage("chat");
        refreshChat();
        markVisibleRead();
        input.requestFocusInWindow();
    }

    /** 切换列表、会话和扫码页面。 */
    private void showPage(String name) {
        page = name;
        layout.show(pages, name);
    }

    /** 更新状态和列表，同时保持正在查看的会话。 */
    private void refresh() {
        if (disposed) { return; }
        status.setText(service.status());
        warning.setText(service.storageWarning());
        warning.setVisible(!service.storageWarning().isBlank());
        if (!accountId.equals(service.history().accountId())) {
            accountId = service.history().accountId();
            selected = null;
            drafts.clear();
            sending.clear();
            errors.clear();
            input.setText("");
            showPage("list");
        }
        WechatService.State state = service.state();
        login.setEnabled(state == WechatService.State.OFFLINE || state == WechatService.State.LOGIN);
        reconnect.setEnabled(state == WechatService.State.OFFLINE);
        logout.setEnabled(state != WechatService.State.OFFLINE);
        if (state == WechatService.State.ONLINE && lastState != state && "login".equals(page)) { showPage("list"); }
        lastState = state;
        BufferedImage image = service.qr();
        if (image != shownQr) {
            shownQr = image;
            qrLabel.setIcon(image == null ? null : new ImageIcon(image.getScaledInstance(JBUI.scale(220), JBUI.scale(220), Image.SCALE_SMOOTH)));
            qrLabel.setText(image == null ? "等待获取二维码…" : "");
        }
        List<Contact> contacts = service.history().contacts();
        boolean changed = listModel.size() != contacts.size();
        for (int index = 0; !changed && index < contacts.size(); index++) { changed = !contacts.get(index).equals(listModel.get(index)); }
        if (changed) {
            listModel.clear();
            listModel.addAll(contacts);
        }
        contactList.repaint();
        if (selected != null) {
            selected = contacts.stream().filter(contact -> contact.id().equals(selected.id())).findFirst().orElse(selected);
            refreshChat();
        }
        markVisibleRead();
    }

    /** 更新聊天正文，读旧消息时不强制跳回底部。 */
    private void refreshChat() {
        if (selected == null) { return; }
        title.setText(selected.displayName());
        List<ChatMessage> current = service.history().messages(selected.id());
        if (!current.equals(renderedMessages)) {
            var scroll = messageScroll.getVerticalScrollBar();
            boolean atBottom = renderedMessages.isEmpty() || scroll.getValue() + scroll.getVisibleAmount() >= scroll.getMaximum() - JBUI.scale(24);
            int position = scroll.getValue();
            StringBuilder content = new StringBuilder();
            for (ChatMessage message : current) {
                content.append(message.sender()).append(message.outgoing() ? "（我）" : "").append("  ").append(TIME.format(Instant.ofEpochMilli(message.time()))).append('\n').append(message.text()).append("\n\n");
            }
            messages.setText(content.toString());
            renderedMessages = current;
            if (atBottom) { messages.setCaretPosition(messages.getDocument().getLength()); }
            else { SwingUtilities.invokeLater(() -> scroll.setValue(position)); }
        }
        boolean available = service.canSend(selected) && !sending.contains(selected.id());
        input.setEnabled(available);
        send.setEnabled(available);
        send.setText(sending.contains(selected.id()) ? "发送中…" : "发送");
        sendStatus.setText(errors.getOrDefault(selected.id(), available ? "回车发送" : "当前离线或属于旧登录历史，可查看消息；请从当前联系人中选择会话"));
    }

    /** 发起发送并按会话保存失败草稿，避免切换联系人后清错输入框。 */
    private void sendMessage() {
        if (selected == null || !send.isEnabled() || input.getText().isBlank()) { return; }
        String recipient = selected.id();
        String text = input.getText();
        String account = accountId;
        drafts.put(recipient, text);
        sending.add(recipient);
        errors.remove(recipient);
        refreshChat();
        service.send(recipient, text, error -> {
            if (disposed || !account.equals(accountId)) { return; }
            sending.remove(recipient);
            if (error == null) {
                if (text.equals(drafts.get(recipient))) { drafts.remove(recipient); }
                if (selected != null && selected.id().equals(recipient) && text.equals(input.getText())) { input.setText(""); }
            } else { errors.put(recipient, error); }
            refreshChat();
            if (selected != null && selected.id().equals(recipient)) { input.requestFocusInWindow(); }
        });
    }

    /** 只有当前活动窗口中可见的聊天页才清除未读标记。 */
    private void markVisibleRead() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::markVisibleRead); return; }
        Window window = SwingUtilities.getWindowAncestor(this);
        if (!disposed && selected != null && "chat".equals(page) && toolWindow.isVisible() && isShowing() && window != null && window.isActive()) { service.markRead(selected.id()); }
    }

    /** 释放全局焦点监听，订阅由内容销毁器统一移除。 */
    @Override
    public void dispose() {
        disposed = true;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("activeWindow", focusListener);
    }

    /** 使用纯文本绘制联系人名称和独立的未读计数。 */
    private final class ContactRenderer extends DefaultListCellRenderer {
        /** 渲染单行联系人，不显示或执行 HTML。 */
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            JLabel row = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            row.putClientProperty("html.disable", true);
            Contact contact = (Contact) value;
            int unread = service.history().unread(contact.id());
            row.setText(contact.displayName() + (unread > 0 ? "   ● " + unread : ""));
            row.setBorder(JBUI.Borders.empty(10, 6));
            return row;
        }
    }
}
