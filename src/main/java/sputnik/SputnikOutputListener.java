package sputnik;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class SputnikOutputListener implements ExecutionListener {
  @Override
  public void processStarting(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler) {
    Sputnik s = env.getProject().getService(Sputnik.class);
    s.start();
    handler.addProcessListener(new ProcListener(s));
  }

  private static class ProcListener extends ProcessAdapter {
    private final Sputnik mySputnik;

    public ProcListener(@NotNull Sputnik s) {
      mySputnik = s;
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
            mySputnik.h(histName, bucketName);
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
            mySputnik.hr(histName);
          }
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
