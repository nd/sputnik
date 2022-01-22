package sputnik;

import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.JBColor;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class SputnikTW implements ToolWindowFactory, DumbAware {
  @Override
  public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    // 'clear histogram' button in ui
    Sputnik s = project.getService(Sputnik.class);
    SputnikPanel panel = new SputnikPanel(toolWindow, s);
    ContentFactory contentFactory = ContentFactory.SERVICE.getInstance();
    Content content = contentFactory.createContent(panel, "", false);
    content.setPreferredFocusedComponent(() -> panel);
    toolWindow.getContentManager().addContent(content);
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      int lastShown = 0;
      while (s.isRunning()) {
        lastShown = s.waitForUpdate(lastShown);
        panel.scheduleRepaint();
      }
    });
  }

  static class SputnikPanel extends JPanel {
    private final AtomicBoolean myScheduled = new AtomicBoolean(false);
    private final ToolWindow myTw;
    private final Sputnik mySputnik;
    private final JBFont myFont;
    volatile Sputnik.HistUi myHist;

    public SputnikPanel(@NotNull ToolWindow tw, @NotNull Sputnik sputnik) {
      myTw = tw;
      mySputnik = sputnik;
      myFont = JBUI.Fonts.create(Font.MONOSPACED, 11);
    }

    void scheduleRepaint() {
      if (myScheduled.compareAndSet(false, true)) {
        myHist = mySputnik.getHist();
        repaint();
      }
    }

    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      myScheduled.set(false);

      UISettings.setupAntialiasing(g);
      g.setFont(myFont);

      int ystart = 30;

      Sputnik.HistUi hist = this.myHist;
      if (hist != null) {
        int size = hist.getSize();
        int total = hist.getTotal();
        int totalWidth = 100;
        for (int i = 0; i < size; i++) {
          String val = hist.getVal(i);
          int count = hist.getCount(i);
          int width = (int) (totalWidth * (count * 1.0 / total));
          int y = ystart + 20 * i;
          if (width == 0) {
            g.drawString(i > 0 ? "rest is smaller than 1%" : "all is smaller than 1%, total: " + total,
                    10, y + 10);
            break;
          }
          Color color = g.getColor();
          g.setColor(JBColor.RED);
          g.fillRect(10, y, width, 10);
          g.setColor(color);
          val = val + " " + count + "/" + total + " (" + (int)(count * 100.0 / total) + "%)";
          g.drawString(val, width + 20, y + 10);
        }
      }
    }
  }
}
