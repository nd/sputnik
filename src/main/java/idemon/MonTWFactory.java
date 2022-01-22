package idemon;

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

public class MonTWFactory implements ToolWindowFactory, DumbAware {
  @Override
  public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    // 'clear histogram' button in ui
    Mon mon = project.getService(Mon.class);
    MonTW tw = new MonTW(toolWindow, mon);
    ContentFactory contentFactory = ContentFactory.SERVICE.getInstance();
    Content content = contentFactory.createContent(tw, "", false);
    content.setPreferredFocusedComponent(() -> tw);
    toolWindow.getContentManager().addContent(content);
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      int lastShown = 0;
      while (mon.isRunning()) {
        lastShown = mon.waitForUpdate(lastShown);
        tw.scheduleRepaint();
      }
    });
  }

  static class MonTW extends JPanel {
    private final AtomicBoolean myScheduled = new AtomicBoolean(false);
    private final ToolWindow myTw;
    private final Mon myMon;
    private final JBFont myFont;
    volatile Mon.HistUi myHist;

    public MonTW(@NotNull ToolWindow tw, @NotNull Mon mon) {
      myTw = tw;
      myMon = mon;
      myFont = JBUI.Fonts.create(Font.MONOSPACED, 11);
    }

    void scheduleRepaint() {
      if (myScheduled.compareAndSet(false, true)) {
        myHist = myMon.getHist();
        repaint();
      }
    }

    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      myScheduled.set(false);

      UISettings.setupAntialiasing(g);
      g.setFont(myFont);

      int ystart = 30;
      if (false) {
        Mon.CacheUi cacheUi = myMon.getCacheUi();
        ystart+=20;
        long total[] = new long[128];
        long hit[] = new long[128];
        cacheUi.drainTo(total, hit);

        long totalMin = Long.MAX_VALUE;
        long totalMax = 0;
        for (long t : total) {
          totalMin = Math.min(totalMin, t);
          totalMax = Math.max(totalMax, t);
        }
        int yrange = (int) (totalMax - totalMin);
        int xs[] = new int[128];
        int ys1[] = new int[128];
        int ys2[] = new int[128];
        int x = 10;
        for (int i = 0; i < 128; i++) {
          xs[i] = x;
          x+=10;
          ys1[i] = ystart + 100 - ((int)(((int)(total[i]-totalMin)) * 1.0/yrange)) * 100;
          ys2[i] = ystart + 100 - ((int)(((int)(hit[i]-totalMin)) * 1.0/yrange)) * 100;
        }
        g.drawString(cacheUi.getId() + " " + (total[127] != 0 ? hit[127] * 100.0/ total[127] : ""), 10, ystart+20);
        Color color = g.getColor();
        g.setColor(JBColor.RED);
        g.drawPolyline(xs, ys1, 128);
        g.drawPolyline(xs, ys2, 128);
        g.setColor(color);
        ystart += 200;
      }

      Mon.HistUi hist = this.myHist;
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
