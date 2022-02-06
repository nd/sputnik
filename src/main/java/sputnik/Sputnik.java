package sputnik;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.*;

@Service
public final class Sputnik implements Disposable {

  private final AtomicBoolean myStarted = new AtomicBoolean();
  private final AtomicBoolean myStop = new AtomicBoolean();
  private final ArrayBlockingQueue<Cmd> myCmds = new ArrayBlockingQueue<>(16 * 1024);

  // hist name -> (bucket name -> hit count)
  private final Map<String, Map<String, Integer>> myHists = new HashMap<>();

  // chart name -> (series -> count)
  private final Map<String, Map<String, Integer>> myCharts = new HashMap<>();
  private final Map<String, ChartUi> myChartUis = new HashMap<>();

  private final Lock myUpdatedLock = new ReentrantLock();
  private final Condition myUpdated = myUpdatedLock.newCondition();
  private final AtomicInteger myUpdateCounter = new AtomicInteger();

  private final ReadWriteLock myLock = new ReentrantReadWriteLock();

  void start() {
    if (myStarted.compareAndSet(false, true)) {
      ApplicationManager.getApplication().executeOnPooledThread(this::processQueue);
      AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(this::sampleCharts, 0, 1, TimeUnit.SECONDS);
    }
  }

  boolean isRunning() {
    return !myStop.get();
  }

  int waitForUpdate(int lastShownState) {
    myUpdatedLock.lock();
    try {
      if (lastShownState != myUpdateCounter.get()) {
        return myUpdateCounter.get();
      }
      try {
        myUpdated.await();
      } catch (InterruptedException e) {
        //
      }
      return myUpdateCounter.get();
    } finally {
      myUpdatedLock.unlock();
    }
  }

  void h(@NotNull String histName, @NotNull String bucketName) {
    myCmds.offer(new HistCmd(histName, bucketName));
  }

  void hr(@NotNull String histName) {
    myCmds.offer(new ClearHistCmd(histName));
  }

  void c(@NotNull String chartName, @NotNull String seriesName) {
    myCmds.offer(new ChartCmd(chartName, seriesName));
  }

  void deleteHist(@NotNull String histName) {
    myCmds.add(new DeleteCmd("hist", histName)); // not offer, because we don't want to miss click on close in UI
  }

  void deleteChart(@NotNull String chartName) {
    myCmds.add(new DeleteCmd("chart", chartName)); // not offer, because we don't want to miss click on close in UI
  }

  @Override
  public void dispose() {
    myStop.set(true);
  }

  @NotNull List<ChartUi> getCharts() {
    List<ChartUi> result = new ArrayList<>();
    myLock.readLock().lock();
    try {
      for (ChartUi chartui : myChartUis.values()) {
        result.add(chartui.copy());
      }
    } finally {
      myLock.readLock().unlock();
    }
    result.sort(Comparator.comparing(o -> o.name));
    return result;
  }

  @NotNull List<HistUi> getHist() {
    Map<String, Map<String, Integer>> hists = new HashMap<>();
    myLock.readLock().lock();
    try {
      for (String histName : myHists.keySet()) {
        Map<String, Integer> buckets = myHists.get(histName);
        hists.put(histName, new HashMap<>(buckets));
      }
    } finally {
      myLock.readLock().unlock();
    }

    List<HistUi> histUis = new ArrayList<>();
    for (Map.Entry<String, Map<String, Integer>> kv : hists.entrySet()) {
      String histName = kv.getKey();
      Map<String, Integer> histData = kv.getValue();

      Hist hist = new Hist();
      if (histData != null) {
        hist.myMap.putAll(histData);
      }

      List<String> vals = new ArrayList<>(hist.myMap.keySet());
      vals.sort((o1, o2) -> {
        int i1 = hist.myMap.get(o1);
        int i2 = hist.myMap.get(o2);
        return -Integer.compare(i1, i2);
      });
      int[] counts = new int[vals.size()];
      int total = 0;
      for (int i = 0; i < counts.length; i++) {
        int itemCount = hist.myMap.get(vals.get(i));
        counts[i] = itemCount;
        total += itemCount;
      }

      histUis.add(new HistUi(histName, vals, counts, total));
    }

    histUis.sort(Comparator.comparing(h -> h.myHistName));
    return histUis;
  }

  private void processQueue() {
    List<Cmd> buf = new ArrayList<>(myCmds.size());
    Cmd cmd;
    try {
      while (true) {
        buf.clear();
        cmd = myCmds.poll(5, TimeUnit.SECONDS);
        if (myStop.get()) {
          return;
        }
        if (cmd != null) {
          buf.add(cmd);
          myCmds.drainTo(buf);
          processCmds(buf);
        }
      }
    } catch (InterruptedException e) {
      // exit
    }
  }

  static class SeriesUi {
    final String name;
    final int[] counts;
    int writeIdx = 0;

    SeriesUi(String name, int size) {
      this.name = name;
      counts = new int[size];
    }

    void addCount(int count) {
      counts[writeIdx] = count;
      writeIdx = (writeIdx + 1) % counts.length;
    }

    SeriesUi copy() {
      SeriesUi result = new SeriesUi(name, counts.length);
      System.arraycopy(counts, 0, result.counts, 0, counts.length);
      result.writeIdx = writeIdx;
      return result;
    }
  }

  static class ChartUi {
    final int size = 10;
    final String name;
    final TreeMap<String, SeriesUi> series = new TreeMap<>();

    public ChartUi(String name) {
      this.name = name;
    }

    void addCount(String seriesName, int count) {
      SeriesUi seriesUi = series.get(seriesName);
      if (seriesUi == null) {
        seriesUi = new SeriesUi(seriesName, size);
        series.put(seriesName, seriesUi);
      }
      seriesUi.addCount(count);
    }

    ChartUi copy() {
      ChartUi result = new ChartUi(name);
      for (SeriesUi value : series.values()) {
        result.series.put(value.name, value.copy());
      }
      return result;
    }
  }

  private void sampleCharts() {
    boolean updated = false;
    myLock.writeLock().lock(); // writeLock because we will reset counters to 0
    try {
      for (Map.Entry<String, Map<String, Integer>> kv : myCharts.entrySet()) {
        updated = true;
        String chartName = kv.getKey();
        ChartUi chartUi = myChartUis.get(chartName);
        if (chartUi == null) {
          chartUi = new ChartUi(chartName);
          myChartUis.put(chartName, chartUi);
        }
        Map<String, Integer> counters = kv.getValue();
        for (Map.Entry<String, Integer> counterVal : counters.entrySet()) {
          String series = counterVal.getKey();
          Integer val = counterVal.getValue();
          chartUi.addCount(series, val);
          counterVal.setValue(0);
        }
      }
    } finally {
      myLock.writeLock().unlock();
    }

    if (updated) {
      myUpdatedLock.lock();
      try {
        myUpdateCounter.incrementAndGet();
        myUpdated.signal();
      } finally {
        myUpdatedLock.unlock();
      }
    }
  }

  private void processCmds(@NotNull List<Cmd> cmds) {
    myLock.writeLock().lock();
    try {
      for (Cmd cmd : cmds) {
        if (cmd instanceof ClearHistCmd) {
          Map<String, Integer> hist = myHists.get(((ClearHistCmd) cmd).myHistName);
          if (hist != null) {
            hist.clear();
          }
        } else if (cmd instanceof HistCmd) {
          HistCmd histCmd = (HistCmd) cmd;
          Map<String, Integer> hist = myHists.computeIfAbsent(histCmd.myHistName, k -> new HashMap<>());
          hist.merge(histCmd.myBucketName, 1, Integer::sum);
        } else if (cmd instanceof ChartCmd) {
          ChartCmd chartCmd = (ChartCmd) cmd;
          Map<String, Integer> series = myCharts.computeIfAbsent(chartCmd.myChartName, k -> new HashMap<>());
          series.merge(chartCmd.mySeriesName, 1, Integer::sum);
        } else if (cmd instanceof DeleteCmd) {
          if (((DeleteCmd) cmd).myType.equals("hist")) {
            myHists.remove(((DeleteCmd) cmd).myName);
          } else if (((DeleteCmd) cmd).myType.equals("chart")) {
            myCharts.remove(((DeleteCmd) cmd).myName);
            myChartUis.remove(((DeleteCmd) cmd).myName);
          }
        }
      }
    } finally {
      myLock.writeLock().unlock();
    }

    myUpdatedLock.lock();
    try {
      myUpdateCounter.incrementAndGet();
      myUpdated.signal();
    } finally {
      myUpdatedLock.unlock();
    }
  }

  private static class Hist {
    private final Map<String, Integer> myMap = new HashMap<>();
  }

  static class HistUi {
    private final String myHistName;
    private final List<String> myVals;
    private final int[] myCounts;
    private final int myTotal;

    public HistUi(String histName, List<String> vals, int[] counts, int total) {
      myHistName = histName;
      myVals = vals;
      myCounts = counts;
      myTotal = total;
    }

    @NotNull
    String getHistName() {
      return myHistName;
    }

    int getSize() {
      return myVals.size();
    }

    String getBucketName(int i) {
      return myVals.get(i);
    }

    int getCount(int i) {
      return myCounts[i];
    }

    int getTotal() {
      return myTotal;
    }
  }

  interface Cmd {
  }

  private static class ClearHistCmd implements Cmd {
    private final String myHistName;

    public ClearHistCmd(@NotNull String histName) {
      myHistName = histName;
    }
  }

  private static class DeleteCmd implements Cmd {
    private final String myType;
    private final String myName;

    public DeleteCmd(@NotNull String type, @NotNull String name) {
      myType = type;
      myName = name;
    }
  }

  private static class HistCmd implements Cmd {
    private final String myHistName;
    private final String myBucketName;

    public HistCmd(@NotNull String histName, @NotNull String bucketName) {
      myHistName = histName;
      myBucketName = bucketName;
    }
  }

  private static class ChartCmd implements Cmd {
    private final String myChartName;
    private final String mySeriesName;

    public ChartCmd(@NotNull String chartName, @NotNull String seriesName) {
      myChartName = chartName;
      mySeriesName = seriesName;
    }
  }
}
