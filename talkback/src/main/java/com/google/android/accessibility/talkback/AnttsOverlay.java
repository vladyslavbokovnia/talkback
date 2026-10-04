package com.google.android.accessibility.talkback;

import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.Calendar;
import java.util.Locale;

/**
 * AnTTS control bar drawn over the top of the screen.
 *
 * <p>Tap: start / stop continuous reading. Horizontal swipe: move TalkBack focus to the next /
 * previous item. Shows monthly mobile traffic (GB). The rest of the screen keeps the standard
 * Android touch behavior because explore-by-touch is off by default in this build.
 *
 * <p>Uses the same "antts_settings" preferences and keys as the AnTTS app, so its settings screen
 * can configure this bar.
 */
public class AnttsOverlay {
  private static final String PREFS = "antts_settings";
  private static final float SWIPE_THRESHOLD_PX = 28f;
  private static final long TRAFFIC_REFRESH_MS = 2_000L;

  private final TalkBackService service;
  private final WindowManager windowManager;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final SharedPreferences prefs;
  private final AnttsInputReader inputReader;
  private FrameLayout root;
  private TextView traffic;
  private boolean shown;
  private float downX;

  private final Runnable trafficRefresh =
      new Runnable() {
        @Override
        public void run() {
          if (!shown) {
            return;
          }
          traffic.setText(monthlyTrafficText());
          handler.postDelayed(this, TRAFFIC_REFRESH_MS);
        }
      };

  // Strong reference: SharedPreferences only holds listeners weakly.
  private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
      (sharedPrefs, key) -> {
        if (shown) {
          hide();
          show();
        }
      };

  public AnttsOverlay(TalkBackService service) {
    this.service = service;
    this.windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    this.prefs = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    this.inputReader = new AnttsInputReader(service);
    this.prefs.registerOnSharedPreferenceChangeListener(prefsListener);
  }

  public void show() {
    if (shown) {
      return;
    }
    root = new FrameLayout(service);
    // Keep TalkBack's own focus navigation from landing on this bar.
    root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

    View background = new View(service);
    int backgroundAlpha = prefs.getInt("background_alpha", 82);
    background.setBackgroundColor(Color.argb((int) (backgroundAlpha * 2.55), 0, 0, 0));
    root.addView(background, new FrameLayout.LayoutParams(-1, -1));

    traffic = new TextView(service);
    traffic.setText(monthlyTrafficText());
    traffic.setTextSize(27f);
    traffic.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
    traffic.setIncludeFontPadding(false);
    traffic.setGravity(Gravity.CENTER);
    traffic.setTextColor(Color.WHITE);
    root.addView(traffic, new FrameLayout.LayoutParams(-1, -1));

    root.setOnTouchListener(
        (v, event) -> {
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              downX = event.getRawX();
              return true;
            case MotionEvent.ACTION_UP:
              float dx = event.getRawX() - downX;
              if (Math.abs(dx) >= SWIPE_THRESHOLD_PX) {
                int delta = dx > 0 ? 1 : -1;
                // Inside an input field a swipe steps through its sentences.
                if (inputSpeechEnabled()
                    && !service.isContinuousReadingActive()
                    && inputReader.moveSentence(delta)) {
                  return true;
                }
                service.performShortcutAction(
                    delta > 0 ? R.string.shortcut_value_next : R.string.shortcut_value_previous);
              } else {
                toggleReading();
              }
              return true;
            default:
              return true;
          }
        });

    int heightPx = dp(prefs.getInt("bar_height_dp", 28));
    WindowManager.LayoutParams params =
        new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
    params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
    windowManager.addView(root, params);
    shown = true;
    handler.post(trafficRefresh);
  }

  public void hide() {
    if (!shown) {
      return;
    }
    shown = false;
    handler.removeCallbacks(trafficRefresh);
    try {
      windowManager.removeView(root);
    } catch (RuntimeException e) {
      // View was already detached.
    }
  }

  /** Forwards accessibility events so the input reader can track the active field. */
  public void onAccessibilityEvent(AccessibilityEvent event) {
    inputReader.onAccessibilityEvent(event);
  }

  private boolean inputSpeechEnabled() {
    return prefs.getBoolean("speak_input_after_voice", true);
  }

  private void toggleReading() {
    if (service.isContinuousReadingActive() || inputReader.isSpeaking()) {
      inputReader.stop();
      service.interruptAllFeedback(/* stopTtsSpeechCompletely= */ false);
    } else if (inputSpeechEnabled() && inputReader.speakAtCursor()) {
      // Spoke the sentence of the focused input field.
    } else {
      // READ_FROM_CURRENT silently does nothing when no node has accessibility focus (typical
      // right after a screen change, since explore-by-touch is off), so fall back to the top.
      service.performShortcutAction(
          service.hasAccessibilityFocus()
              ? R.string.shortcut_value_read_from_current
              : R.string.shortcut_value_read_from_top);
    }
  }

  private int dp(int value) {
    return (int) (value * service.getResources().getDisplayMetrics().density);
  }

  // ---- Traffic accounting (ported from AnTTS TrafficMonitor / AppSettings) ----

  private String monthlyTrafficText() {
    long end = System.currentTimeMillis();
    try {
      NetworkStatsManager manager =
          (NetworkStatsManager) service.getSystemService(Context.NETWORK_STATS_SERVICE);
      NetworkStats.Bucket bucket =
          manager.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, periodStartMillis(), end);
      long bytes = Math.max(0L, bucket.getRxBytes()) + Math.max(0L, bucket.getTxBytes());
      return String.format(Locale.getDefault(), "%.2f", bytes / 1_000_000_000.0);
    } catch (Exception e) {
      return "\u2014";
    }
  }

  private long periodStartMillis() {
    long now = System.currentTimeMillis();
    int startDay = Math.max(1, Math.min(31, prefs.getInt("traffic_start_day", 1)));
    Calendar cycle = Calendar.getInstance();
    cycle.setTimeInMillis(now);
    cycle.set(
        Calendar.DAY_OF_MONTH, Math.min(startDay, cycle.getActualMaximum(Calendar.DAY_OF_MONTH)));
    cycle.set(Calendar.HOUR_OF_DAY, 0);
    cycle.set(Calendar.MINUTE, 0);
    cycle.set(Calendar.SECOND, 0);
    cycle.set(Calendar.MILLISECOND, 0);
    if (now < cycle.getTimeInMillis()) {
      cycle.add(Calendar.MONTH, -1);
    }
    return cycle.getTimeInMillis();
  }
}
