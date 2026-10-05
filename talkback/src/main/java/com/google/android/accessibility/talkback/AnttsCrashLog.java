package com.google.android.accessibility.talkback;

import android.content.Context;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps the latest crashes / handled errors in a private file so they can be read in the AnTTS
 * settings screen without adb. Newest entry first.
 */
public final class AnttsCrashLog {
  private static final String FILE_NAME = "antts-crash-log.txt";
  private static final int MAX_CHARS = 20_000;
  private static boolean installed;

  private AnttsCrashLog() {}

  /** Saves uncaught exceptions to the log, then lets the system handle the crash as usual. */
  public static synchronized void install(Context context) {
    if (installed) {
      return;
    }
    installed = true;
    final Context appContext =
        context.getApplicationContext() != null ? context.getApplicationContext() : context;
    final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, throwable) -> {
          record(appContext, "CRASH, thread " + thread.getName(), throwable);
          if (previous != null) {
            previous.uncaughtException(thread, throwable);
          }
        });
  }

  /** Adds an entry to the log. Never throws. */
  public static synchronized void record(Context context, String where, Throwable throwable) {
    try {
      StringWriter trace = new StringWriter();
      throwable.printStackTrace(new PrintWriter(trace));
      String entry =
          new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
              + " | "
              + where
              + "\n"
              + Build.MANUFACTURER
              + " "
              + Build.MODEL
              + ", Android "
              + Build.VERSION.RELEASE
              + " (SDK "
              + Build.VERSION.SDK_INT
              + ")\n"
              + trace
              + "\n";
      String all = entry + read(context);
      if (all.length() > MAX_CHARS) {
        all = all.substring(0, MAX_CHARS);
      }
      try (FileOutputStream out = context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE)) {
        out.write(all.getBytes(StandardCharsets.UTF_8));
      }
    } catch (Throwable ignored) {
      // Logging must never crash the app.
    }
  }

  /** Returns the whole log, or an empty string. */
  public static synchronized String read(Context context) {
    try (FileInputStream in = context.openFileInput(FILE_NAME)) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int count;
      while ((count = in.read(buffer)) > 0) {
        bytes.write(buffer, 0, count);
      }
      return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    } catch (Throwable e) {
      return "";
    }
  }

  public static synchronized void clear(Context context) {
    try {
      context.deleteFile(FILE_NAME);
    } catch (Throwable ignored) {
      // Nothing to do.
    }
  }
}
