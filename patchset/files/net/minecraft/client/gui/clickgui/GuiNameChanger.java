package net.minecraft.client.gui.clickgui;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import plutonium.gpu.KeyboardShim;
import plutonium.gpu.MouseShim;
import plutonium.Account;
import plutonium.AccountManager;
import plutonium.MicrosoftAuth;

import java.io.IOException;
import java.net.URI;
import java.util.List;

public class GuiNameChanger extends GuiScreen {

    private static final int PANEL_W       = 260;
    private static final int PANEL_H       = 220;
    private static final int ACC_ROW_H     = 16;
    private static final int ACC_MAX_ROWS  = 4;

    private int accountScroll = 0;

    private static final int COL_OVERLAY     = 0xAA000000;
    private static final int COL_TAG_MS      = 0xFF1B9E3E;
    private static final int COL_TAG_OFFLINE = 0xFF555570;

    // Theme colors from shared palette — import shortcuts for readability
    private static final int T_SHADOW        = DrawUtils.T_SHADOW;
    private static final int T_WIN_BG        = DrawUtils.T_WIN_BG;
    private static final int T_BORDER        = DrawUtils.T_BORDER;
    private static final int T_HEADER_TOP    = DrawUtils.T_HEADER_TOP;
    private static final int T_HEADER_BOT    = DrawUtils.T_HEADER_BOT;
    private static final int T_GLOSS_TOP     = DrawUtils.T_GLOSS_TOP;
    private static final int T_GLOSS_BOT     = DrawUtils.T_GLOSS_BOT;
    private static final int T_TEXT_PRIMARY   = DrawUtils.T_TEXT_PRIMARY;
    private static final int T_TEXT_SECONDARY = DrawUtils.T_TEXT_SECONDARY;
    private static final int T_TEXT_DISABLED  = DrawUtils.T_TEXT_DISABLED;
    private static final int T_SEPARATOR     = DrawUtils.T_SEPARATOR;
    private static final int T_ACCENT_MUTED  = DrawUtils.T_ACCENT_MUTED;
    private static final int T_ACCENT_LIGHT  = DrawUtils.T_ACCENT_LIGHT;
    private static final int T_ACCENT_DARK   = DrawUtils.T_ACCENT_DARK;
    private static final int T_BTN_NORMAL    = DrawUtils.T_BTN_NORMAL;
    private static final int T_BTN_HOVER     = DrawUtils.T_BTN_HOVER;
    private static final int T_BTN_TEXT      = DrawUtils.T_BTN_TEXT;
    private static final int T_BTN_DISABLED  = DrawUtils.T_BTN_DISABLED;
    private static final int T_CARD_OFF_BG   = DrawUtils.T_CARD_OFF_BG;
    private static final int T_SLIDER_BG     = DrawUtils.T_SLIDER_BG;

    private final GuiScreen parent;
    private String originalName;

    // Tab: 0 = accounts list, 1 = add offline, 2 = microsoft login
    private int tab = 0;

    // Offline tab
    private GuiTextField nameField;

    // Microsoft tab
    private MicrosoftAuth.DeviceCode deviceCode;
    private String msStatus = null;
    private int msStatusColor = 0;
    private boolean msLoggingIn = false;
    private boolean msPolling = false;
    private boolean codeCopied = false;

    private long openTime;

    public GuiNameChanger(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        openTime = System.currentTimeMillis();
        KeyboardShim.enableRepeatEvents(true);

        if (originalName == null) {
            originalName = mc.getSession().getUsername();
        }

        int px = (width  - PANEL_W) / 2;
        int py = (height - PANEL_H) / 2;

        nameField = new GuiTextField(0, fontRendererObj, px + 10, py + 42, PANEL_W - 20, 16);
        nameField.setMaxStringLength(16);
        nameField.setFocused(true);
        nameField.setCanLoseFocus(true);
    }

    @Override
    public void onGuiClosed() {
        KeyboardShim.enableRepeatEvents(false);
    }

    @Override
    public void updateScreen() {
        if (tab == 1) nameField.updateCursorCounter();
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int scroll = MouseShim.getEventDWheel();
        if (scroll != 0 && tab == 0) {
            int max = Math.max(0, AccountManager.getAccounts().size() - ACC_MAX_ROWS);
            accountScroll = scroll > 0 ? Math.max(0, accountScroll - 1) : Math.min(max, accountScroll + 1);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        float anim = Math.min(1f, (System.currentTimeMillis() - openTime) / 150f);
        float ease = 1f - (float) Math.pow(1f - anim, 3f);

        Gui.drawRect(0, 0, width, height, COL_OVERLAY);

        int px = (width  - PANEL_W) / 2;
        int py = (height - PANEL_H) / 2 + (int)((1f - ease) * 12);

        DrawUtils.drawRect(px + 3, py + 4, PANEL_W, PANEL_H, T_SHADOW);
        DrawUtils.drawRoundedRect(px, py, PANEL_W, PANEL_H, T_WIN_BG);
        DrawUtils.drawRoundedRectBorder(px, py, PANEL_W, PANEL_H, T_BORDER);

        // Header
        int headerH = 18;
        Gui.drawRect(px + 2, py + 1, px + PANEL_W - 2, py + headerH, 0xFF0D0F1A);
        DrawUtils.drawGradientRect(px + 2, py + 1, PANEL_W - 4, headerH - 1, T_HEADER_TOP, T_HEADER_BOT);
        DrawUtils.drawGradientRect(px + 3, py + 1, PANEL_W - 6, (headerH - 1) / 2, T_GLOSS_TOP, T_GLOSS_BOT);
        String title = "Accounts";
        fontRendererObj.drawString(title, px + (PANEL_W - fontRendererObj.getStringWidth(title)) / 2,
                py + (headerH - 8) / 2, T_TEXT_PRIMARY);
        Gui.drawRect(px + 2, py + headerH, px + PANEL_W - 2, py + headerH + 1, T_SEPARATOR);

        // Tabs
        int tabY = py + headerH + 2;
        int tabW = (PANEL_W - 6) / 3;
        drawTab(px + 2, tabY, tabW, "Accounts", 0, mouseX, mouseY);
        drawTab(px + 2 + tabW, tabY, tabW, "Offline", 1, mouseX, mouseY);
        drawTab(px + 2 + tabW * 2, tabY, tabW + 2, "Microsoft", 2, mouseX, mouseY);

        int contentY = tabY + 14;
        Gui.drawRect(px + 2, contentY, px + PANEL_W - 2, contentY + 1, T_SEPARATOR);
        contentY += 3;

        switch (tab) {
            case 0: drawAccountsList(px, contentY, mouseX, mouseY); break;
            case 1: drawOfflineTab(px, contentY, mouseX, mouseY); break;
            case 2: drawMicrosoftTab(px, contentY, mouseX, mouseY); break;
        }
    }

    private void drawTab(int x, int y, int w, String label, int tabIndex, int mx, int my) {
        boolean active = tab == tabIndex;
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 13;
        int bg = active ? T_ACCENT_DARK : (hover ? T_CARD_OFF_BG : 0x00000000);
        DrawUtils.drawRoundedRect(x, y, w, 13, bg);
        int textColor = active ? T_BTN_TEXT : T_TEXT_SECONDARY;
        fontRendererObj.drawString(label, x + (w - fontRendererObj.getStringWidth(label)) / 2, y + 3, textColor);
    }

    private void drawAccountsList(int px, int contentY, int mouseX, int mouseY) {
        List<Account> accs = AccountManager.getAccounts();
        String curName = AccountManager.currentName();

        if (accs.isEmpty()) {
            fontRendererObj.drawString("No accounts saved", px + 8, contentY + (ACC_MAX_ROWS * ACC_ROW_H - 8) / 2, T_TEXT_DISABLED);
        } else {
            for (int i = accountScroll; i < accountScroll + ACC_MAX_ROWS && i < accs.size(); i++) {
                Account acc = accs.get(i);
                String name = acc.getUsername();
                int rowY = contentY + (i - accountScroll) * ACC_ROW_H;
                boolean cur = name.equals(curName);
                boolean hover = mouseX >= px + 2 && mouseX < px + PANEL_W - 18
                        && mouseY >= rowY && mouseY < rowY + ACC_ROW_H;
                if (cur)
                    drawRect(px + 2, rowY, px + PANEL_W - 18, rowY + ACC_ROW_H, T_ACCENT_MUTED);
                else if (hover)
                    drawRect(px + 2, rowY, px + PANEL_W - 18, rowY + ACC_ROW_H, 0x1A5B7CA8);

                fontRendererObj.drawString(name, px + 8, rowY + (ACC_ROW_H - 8) / 2,
                        cur ? T_ACCENT_LIGHT : T_TEXT_PRIMARY);

                // Type tag
                String tag = acc.getTypeTag();
                int tagColor = acc.getType() == Account.Type.MICROSOFT ? COL_TAG_MS : COL_TAG_OFFLINE;
                int tagW = fontRendererObj.getStringWidth(tag) + 4;
                int tagX = px + PANEL_W - 34 - tagW;
                DrawUtils.drawRoundedRect(tagX, rowY + 2, tagW, 11, tagColor);
                fontRendererObj.drawString(tag, tagX + 2, rowY + (ACC_ROW_H - 8) / 2, T_TEXT_PRIMARY);

                // Remove X
                int xX = px + PANEL_W - 16;
                boolean xHov = mouseX >= xX && mouseX < xX + 10 && mouseY >= rowY && mouseY < rowY + ACC_ROW_H;
                fontRendererObj.drawString("x", xX + 1, rowY + (ACC_ROW_H - 8) / 2,
                        xHov ? 0xFFFF5555 : T_TEXT_DISABLED);
            }
        }
    }

    private void drawOfflineTab(int px, int contentY, int mouseX, int mouseY) {
        fontRendererObj.drawString("Username:", px + 8, contentY, T_TEXT_SECONDARY);
        nameField.yPosition = contentY + 11;
        nameField.xPosition = px + 8;
        nameField.drawTextBox();

        String hint = "Original: " + originalName;
        fontRendererObj.drawString(hint, px + PANEL_W - fontRendererObj.getStringWidth(hint) - 5, contentY, T_TEXT_DISABLED);

        int btnY = contentY + 32;
        int btnW = 80;
        int btnH = 14;
        int addX = px + PANEL_W / 2 - btnW / 2;
        boolean addHover = mouseX >= addX && mouseX <= addX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
        DrawUtils.drawRoundedRect(addX, btnY, btnW, btnH, addHover ? T_BTN_HOVER : T_BTN_NORMAL);
        DrawUtils.drawRoundedRectBorder(addX, btnY, btnW, btnH, T_BORDER);
        String label = "Add & Use";
        fontRendererObj.drawString(label, addX + (btnW - fontRendererObj.getStringWidth(label)) / 2,
                btnY + (btnH - 8) / 2, T_BTN_TEXT);
    }

    private void drawMicrosoftTab(int px, int contentY, int mouseX, int mouseY) {
        if (!MicrosoftAuth.isConfigured()) {
            String l1 = "Azure app not configured.";
            String l2 = "Create plutonium_azure.txt";
            String l3 = "in your .minecraft folder";
            String l4 = "with your Azure client ID.";
            fontRendererObj.drawString(l1, px + (PANEL_W - fontRendererObj.getStringWidth(l1)) / 2, contentY + 10, 0xFFFF8855);
            fontRendererObj.drawString(l2, px + (PANEL_W - fontRendererObj.getStringWidth(l2)) / 2, contentY + 26, T_TEXT_SECONDARY);
            fontRendererObj.drawString(l3, px + (PANEL_W - fontRendererObj.getStringWidth(l3)) / 2, contentY + 38, T_TEXT_SECONDARY);
            fontRendererObj.drawString(l4, px + (PANEL_W - fontRendererObj.getStringWidth(l4)) / 2, contentY + 50, T_TEXT_SECONDARY);
            return;
        }

        if (deviceCode != null && msPolling) {
            String line1 = "Go to:";
            fontRendererObj.drawString(line1, px + 8, contentY, T_TEXT_SECONDARY);

            String url = deviceCode.verificationUri;
            fontRendererObj.drawString(url, px + 8, contentY + 12, T_ACCENT_LIGHT);

            fontRendererObj.drawString("Enter code:", px + 8, contentY + 28, T_TEXT_SECONDARY);

            String code = deviceCode.userCode;
            int codeW = fontRendererObj.getStringWidth(code) + 12;
            int codeX = px + 8;
            int codeY = contentY + 40;
            DrawUtils.drawRoundedRect(codeX, codeY, codeW, 16, T_SLIDER_BG);
            DrawUtils.drawRoundedRectBorder(codeX, codeY, codeW, 16, T_BORDER);
            fontRendererObj.drawString(code, codeX + 6, codeY + 4, 0xFFFFFFFF);

            int copyX = codeX + codeW + 6;
            int copyW = fontRendererObj.getStringWidth(codeCopied ? "Copied!" : "Copy") + 8;
            boolean copyHover = mouseX >= copyX && mouseX < copyX + copyW && mouseY >= codeY && mouseY < codeY + 16;
            DrawUtils.drawRoundedRect(copyX, codeY, copyW, 16, copyHover ? T_BTN_HOVER : T_BTN_NORMAL);
            fontRendererObj.drawString(codeCopied ? "Copied!" : "Copy", copyX + 4, codeY + 4,
                    codeCopied ? 0xFF55FF55 : T_BTN_TEXT);

            fontRendererObj.drawString("Waiting for login...", px + 8, contentY + 64, 0xFF888888);

            int cancelW = 60;
            int cancelH = 14;
            int cancelX = px + PANEL_W / 2 - cancelW / 2;
            int cancelY = contentY + 80;
            boolean cancelHover = mouseX >= cancelX && mouseX < cancelX + cancelW
                    && mouseY >= cancelY && mouseY < cancelY + cancelH;
            DrawUtils.drawRoundedRect(cancelX, cancelY, cancelW, cancelH, cancelHover ? 0xFF3A2222 : 0xFF2A1A1A);
            DrawUtils.drawRoundedRectBorder(cancelX, cancelY, cancelW, cancelH, 0x88FF6655);
            String cancelLbl = "Cancel";
            fontRendererObj.drawString(cancelLbl, cancelX + (cancelW - fontRendererObj.getStringWidth(cancelLbl)) / 2,
                    cancelY + 3, 0xFFFF6655);
        } else {
            String desc1 = "Sign in with your Microsoft";
            String desc2 = "account to play on online servers.";
            fontRendererObj.drawString(desc1, px + (PANEL_W - fontRendererObj.getStringWidth(desc1)) / 2,
                    contentY + 10, T_TEXT_SECONDARY);
            fontRendererObj.drawString(desc2, px + (PANEL_W - fontRendererObj.getStringWidth(desc2)) / 2,
                    contentY + 22, T_TEXT_SECONDARY);

            int btnW = 100;
            int btnH = 16;
            int btnX = px + PANEL_W / 2 - btnW / 2;
            int btnY = contentY + 42;

            if (msLoggingIn) {
                DrawUtils.drawRoundedRect(btnX, btnY, btnW, btnH, T_BTN_DISABLED);
                String lbl = "Starting...";
                fontRendererObj.drawString(lbl, btnX + (btnW - fontRendererObj.getStringWidth(lbl)) / 2,
                        btnY + 4, 0xFF888888);
            } else {
                boolean hover = mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
                DrawUtils.drawRoundedRect(btnX, btnY, btnW, btnH, hover ? T_BTN_HOVER : T_BTN_NORMAL);
                DrawUtils.drawRoundedRectBorder(btnX, btnY, btnW, btnH, T_BORDER);
                String lbl = "Sign In";
                fontRendererObj.drawString(lbl, btnX + (btnW - fontRendererObj.getStringWidth(lbl)) / 2,
                        btnY + 4, T_BTN_TEXT);
            }
        }

        if (msStatus != null) {
            int statusY = contentY + (msPolling ? 100 : 68);
            fontRendererObj.drawString(msStatus, px + 8, statusY, msStatusColor);
        }
    }

    private void applyOffline() {
        String input = nameField.getText().trim();
        if (!input.isEmpty()) {
            AccountManager.addOffline(input);
            AccountManager.switchTo(input);
            tab = 0;
            nameField.setText("");
        }
    }

    private void startMicrosoftLogin() {
        if (msLoggingIn || msPolling) return;
        msLoggingIn = true;
        msStatus = null;
        codeCopied = false;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final MicrosoftAuth.DeviceCode code = MicrosoftAuth.requestDeviceCode();
                    mc.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            deviceCode = code;
                            msLoggingIn = false;
                            msPolling = true;

                            // Clipboard copy — may fail under headless AWT, that's OK
                            try {
                                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                                        .setContents(new java.awt.datatransfer.StringSelection(code.userCode), null);
                                codeCopied = true;
                            } catch (Exception ignored) {}

                            // Open browser — may fail under headless AWT, that's OK
                            try {
                                java.awt.Desktop.getDesktop().browse(new URI(code.verificationUri));
                            } catch (Exception ignored) {}
                        }
                    });

                    final Account account = MicrosoftAuth.loginWithDeviceCode(code);
                    mc.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            AccountManager.addMicrosoft(account);
                            AccountManager.switchTo(account.getUsername());
                            msPolling = false;
                            deviceCode = null;
                            msStatus = "Signed in as " + account.getUsername();
                            msStatusColor = 0xFF55FF55;
                            tab = 0;
                        }
                    });
                } catch (final MicrosoftAuth.AuthException e) {
                    System.err.println("[MicrosoftAuth] Login failed: " + e.getMessage());
                    e.printStackTrace();
                    mc.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            msLoggingIn = false;
                            msPolling = false;
                            deviceCode = null;
                            msStatus = e.getMessage();
                            msStatusColor = 0xFFFF5555;
                        }
                    });
                }
            }
        }, "MicrosoftAuth").start();
    }

    private void cancelMicrosoftLogin() {
        msPolling = false;
        msLoggingIn = false;
        deviceCode = null;
        msStatus = null;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton != 0) return;

        int px = (width  - PANEL_W) / 2;
        int py = (height - PANEL_H) / 2;
        int headerH = 18;

        // Tab clicks
        int tabY = py + headerH + 2;
        int tabW = (PANEL_W - 6) / 3;
        if (mouseY >= tabY && mouseY < tabY + 13) {
            if (mouseX >= px + 2 && mouseX < px + 2 + tabW) { tab = 0; return; }
            if (mouseX >= px + 2 + tabW && mouseX < px + 2 + tabW * 2) {
                tab = 1;
                nameField.setFocused(true);
                return;
            }
            if (mouseX >= px + 2 + tabW * 2 && mouseX < px + PANEL_W - 2) {
                tab = 2;
                return;
            }
        }

        int contentY = tabY + 14 + 4;

        if (tab == 0) {
            handleAccountsListClick(px, contentY, mouseX, mouseY);
        } else if (tab == 1) {
            nameField.mouseClicked(mouseX, mouseY, mouseButton);
            int btnY = contentY + 32;
            int btnW = 80;
            int btnH = 14;
            int addX = px + PANEL_W / 2 - btnW / 2;
            if (mouseX >= addX && mouseX <= addX + btnW && mouseY >= btnY && mouseY <= btnY + btnH) {
                applyOffline();
            }
        } else if (tab == 2) {
            handleMicrosoftClick(px, contentY, mouseX, mouseY);
        }
    }

    private void handleAccountsListClick(int px, int contentY, int mouseX, int mouseY) {
        List<Account> accs = AccountManager.getAccounts();
        for (int i = accountScroll; i < accountScroll + ACC_MAX_ROWS && i < accs.size(); i++) {
            Account acc = accs.get(i);
            int rowY = contentY + (i - accountScroll) * ACC_ROW_H;

            int xX = px + PANEL_W - 16;
            if (mouseX >= xX && mouseX < xX + 10 && mouseY >= rowY && mouseY < rowY + ACC_ROW_H) {
                AccountManager.remove(acc.getUsername());
                accountScroll = Math.max(0, Math.min(accountScroll,
                        AccountManager.getAccounts().size() - ACC_MAX_ROWS));
                return;
            }

            if (mouseX >= px + 2 && mouseX < px + PANEL_W - 18
                    && mouseY >= rowY && mouseY < rowY + ACC_ROW_H) {
                AccountManager.switchTo(acc.getUsername());
                return;
            }
        }
    }

    private void handleMicrosoftClick(int px, int contentY, int mouseX, int mouseY) {
        if (deviceCode != null && msPolling) {
            // Copy button
            String code = deviceCode.userCode;
            int codeW = fontRendererObj.getStringWidth(code) + 12;
            int codeX = px + 8;
            int codeY = contentY + 40;
            int copyX = codeX + codeW + 6;
            int copyW = fontRendererObj.getStringWidth(codeCopied ? "Copied!" : "Copy") + 8;
            if (mouseX >= copyX && mouseX < copyX + copyW && mouseY >= codeY && mouseY < codeY + 16) {
                try {
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new java.awt.datatransfer.StringSelection(deviceCode.userCode), null);
                    codeCopied = true;
                } catch (Exception ignored) {}
                return;
            }

            // Cancel button
            int cancelW = 60;
            int cancelH = 14;
            int cancelX = px + PANEL_W / 2 - cancelW / 2;
            int cancelY = contentY + 80;
            if (mouseX >= cancelX && mouseX < cancelX + cancelW
                    && mouseY >= cancelY && mouseY < cancelY + cancelH) {
                cancelMicrosoftLogin();
                return;
            }
        } else if (!msLoggingIn) {
            // Sign In button
            int btnW = 100;
            int btnH = 16;
            int btnX = px + PANEL_W / 2 - btnW / 2;
            int btnY = contentY + 42;
            if (mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH) {
                startMicrosoftLogin();
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == KeyboardShim.KEY_ESCAPE) {
            if (msPolling) {
                cancelMicrosoftLogin();
                return;
            }
            mc.displayGuiScreen(parent);
            return;
        }

        if (tab == 1) {
            if (keyCode == KeyboardShim.KEY_RETURN || keyCode == KeyboardShim.KEY_NUMPADENTER) {
                applyOffline();
                return;
            }
            nameField.textboxKeyTyped(typedChar, keyCode);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return true;
    }
}
