package com.google.android.accessibility.talkback;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings for the AnTTS control bar (dark theme). Writes the same "antts_settings" keys as the
 * AnTTS app; the bar applies changes immediately. Also shows the latest error log.
 */
public class AnttsSettingsActivity extends Activity {
  private static final String PREFS = "antts_settings";
  private static final int BACKGROUND = 0xFF121212;
  private static final int TEXT = 0xFFE8EAED;
  private static final int TEXT_DIM = 0xFF9AA0A6;

  private SharedPreferences prefs;
  private TextView status;
  private TextView logView;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    AnttsCrashLog.install(this);
    prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    getWindow().setStatusBarColor(BACKGROUND);
    getWindow().setNavigationBarColor(BACKGROUND);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(24, 24, 24, 24);
    root.setBackgroundColor(BACKGROUND);

    TextView title = new TextView(this);
    title.setText("AnTTS");
    title.setTextSize(30f);
    title.setTextColor(TEXT);
    root.addView(title);

    status = new TextView(this);
    status.setTextSize(14f);
    status.setTextColor(TEXT_DIM);
    status.setPadding(0, 8, 0, 16);
    root.addView(status);

    root.addView(
        button(
            "Открыть настройки специальных возможностей",
            () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))),
        match());
    root.addView(
        button(
            "Разрешить доступ к данным об использовании",
            () -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))),
        match());

    addSeek(root, "Прозрачность чёрного фона", "background_alpha", 82, 0, 100);
    addSeek(root, "Высота панели (dp)", "bar_height_dp", 28, 16, 64);
    addSeek(root, "Учёт трафика с числа каждого месяца", "traffic_start_day", 1, 1, 31);

    CheckBox inputSpeech = new CheckBox(this);
    inputSpeech.setText("Озвучивать текст поля ввода по нажатию на панель");
    inputSpeech.setTextColor(TEXT);
    inputSpeech.setChecked(prefs.getBoolean("speak_input_after_voice", true));
    inputSpeech.setOnCheckedChangeListener(
        (buttonView, checked) ->
            prefs.edit().putBoolean("speak_input_after_voice", checked).apply());
    root.addView(inputSpeech, match());

    TextView help = new TextView(this);
    help.setText(
        "Нажатие на верхнюю полосу запускает/останавливает чтение, свайп по ней переводит фокус"
            + " TalkBack на следующий/предыдущий элемент.");
    help.setTextSize(13f);
    help.setTextColor(TEXT_DIM);
    help.setPadding(0, 16, 0, 0);
    root.addView(help);

    TextView logTitle = new TextView(this);
    logTitle.setText("Журнал ошибок");
    logTitle.setTextSize(18f);
    logTitle.setTextColor(TEXT);
    logTitle.setPadding(0, 32, 0, 8);
    root.addView(logTitle);

    LinearLayout logButtons = new LinearLayout(this);
    logButtons.setOrientation(LinearLayout.HORIZONTAL);
    logButtons.addView(button("Копировать", this::copyLog), weight());
    logButtons.addView(
        button(
            "Очистить",
            () -> {
              AnttsCrashLog.clear(this);
              refreshLog();
            }),
        weight());
    root.addView(logButtons, match());

    logView = new TextView(this);
    logView.setTextIsSelectable(true);
    logView.setTypeface(Typeface.MONOSPACE);
    logView.setTextSize(11f);
    logView.setTextColor(TEXT_DIM);
    logView.setPadding(0, 16, 0, 0);
    root.addView(logView);

    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(BACKGROUND);
    scroll.addView(root);
    setContentView(scroll);
  }

  @Override
  protected void onResume() {
    super.onResume();
    status.setText(
        TalkBackService.isServiceActive() ? "Служба TalkBack включена" : "Сначала включите TalkBack");
    refreshLog();
  }

  private void refreshLog() {
    String log = AnttsCrashLog.read(this);
    logView.setText(log.isEmpty() ? "Ошибок нет" : log);
  }

  private void copyLog() {
    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(ClipData.newPlainText("AnTTS log", AnttsCrashLog.read(this)));
    Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show();
  }

  private void addSeek(
      LinearLayout root, String label, String key, int defaultValue, int min, int max) {
    TextView text = new TextView(this);
    text.setTextSize(16f);
    text.setTextColor(TEXT);
    text.setPadding(0, 16, 0, 0);
    SeekBar seek = new SeekBar(this);
    seek.setMax(max - min);
    seek.setProgress(prefs.getInt(key, defaultValue) - min);
    text.setText(label + ": " + (seek.getProgress() + min));
    seek.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
            int value = progress + min;
            text.setText(label + ": " + value);
            if (fromUser) {
              prefs.edit().putInt(key, value).apply();
            }
          }

          @Override
          public void onStartTrackingTouch(SeekBar s) {}

          @Override
          public void onStopTrackingTouch(SeekBar s) {}
        });
    root.addView(text);
    root.addView(seek, match());
  }

  private Button button(String label, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setOnClickListener((View v) -> action.run());
    return b;
  }

  private static LinearLayout.LayoutParams match() {
    return new LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
  }

  private static LinearLayout.LayoutParams weight() {
    return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
  }
}
