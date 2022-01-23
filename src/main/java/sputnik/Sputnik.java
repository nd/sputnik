package sputnik;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
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

  private final Lock myUpdatedLock = new ReentrantLock();
  private final Condition myUpdated = myUpdatedLock.newCondition();
  private final AtomicInteger myUpdateCounter = new AtomicInteger();

  private final ReadWriteLock myLock = new ReentrantReadWriteLock();

  void start() {
    if (myStarted.compareAndSet(false, true)) {
      ApplicationManager.getApplication().executeOnPooledThread(this::processQueue);
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
    myCmds.offer(new ClearCmd(histName));
  }

  void delete(@NotNull String histName) {
    myCmds.add(new DeleteCmd(histName)); // not offer, because we don't want to miss click on close in UI
  }

  @Override
  public void dispose() {
    myStop.set(true);
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

  private void processCmds(@NotNull List<Cmd> cmds) {
    myLock.writeLock().lock();
    try {
      for (Cmd cmd : cmds) {
        if (cmd instanceof ClearCmd) {
          Map<String, Integer> hist = myHists.get(((ClearCmd) cmd).myHistName);
          if (hist != null) {
            hist.clear();
          }
        }
        else if (cmd instanceof HistCmd) {
          HistCmd histCmd = (HistCmd) cmd;
          Map<String, Integer> hist = myHists.computeIfAbsent(histCmd.myHistName, k -> new HashMap<>());
          hist.merge(histCmd.myBucketName, 1, Integer::sum);
        }
        else if (cmd instanceof DeleteCmd) {
          myHists.remove(((DeleteCmd) cmd).myHistName);
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

  private static class ClearCmd implements Cmd {
    private final String myHistName;

    public ClearCmd(@NotNull String histName) {
      myHistName = histName;
    }
  }

  private static class DeleteCmd implements Cmd {
    private final String myHistName;

    public DeleteCmd(@NotNull String histName) {
      myHistName = histName;
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
}
