package com.google.android.accessibility.talkback;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Settings for the AnTTS control bar. Writes the same "antts_settings" keys as the AnTTS app; the
 * bar applies changes immediately.
 */
public class AnttsSettingsActivity extends Activity {
  private static final String PREFS = "antts_settings";

  private SharedPreferences prefs;
  private TextView status;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(24, 24, 24, 24);
    root.setBackgroundColor(Color.WHITE);

    TextView title = new TextView(this);
    title.setText("AnTTS");
    title.setTextSize(30f);
    title.setTextColor(Color.BLACK);
    root.addView(title);

    status = new TextView(this);
    status.setTextSize(14f);
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

    addSeek(root, "Прозрачность чёрного фона", "background_alpha", 82, 0, 100, null);
    addSeek(root, "Высота панели (dp)", "bar_height_dp", 28, 16, 64, null);
    addSeek(
        root,
        "Учёт трафика с числа каждого месяца",
        "traffic_start_day",
        1,
        1,
        31,
        null);

    CheckBox inputSpeech = new CheckBox(this);
    inputSpeech.setText("Озвучивать текст поля ввода по нажатию на панель");
    inputSpeech.setChecked(prefs.getBoolean("speak_input_after_voice", true));
    inputSpeech.setOnCheckedChangeListener(
        (buttonView, checked) -> prefs.edit().putBoolean("speak_input_after_voice", checked).apply());
    root.addView(inputSpeech, match());

    TextView help = new TextView(this);
    help.setText(
        "Нажатие на верхнюю полосу запускает/останавливает чтение, свайп по ней переводит фокус"
            + " TalkBack на следующий/предыдущий элемент.");
    help.setTextSize(13f);
    help.setTextColor(Color.DKGRAY);
    help.setPadding(0, 16, 0, 0);
    root.addView(help);

    ScrollView scroll = new ScrollView(this);
    scroll.addView(root);
    setContentView(scroll);
  }

  @Override
  protected void onResume() {
    super.onResume();
    status.setText(
        TalkBackService.isServiceActive() ? "Служба TalkBack включена" : "Сначала включите TalkBack");
  }

  private void addSeek(
      LinearLayout root,
      String label,
      String key,
      int defaultValue,
      int min,
      int max,
      Runnable onChange) {
    TextView text = new TextView(this);
    text.setTextSize(16f);
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
              if (onChange != null) {
                onChange.run();
              }
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
}
