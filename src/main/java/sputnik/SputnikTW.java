package sputnik;

import com.intellij.icons.AllIcons;
import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextLayout;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class SputnikTW implements ToolWindowFactory, DumbAware {
  @Override
  public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    Sputnik s = project.getService(Sputnik.class);
    SputnikPanel panel = new SputnikPanel(toolWindow, s);
    JBScrollPane scrollPane = new JBScrollPane(panel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    Content content = ContentFactory.SERVICE.getInstance().createContent(scrollPane, "", false);
    content.setPreferredFocusedComponent(() -> scrollPane);
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
    private static final JBColor[] colors = new JBColor[]{JBColor.RED, JBColor.BLUE, JBColor.GREEN, JBColor.YELLOW};

    private final AtomicBoolean myScheduled = new AtomicBoolean(false);
    private final ToolWindow myTw;
    private final Sputnik mySputnik;
    private final Font myFont;
    private final Font myBoldFont;
    private final List<CloseBounds> myCloseBounds = new ArrayList<>();


    public SputnikPanel(@NotNull ToolWindow tw, @NotNull Sputnik sputnik) {
      myTw = tw;
      mySputnik = sputnik;
      myFont = JBUI.Fonts.create(Font.MONOSPACED, 11);
      myBoldFont = myFont.deriveFont(Font.BOLD);

      addMouseListener(new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent e) {
          super.mouseClicked(e);
          Point point = e.getPoint();
          for (CloseBounds b : myCloseBounds) {
            if (b.rect.contains(point)) {
              ApplicationManager.getApplication().executeOnPooledThread(() -> {
                if ("hist".equals(b.type)) {
                  mySputnik.deleteHist(b.name);
                } else if ("chart".equals(b.type)) {
                  mySputnik.deleteChart(b.name);
                }
                scheduleRepaint();
              });
              break;
            }
          }
        }
      });
    }

    void scheduleRepaint() {
      if (myScheduled.compareAndSet(false, true)) {
        repaint();
      }
    }

    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      myScheduled.set(false);
      List<Sputnik.HistUi> myHists = mySputnik.getHist();
      List<Sputnik.ChartUi> charts = mySputnik.getCharts();

      UISettings.setupAntialiasing(g);
      g.setFont(myFont);

      int y = 30;
      for (Sputnik.HistUi hist : myHists) {
        y = drawHist(g, y, hist, true);
        y += 20;
      }

      for (Sputnik.ChartUi chart : charts) {
        y += drawChart(g, y, chart, true);
        y += 20;
      }

      Dimension size = getSize();
      if (y != size.height) {
        setPreferredSize(new Dimension(size.width, y));
        revalidate();
      }

      myCloseBounds.clear();

      y = 30;
      for (Sputnik.HistUi hist : myHists) {
        y = drawHist(g, y, hist, false);
        y += 20;
      }

      for (Sputnik.ChartUi chart : charts) {
        y += drawChart(g, y, chart, false);
        y += 20;
      }
    }

    private int drawHist(Graphics g, int y, Sputnik.HistUi hist, boolean dryRun) {
      int size = hist.getSize();
      int total = hist.getTotal();
      float k = 1.0f / total;

      int totalWidth = 100;
      int rowHeight = 10;

      if (!dryRun) {
        String namePrefix = hist.getHistName();
        if (!namePrefix.isEmpty()) {
          namePrefix += ", ";
        }
        String title = namePrefix + "total: " + total;
        TextLayout tl = new TextLayout(title, myBoldFont, ((Graphics2D) g).getFontRenderContext());
        tl.draw((Graphics2D) g, 10, y);
        Rectangle2D bounds = tl.getBounds();
        bounds.setRect(10 + (int) bounds.getMaxX() + 5,
                bounds.getY() + y - 1 - ((AllIcons.Actions.Close.getIconHeight() - rowHeight) / 2.0),
                AllIcons.Actions.Close.getIconWidth(),
                AllIcons.Actions.Close.getIconHeight());

        myCloseBounds.add(new CloseBounds(bounds, "hist", hist.getHistName()));
        AllIcons.Actions.Close.paintIcon(this, g, (int) bounds.getX(), (int) bounds.getY());
      }

      y += rowHeight;

      for (int i = 0; i < size; i++) {
        int count = hist.getCount(i);
        float bucketContrib = count * k;
        if (bucketContrib < 0.01) {
          if (!dryRun) {
            g.drawString((i > 0 ? "rest" : "all") + " are smaller than 1%", 10, y + rowHeight);
          }
          y += 2 * rowHeight;
          break;
        }

        if (!dryRun) {
          int width = (int) (totalWidth * bucketContrib);
          Color color = g.getColor();
          g.setColor(JBColor.RED);
          g.fillRect(10, y, width, rowHeight);
          g.setColor(color);

          String bucketText = hist.getBucketName(i) + " " + count + " (" + (int) (100 * bucketContrib) + "%)";
          g.drawString(bucketText, width + 20, y + rowHeight);
        }
        y += 2 * rowHeight;
      }

      return y;
    }

    private int drawChart(Graphics g, int y, Sputnik.ChartUi chart, boolean dryRun) {
      int max = 0;
      for (Sputnik.SeriesUi series : chart.series.values()) {
        for (int count : series.counts) {
          max = Math.max(max, count);
        }
      }

      int rowHeight = 10;

      if (!dryRun) {
        TextLayout tl = new TextLayout(chart.name, myBoldFont, ((Graphics2D) g).getFontRenderContext());
        tl.draw((Graphics2D) g, 10, y);
        Rectangle2D bounds = tl.getBounds();
        bounds.setRect(10 + (int) bounds.getMaxX() + 5,
                bounds.getY() + y - 1 - ((AllIcons.Actions.Close.getIconHeight() - rowHeight) / 2.0),
                AllIcons.Actions.Close.getIconWidth(),
                AllIcons.Actions.Close.getIconHeight());

        myCloseBounds.add(new CloseBounds(bounds, "chart", chart.name));
        AllIcons.Actions.Close.paintIcon(this, g, (int) bounds.getX(), (int) bounds.getY());
      }

      y += rowHeight;

      int rectHeight = 100;
      int tickSize = 30;
      y += rectHeight; // now at the bottom of the chart
      Color prevColor = g.getColor();
      if (!dryRun) {
        g.drawRect(10, y - rectHeight, (chart.size - 1) * tickSize, rectHeight);
        g.drawString("0", 10 + (chart.size - 1) * tickSize + 10, y);
        g.drawString(String.valueOf(max), 10 + (chart.size - 1) * tickSize + 10, y - rectHeight + rowHeight);
        if (max != 0) {
          float k = 100.0f / max;
          int colorIdx = 0;
          for (Sputnik.SeriesUi series : chart.series.values()) {
            JBColor color = colors[colorIdx];
            g.setColor(color);
            int x = 10;
            GeneralPath path = new GeneralPath();
            path.moveTo(x, y - k * series.counts[(series.writeIdx) % chart.size]);
            x += tickSize;
            for (int i = 1; i < chart.size; i++) {
              path.lineTo(x, y - k * series.counts[(series.writeIdx + i) % chart.size]);
              x += tickSize;
            }
            ((Graphics2D) g).draw(path);
            colorIdx = (colorIdx + 1) % colors.length;
          }
        }
      }

      y += 2 * rowHeight;
      int colorIdx = 0;
      for (Sputnik.SeriesUi series : chart.series.values()) {
        JBColor color = colors[colorIdx];
        g.setColor(color);
        g.drawLine(10, y - 5, 10 + tickSize, y - 5);
        g.drawString(series.name, 10 + tickSize + 10, y);
        y += 2 * rowHeight;
        colorIdx = (colorIdx + 1) % colors.length;
      }
      g.setColor(prevColor);
      return y;
    }

    static class CloseBounds {
      private final Rectangle2D rect;
      private final String type;
      private final String name;

      public CloseBounds(Rectangle2D rect, String type, String name) {
        this.rect = rect;
        this.type = type;
        this.name = name;
      }
    }
  }
}
