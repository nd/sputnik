package idemon;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Service
public final class Mon implements Disposable {

  private final AtomicBoolean myStarted = new AtomicBoolean();
  private final AtomicBoolean myStop = new AtomicBoolean();
  private final ArrayBlockingQueue<Cmd> myCmds = new ArrayBlockingQueue<>(16 * 1024);
  private final Hist myHist = new Hist();
  private final Map<String, Cache> myCaches = new ConcurrentHashMap<>();
  private final Map<String, CacheUi> myCacheUis = new ConcurrentHashMap<>();
  private final AtomicReference<HistUi> myHistUi = new AtomicReference<>();

  private final Lock myUpdatedLock = new ReentrantLock();
  private final Condition myUpdated  = myUpdatedLock.newCondition();
  private final AtomicInteger myUpdateCounter = new AtomicInteger();

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

  void compute(String id, String cmd) {
    //myCmds.offer(new ComputeCmd(id,cmd));
  }

  @Override
  public void dispose() {
    myStop.set(true);
  }

  @Nullable HistUi getHist() {
    return myHistUi.get();
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
    for (Cmd cmd : cmds) {
      if (cmd instanceof ClearCmd) {
        myHist.myMap.clear();
      }
      if (cmd instanceof HistCmd) {
        HistCmd histCmd = (HistCmd) cmd;
        myHist.myMap.merge(histCmd.myBucketName, 1, Integer::sum);
      }
      if (cmd instanceof ComputeCmd) {
        String id = ((ComputeCmd) cmd).myId;
        String cacheCmd = ((ComputeCmd) cmd).myCmd;
        if ("clear".equals(cacheCmd)) {
          Cache cache = myCaches.get(id);
          if (cache != null) {
            cache.myTotalCount = 0;
            cache.myHitCount = 0;
          }
        }
        else if ("total".equals(cacheCmd)) {
          Cache cache = myCaches.get(id);
          if (cache == null) {
            cache = new Cache();
            myCaches.put(id, cache);
          }
          cache.myTotalCount++;
        }
        else if ("hit".equals(cacheCmd)) {
          Cache cache = myCaches.get(id);
          if (cache == null) {
            cache = new Cache();
            myCaches.put(id, cache);
          }
          cache.myHitCount++;
        }
      }
    }

    List<String> vals = new ArrayList<>(myHist.myMap.keySet());
    Collections.sort(vals, (o1, o2) -> {
      int i1 = myHist.myMap.get(o1);
      int i2 = myHist.myMap.get(o2);
      return -Integer.compare(i1, i2);
    });
    int[] counts = new int[vals.size()];
    int total = 0;
    for (int i = 0; i < counts.length; i++) {
      int itemCount = myHist.myMap.get(vals.get(i));
      counts[i] = itemCount;
      total += itemCount;
    }
    //if (myHistUi.get() == null)
      myHistUi.set(new HistUi(vals, counts, total));

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
    private final List<String> myVals;
    private final int[] myCounts;
    private final int myTotal;

    public HistUi(List<String> vals, int[] counts, int total) {
      myVals = vals;
      myCounts = counts;
      myTotal = total;
    }

    int getSize() {
      return myVals.size();
    }

    String getVal(int i) {
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
  private static class HistCmd implements Cmd {
    private final String myHistName;
    private final String myBucketName;

    public HistCmd(@NotNull String histName, @NotNull String bucketName) {
      myHistName = histName;
      myBucketName = bucketName;
    }
  }

  private static class ComputeCmd implements Cmd {
    private final String myId;
    private final String myCmd;

    public ComputeCmd(String id, String cmd) {
      this.myId = id;
      this.myCmd = cmd;
    }
  }

  private static class Cache {
    private long myTotalCount;
    private long myHitCount;
  }

  static class CacheUi {
    private String myId = "nothing";
    private long myPrevTotal;
    private long myPrevHit;
    long myTotal[] = new long[128];
    long myHit[] = new long[128];
    int myIdx;

    void add(long total, long hit) {
      long totalDiff = total - myPrevTotal;
      long hitDiff = hit - myPrevHit;
      myTotal[myIdx] = totalDiff;
      myHit[myIdx] = hitDiff;
      myIdx = (myIdx + 1) % 128;
      myPrevTotal = total;
      myPrevHit = hit;
    }

    void drainTo(long[] total, long[]hit) {
      int idx = (myIdx + 1) % 128;
      for (int i = 0; i < 128; i++) {
        total[i] = myTotal[idx];
        hit[i] = myHit[idx];
        idx = (idx + 1) % 128;
      }
    }

    public String getId() {
      return myId;
    }
  }

  void sampleCaches() {
    for (String cacheId : myCaches.keySet()) {
      Cache cache = myCaches.get(cacheId);
      CacheUi cacheUi = myCacheUis.get(cacheId);
      if (cacheUi == null){
        cacheUi = new CacheUi();
        cacheUi.myId = cacheId;
        myCacheUis.put(cacheId, cacheUi);
      }
      cacheUi.add(cache.myTotalCount, cache.myHitCount);
    }
  }

  CacheUi getCacheUi() {
    Set<String> keys = myCacheUis.keySet();
    if (!keys.isEmpty()) {
      return myCacheUis.get(keys.iterator().next());
    }
    return new CacheUi();
  }
}
