package net.minecraft.client.gui.clickgui;

import java.io.IOException;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.renderer.GlStateManager;

public class GuiQuickConnect extends GuiScreen {

    private final GuiScreen parentScreen;
    private ServerList serverList;
    private int scrollOffset;
    private int selectedIndex = -1;
    private GuiTextField ipField;

    private static final int ENTRY_HEIGHT = 24;
    private static final int PANEL_W = 260;

    public GuiQuickConnect(GuiScreen parent) {
        this.parentScreen = parent;
    }

    public void initGui() {
        this.serverList = new ServerList(this.mc);
        this.serverList.loadServerList();
        this.scrollOffset = 0;
        this.selectedIndex = -1;

        int cx = this.width / 2;
        int bottomY = this.height - 30;

        this.ipField = new GuiTextField(100, this.fontRendererObj, cx - PANEL_W / 2 + 2, bottomY - 24, PANEL_W - 64, 16);
        this.ipField.setMaxStringLength(128);
        this.ipField.setFocused(true);

        this.buttonList.clear();
        this.buttonList.add(new GuiButton(0, cx + PANEL_W / 2 - 58, bottomY - 26, 56, 20, "Connect"));
        this.buttonList.add(new GuiButton(1, cx - 50, bottomY, 100, 20, "Back"));
    }

    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 1) {
            this.mc.displayGuiScreen(this.parentScreen);
        } else if (button.id == 0) {
            String ip = this.ipField.getText().trim();
            if (!ip.isEmpty()) {
                connectTo(ip, ip);
            }
        }
    }

    private void connectTo(String name, String ip) {
        // Properly close the current server connection before switching.
        // GuiConnecting only calls loadWorld(null) which nulls the NetworkManager
        // reference but never closes the channel — Server A would persist until it
        // times out. sendQuittingDisconnectingPacket closes the channel gracefully.
        if (this.mc.theWorld != null) {
            this.mc.theWorld.sendQuittingDisconnectingPacket();
        }
        ServerData data = new ServerData(name, ip, false);
        // Use GuiMainMenu (not parentScreen/GuiIngameMenu) as the fallback so that
        // if Server B kicks us we land at the title screen rather than the in-game
        // menu of the now-disconnected Server A.
        this.mc.displayGuiScreen(new GuiConnecting(new GuiMainMenu(), this.mc, data));
    }

    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            this.mc.displayGuiScreen(this.parentScreen);
            return;
        }
        if (keyCode == 28 || keyCode == 156) {
            String ip = this.ipField.getText().trim();
            if (!ip.isEmpty()) {
                connectTo(ip, ip);
                return;
            }
        }
        this.ipField.textboxKeyTyped(typedChar, keyCode);
    }

    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        this.ipField.mouseClicked(mouseX, mouseY, mouseButton);

        int cx = this.width / 2;
        int panelX = cx - PANEL_W / 2;
        int listTop = 60;
        int listBottom = this.height - 62;
        int visibleCount = (listBottom - listTop) / ENTRY_HEIGHT;

        if (mouseX >= panelX && mouseX <= panelX + PANEL_W && mouseY >= listTop && mouseY < listBottom) {
            int idx = (mouseY - listTop) / ENTRY_HEIGHT + scrollOffset;
            if (idx >= 0 && idx < serverList.countServers()) {
                if (mouseButton == 0) {
                    if (idx == selectedIndex) {
                        ServerData sd = serverList.getServerData(idx);
                        connectTo(sd.serverName, sd.serverIP);
                        return;
                    }
                    selectedIndex = idx;
                }
            }
        }
    }

    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = plutonium.gpu.MouseShim.getEventDWheel();
        if (wheel != 0) {
            int listTop = 60;
            int listBottom = this.height - 62;
            int visibleCount = (listBottom - listTop) / ENTRY_HEIGHT;
            int maxScroll = Math.max(0, serverList.countServers() - visibleCount);
            if (wheel > 0) scrollOffset = Math.max(0, scrollOffset - 1);
            else scrollOffset = Math.min(maxScroll, scrollOffset + 1);
        }
    }

    public void updateScreen() {
        this.ipField.updateCursorCounter();
    }

    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        Gui.drawRect(0, 0, this.width, this.height, 0xB00E1118);

        int cx = this.width / 2;
        int panelX = cx - PANEL_W / 2;
        int headerY = 10;
        int headerH = 20;

        DrawUtils.drawRoundedRect(panelX, headerY, PANEL_W, headerH, DrawUtils.T_WIN_BG);
        DrawUtils.drawRoundedRectBorder(panelX, headerY, PANEL_W, headerH, DrawUtils.T_BORDER);
        DrawUtils.drawGradientRect(panelX + 2, headerY + 1, PANEL_W - 4, headerH - 2, DrawUtils.T_HEADER_TOP, DrawUtils.T_HEADER_BOT);
        DrawUtils.drawGradientRect(panelX + 3, headerY + 1, PANEL_W - 6, (headerH - 2) / 2, 0x22FFFFFF, 0x00FFFFFF);

        String title = "Quick Connect";
        int titleW = this.fontRendererObj.getStringWidth(title);
        this.fontRendererObj.drawString(title, panelX + (PANEL_W - titleW) / 2, headerY + (headerH - 8) / 2, DrawUtils.T_TEXT_PRIMARY);

        int listTop = 38;
        int listBottom = this.height - 62;
        int visibleCount = (listBottom - listTop) / ENTRY_HEIGHT;

        DrawUtils.drawRoundedRect(panelX - 1, listTop - 1, PANEL_W + 2, listBottom - listTop + 2, DrawUtils.T_WIN_BG);
        DrawUtils.drawRoundedRectBorder(panelX - 1, listTop - 1, PANEL_W + 2, listBottom - listTop + 2, DrawUtils.T_BORDER);

        int count = serverList.countServers();
        if (count == 0) {
            String empty = "No saved servers";
            int ew = this.fontRendererObj.getStringWidth(empty);
            this.fontRendererObj.drawString(empty, cx - ew / 2, listTop + 10, 0xFF44445A);
        } else {
            for (int i = 0; i < visibleCount && (i + scrollOffset) < count; i++) {
                int idx = i + scrollOffset;
                ServerData sd = serverList.getServerData(idx);
                int ey = listTop + i * ENTRY_HEIGHT;

                boolean hovered = mouseX >= panelX && mouseX <= panelX + PANEL_W && mouseY >= ey && mouseY < ey + ENTRY_HEIGHT;
                boolean selected = idx == selectedIndex;

                if (selected) {
                    Gui.drawRect(panelX, ey, panelX + PANEL_W, ey + ENTRY_HEIGHT - 1, 0x44524ACC);
                } else if (hovered) {
                    Gui.drawRect(panelX, ey, panelX + PANEL_W, ey + ENTRY_HEIGHT - 1, 0x22FFFFFF);
                }

                this.fontRendererObj.drawString(sd.serverName, panelX + 4, ey + 3, DrawUtils.T_TEXT_PRIMARY);
                this.fontRendererObj.drawString(sd.serverIP, panelX + 4, ey + 13, DrawUtils.T_TEXT_SECONDARY);

                Gui.drawRect(panelX + 2, ey + ENTRY_HEIGHT - 1, panelX + PANEL_W - 2, ey + ENTRY_HEIGHT, 0x18FFFFFF);
            }

            if (count > visibleCount) {
                int scrollBarH = listBottom - listTop;
                int maxScroll = Math.max(1, count - visibleCount);
                int thumbH = Math.max(10, scrollBarH * visibleCount / count);
                int thumbY = listTop + (scrollBarH - thumbH) * scrollOffset / maxScroll;
                int sbX = panelX + PANEL_W - 3;
                Gui.drawRect(sbX, listTop, sbX + 2, listBottom, 0x22FFFFFF);
                Gui.drawRect(sbX, thumbY, sbX + 2, thumbY + thumbH, DrawUtils.T_BORDER);
            }
        }

        String hint = "Double-click a server or type IP below";
        int hw = this.fontRendererObj.getStringWidth(hint);
        this.fontRendererObj.drawString(hint, cx - hw / 2, listBottom + 4, 0xFF44445A);

        this.ipField.drawTextBox();

        GlStateManager.disableBlend();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
