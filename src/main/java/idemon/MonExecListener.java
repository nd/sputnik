package idemon;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class MonExecListener implements ExecutionListener {
  @Override
  public void processStarting(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler) {
    Mon mon = env.getProject().getService(Mon.class);
    mon.start();
    handler.addProcessListener(new ProcListener(mon));
  }

  private static class ProcListener extends ProcessAdapter {
    private final Mon myMon;

    public ProcListener(@NotNull Mon mon) {
      myMon = mon;
    }

    @Override
    public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
      String text = StringUtil.trimTrailing(event.getText());
      // command starts with \u0001 and is at least 4 chars long: \u0001c()
      if (text.startsWith("\u0001") && text.length() >= 4 && text.charAt(text.length() - 1) == ')') {
        char c1 = text.charAt(1);
        if (c1 == 'h') {
          //\u0001h("histName","bucketName") - add 1 to the bucket in given histogram
          //\u0001hr("histName") - reset the given histogram
          if (text.charAt(2) == '(') {
            int idx = 3;
            String histName = parseString(text, idx);
            if (histName == null) {
              return;
            }
            idx += histName.length() + 2;
            if (text.charAt(idx) != ',') {
              return;
            }
            idx++;
            String bucketName = parseString(text, idx);
            if (bucketName == null) {
              return;
            }
            idx += bucketName.length() + 2;
            if (idx != text.length() - 1) {
              return;
            }
            myMon.h(histName, bucketName);
          } else if (text.charAt(2) == 'r' && text.charAt(3) == '(') {
            int idx = 4;
            String histName = parseString(text, idx);
            if (histName == null) {
              return;
            }
            idx += histName.length() + 2;
            if (idx != text.length() - 1) {
              return;
            }
            myMon.hr(histName);
          }
        }
      }
      else if (text.startsWith("#!c{") && text.endsWith("}")) {
        //#!c{id="xx",cmd=clear}
        //#!c{id="xx",cmd=total}
        //#!c{id="xx",cmd=hit}
        text = StringUtil.trimEnd(StringUtil.trimStart(text, "#!c{"), "}");

        List<String> entries = StringUtil.split(text, ",");
        String id = null;
        String cmd = null;
        for (String entry : entries) {
          List<String> parts = StringUtil.split(entry, "=");
          if (parts.size() == 2) {
            String k = parts.get(0);
            String v = parts.get(1);
            if ("id".equals(k)) {
              id = StringUtil.trimEnd(StringUtil.trimStart(v, "\""), "\"");
            }
            else if ("cmd".equals(k)) {
              cmd = StringUtil.trim(v);
            }
          }
        }
        if (id != null && cmd != null) {
          myMon.compute(id, cmd);
        }
      }
    }
  }

  @Nullable
  private static String parseString(@NotNull String text, int startOffset) {
    char quote = text.charAt(startOffset);
    if (quote != '"' && quote != '\'') {
      return null;
    }
    int i = startOffset + 1;
    while (i < text.length() && text.charAt(i) != quote) {
      i++;
    }
    if (i < text.length()) {
      return text.substring(startOffset + 1, i);
    } else {
      return null;
    }
  }
}
