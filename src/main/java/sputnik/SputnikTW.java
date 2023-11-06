package sputnik;

import com.intellij.icons.AllIcons;
import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
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
    Content content = ContentFactory.getInstance().createContent(scrollPane, "", false);
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
    private final List<ActionBounds> myActionBounds = new ArrayList<>();
    private volatile boolean myDrawHiCumulative = false;

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
                } else if ("hi".equals(b.type)) {
                  mySputnik.deleteHi();
                }
                scheduleRepaint();
              });
              return;
            }
          }
          for (ActionBounds b : myActionBounds) {
            if (b.rect.contains(point)) {
              b.action.run();
              scheduleRepaint();
              return;
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
      List<Sputnik.HiUi> his = mySputnik.getHis();

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

      y += his.size() * (200 + 20);

      Dimension size = getSize();
      if (y != size.height) {
        setPreferredSize(new Dimension(size.width, y));
        revalidate();
      }

      myCloseBounds.clear();
      myActionBounds.clear();

      y = 30;
      for (Sputnik.HistUi hist : myHists) {
        y = drawHist(g, y, hist, false);
        y += 20;
      }

      for (Sputnik.ChartUi chart : charts) {
        y += drawChart(g, y, chart, false);
        y += 20;
      }

      for (Sputnik.HiUi hi : his) {
        y += drawHi(g, y, hi);
        y += 20;
      }
    }

    private int drawHi(Graphics g, int y, Sputnik.HiUi hi) {
      if (myDrawHiCumulative) {
        return drawHiCumulative(g, y, hi);
      }

      int heightPx = 2;
      int widthPx = 2;
      g.drawRect(10, y, widthPx * 100, heightPx * 100);
      float scale = 100.0f / hi.myMaxPercent;
      int yStart = y;
      g.setColor(JBColor.GRAY);
      g.drawLine(
              10 + widthPx * 25, yStart,
              10 + widthPx * 25, yStart + heightPx * 100);
      g.drawLine(
              10 + widthPx * 50, yStart,
              10 + widthPx * 50, yStart + heightPx * 100);
      g.drawLine(
              10 + widthPx * 75, yStart,
              10 + widthPx * 75, yStart + heightPx * 100);

      long height = hi.myMax - hi.myMin;
      double step;
      if (height >= 10) {
        step = (double) height / 10;
      } else {
        step = 1.0;
      }

      // draw first label with text layout to get bounds, needed for close icon
      g.setColor(JBColor.BLACK);
      int labelsXOffset = 10 + widthPx * 100 + 10;
      TextLayout tl = new TextLayout("" + hi.myMin, myFont, ((Graphics2D) g).getFontRenderContext());
      tl.draw((Graphics2D) g, labelsXOffset, yStart + 5);
      Rectangle2D bounds = tl.getBounds();

      long prev = hi.myMin;
      for (int i = 1; i <= 10; i++) {
        long val = (long) (prev + step);
        if (val > hi.myMax) {
          break;
        }
        float part = (float) ((val - hi.myMin) * 1.0 / height);
        int tickY = yStart + (int) (part * heightPx * 100);
        g.drawString("" + val, labelsXOffset, tickY + 5);
        prev = val;
      }

      g.setColor(JBColor.RED);
      for (float i : hi.myHist) {
        g.fillRect(10, y, widthPx * (int) (i * scale), heightPx);
        y += heightPx;
      }

      AllIcons.Actions.Close.paintIcon(this, g,
              (int) (labelsXOffset + bounds.getWidth()),
              yStart - AllIcons.Actions.Close.getIconHeight());

      bounds.setRect(
              labelsXOffset + bounds.getWidth(),
              yStart - AllIcons.Actions.Close.getIconHeight(),
              AllIcons.Actions.Close.getIconWidth(),
              AllIcons.Actions.Close.getIconHeight());
      myCloseBounds.add(new CloseBounds(bounds, "hi", ""));

      myActionBounds.add(new ActionBounds(new Rectangle2D.Float(10, yStart, widthPx * 100, heightPx * 100), () -> {
        myDrawHiCumulative = true;
      }));

      return y;
    }

    private int drawHiCumulative(Graphics g, int y, Sputnik.HiUi hi) {
      int heightPx = 2;
      int widthPx = 2;
      g.drawRect(10, y, widthPx * 100, heightPx * 100);
      float scale = 1.0f;
      int yStart = y;
      g.setColor(JBColor.GRAY);

      Stroke dashedStroke = new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{3}, 0);
      Stroke defaultStroke = ((Graphics2D) g).getStroke();
      Stroke[] strokes = new Stroke[]{dashedStroke, defaultStroke};
      int strokeIdx = 0;

      ((Graphics2D) g).setStroke(strokes[strokeIdx++ % strokes.length]);
      g.drawLine(
              10 + widthPx * 25, yStart,
              10 + widthPx * 25, yStart + heightPx * 100);
      ((Graphics2D) g).setStroke(strokes[strokeIdx++ % strokes.length]);
      g.drawLine(
              10 + widthPx * 50, yStart,
              10 + widthPx * 50, yStart + heightPx * 100);
      ((Graphics2D) g).setStroke(strokes[strokeIdx++ % strokes.length]);
      g.drawLine(
              10 + widthPx * 75, yStart,
              10 + widthPx * 75, yStart + heightPx * 100);
      ((Graphics2D) g).setStroke(strokes[strokeIdx++ % strokes.length]);
      g.drawLine(
              10 + widthPx * 90, yStart,
              10 + widthPx * 90, yStart + heightPx * 100);
      ((Graphics2D) g).setStroke(strokes[strokeIdx++ % strokes.length]);
      g.drawLine(
              10 + widthPx * 99, yStart,
              10 + widthPx * 99, yStart + heightPx * 100);

      g.setColor(JBColor.BLACK);

      g.drawString("25", 5 + widthPx * 25, yStart);
      g.drawString("50", 5 + widthPx * 50, yStart);
      g.drawString("75", 5 + widthPx * 75, yStart);
      g.drawString("90", 5 + widthPx * 90, yStart);
      g.drawString("99", 5 + widthPx * 99, yStart);

      long height = hi.myMax - hi.myMin;

      // draw first label with text layout to get bounds, needed for close icon
      int labelsXOffset = 10 + widthPx * 100 + 10;
      TextLayout tl = new TextLayout("" + hi.myMin, myFont, ((Graphics2D) g).getFontRenderContext());
      tl.draw((Graphics2D) g, labelsXOffset, yStart + 5);
      Rectangle2D bounds = tl.getBounds();

      strokeIdx = 0;
      int[] percentiles = new int[]{25, 50, 75, 90, 99, 101};
      int percIdx = 0;
      g.setColor(JBColor.RED);
      float x = 0;
      for (int i = 0; i < hi.myHist.length; i++) {
        float percent = hi.myHist[i];
        g.fillRect(10, y, widthPx * (int) (x * scale), heightPx);
        y += heightPx;
        x += percent;
        if (percIdx < percentiles.length && x >= percentiles[percIdx]) {
          g.setColor(JBColor.GRAY);
          ((Graphics2D) g).setStroke(strokes[strokeIdx++ % strokes.length]);
          g.drawLine(
                  10 + widthPx * (int)x, y,
                  10 + widthPx * 100, y);
          g.setColor(JBColor.BLACK);
          g.drawString((long)(hi.myMin + height * i * 0.01) + " (" + percentiles[percIdx] + "%)", labelsXOffset, y + 5);
          g.setColor(JBColor.RED);
          ((Graphics2D) g).setStroke(defaultStroke);
          percIdx++;
        }
      }

      g.setColor(JBColor.BLACK);
      g.drawString("" + hi.myMax, labelsXOffset, y + 15);

      AllIcons.Actions.Close.paintIcon(this, g,
              (int) (labelsXOffset + bounds.getWidth()),
              yStart - AllIcons.Actions.Close.getIconHeight());

      bounds.setRect(
              labelsXOffset + bounds.getWidth(),
              yStart - AllIcons.Actions.Close.getIconHeight(),
              AllIcons.Actions.Close.getIconWidth(),
              AllIcons.Actions.Close.getIconHeight());
      myCloseBounds.add(new CloseBounds(bounds, "hi", ""));

      myActionBounds.add(new ActionBounds(new Rectangle2D.Float(10, yStart, widthPx * 100, heightPx * 100), () -> {
        myDrawHiCumulative = false;
      }));

      return y;
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

    static class ActionBounds {
      private final Rectangle2D rect;
      private final Runnable action;

      public ActionBounds(@NotNull Rectangle2D rect, @NotNull Runnable action) {
        this.rect = rect;
        this.action = action;
      }
    }
  }
}
