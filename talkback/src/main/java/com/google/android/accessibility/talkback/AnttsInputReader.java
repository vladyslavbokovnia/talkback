package com.google.android.accessibility.talkback;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * Speaks the sentence of the focused input field where the cursor is, and steps through the
 * field's sentences. Ported from AnTTS (AnTTSAccessibilityService input handling).
 */
public class AnttsInputReader {
  private final TalkBackService service;
  private final Handler handler = new Handler(Looper.getMainLooper());

  private AccessibilityNodeInfo pendingInputNode;
  private AccessibilityNodeInfo lastSpokenNode;
  private int lastRecordedCursor = -1;
  private int inputSentenceIndex;
  private boolean speaking;
  private long speakToken;

  public AnttsInputReader(TalkBackService service) {
    this.service = service;
  }

  public boolean isSpeaking() {
    return speaking;
  }

  /** Stops tracking the current utterance; the caller interrupts TalkBack's speech itself. */
  public void stop() {
    speaking = false;
    speakToken++;
  }

  /** Tracks which editable field is active and where its cursor is. */
  public void onAccessibilityEvent(AccessibilityEvent event) {
    switch (event.getEventType()) {
      case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
        {
          AccessibilityNodeInfo source = event.getSource();
          if (source != null && isEditable(source)) {
            pendingInputNode = source;
            if (event.getFromIndex() >= 0) {
              lastRecordedCursor = event.getFromIndex() + event.getAddedCount();
            }
          }
          break;
        }
      case AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED:
        {
          AccessibilityNodeInfo source = event.getSource();
          if (source != null && isEditable(source)) {
            pendingInputNode = source;
            if (event.getFromIndex() >= 0) {
              lastRecordedCursor = event.getFromIndex();
            }
          }
          break;
        }
      case AccessibilityEvent.TYPE_VIEW_FOCUSED:
        {
          AccessibilityNodeInfo source = event.getSource();
          if (source != null && isEditable(source)) {
            pendingInputNode = source;
            if (source.getTextSelectionStart() >= 0) {
              lastRecordedCursor = source.getTextSelectionStart();
            }
          }
          break;
        }
      case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
        pendingInputNode = null;
        lastSpokenNode = null;
        lastRecordedCursor = -1;
        break;
      default:
        break;
    }
  }

  /**
   * Speaks the sentence at the cursor of the active input field.
   *
   * @return true if there was an input field with text to speak; false so the caller can fall back
   *     to normal page reading.
   */
  public boolean speakAtCursor() {
    AccessibilityNodeInfo node = findActiveInputNode();
    if (node == null) {
      return false;
    }
    String text = textOf(node);
    List<SentenceSpan> sentences = parseSentences(text);
    if (sentences.isEmpty()) {
      return false;
    }
    int cursor;
    if (node.getTextSelectionStart() >= 0) {
      cursor = node.getTextSelectionStart();
    } else if (lastRecordedCursor >= 0 && lastRecordedCursor <= text.length()) {
      cursor = lastRecordedCursor;
    } else {
      cursor = text.length();
    }
    inputSentenceIndex = sentenceIndexAt(sentences, cursor);
    lastSpokenNode = node;
    speakSentence(sentences, inputSentenceIndex);
    return true;
  }

  /**
   * Speaks the previous/next sentence of the active input field.
   *
   * @return true if handled; false if there is no input field with text.
   */
  public boolean moveSentence(int delta) {
    AccessibilityNodeInfo node = findActiveInputNode();
    if (node == null) {
      return false;
    }
    List<SentenceSpan> sentences = parseSentences(textOf(node));
    if (sentences.isEmpty()) {
      return false;
    }
    if (lastSpokenNode == null || !lastSpokenNode.equals(node)) {
      // First time in this field: start from the cursor instead of a stale index.
      return speakAtCursor();
    }
    speakSentence(sentences, inputSentenceIndex + delta);
    return true;
  }

  private void speakSentence(List<SentenceSpan> sentences, int index) {
    inputSentenceIndex = Math.max(0, Math.min(index, sentences.size() - 1));
    String spoken = sentences.get(inputSentenceIndex).text;
    if (spoken.trim().isEmpty()) {
      return;
    }
    speaking = true;
    final long token = ++speakToken;
    service.speakInterrupting(
        spoken,
        status ->
            handler.post(
                () -> {
                  if (token == speakToken) {
                    speaking = false;
                  }
                }));
  }

  // Never read passwords aloud.
  private String textOf(AccessibilityNodeInfo node) {
    node.refresh();
    if (node.isPassword()) {
      return "";
    }
    CharSequence text = node.getText();
    return text == null ? "" : text.toString();
  }

  private static boolean isEditable(AccessibilityNodeInfo node) {
    if (node.isEditable()) {
      return true;
    }
    CharSequence className = node.getClassName();
    if (className == null) {
      return false;
    }
    String name = className.toString().toLowerCase();
    return name.contains("edittext") || name.contains("autocompletetextview");
  }

  private AccessibilityNodeInfo findActiveInputNode() {
    AccessibilityNodeInfo root = service.getRootInActiveWindow();
    if (root != null) {
      AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
      if (focused != null && isEditable(focused)) {
        return focused;
      }
    }
    if (pendingInputNode != null && pendingInputNode.refresh() && isEditable(pendingInputNode)) {
      return pendingInputNode;
    }
    try {
      List<AccessibilityWindowInfo> windows = service.getWindows();
      for (AccessibilityWindowInfo window : windows) {
        AccessibilityNodeInfo windowRoot = window.getRoot();
        if (windowRoot == null) {
          continue;
        }
        AccessibilityNodeInfo focused = windowRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null && isEditable(focused)) {
          return focused;
        }
      }
    } catch (RuntimeException e) {
      // Windows can disappear while iterating.
    }
    return null;
  }

  // ---- Sentence parsing (ported from AnTTS InputSentenceParser) ----

  static final class SentenceSpan {
    final int contentStart;
    final int contentEnd;
    final int spanEnd;
    final String text;

    SentenceSpan(int contentStart, int contentEnd, int spanEnd, String text) {
      this.contentStart = contentStart;
      this.contentEnd = contentEnd;
      this.spanEnd = spanEnd;
      this.text = text;
    }
  }

  private static boolean isTerminator(char ch) {
    return ch == '.' || ch == '!' || ch == '?' || ch == '\u2026';
  }

  static List<SentenceSpan> parseSentences(String text) {
    List<SentenceSpan> spans = new ArrayList<>();
    if (text.trim().isEmpty()) {
      return spans;
    }
    int len = text.length();
    int start = 0;
    while (start < len) {
      while (start < len && Character.isWhitespace(text.charAt(start))) {
        start++;
      }
      if (start >= len) {
        break;
      }
      int end = start;
      while (end < len) {
        char ch = text.charAt(end);
        if (ch == '\n' || ch == '\r') {
          end++;
          break;
        }
        if (isTerminator(ch)) {
          // Decimal separator such as 3.14 is not a sentence end.
          if (ch == '.'
              && end > start
              && Character.isDigit(text.charAt(end - 1))
              && end + 1 < len
              && Character.isDigit(text.charAt(end + 1))) {
            end++;
            continue;
          }
          while (end < len && isTerminator(text.charAt(end))) {
            end++;
          }
          while (end < len && "\"'\u00bb\u201d\u2019)]".indexOf(text.charAt(end)) >= 0) {
            end++;
          }
          break;
        }
        end++;
      }
      int spanEnd = end;
      while (spanEnd < len && Character.isWhitespace(text.charAt(spanEnd))) {
        spanEnd++;
      }
      String sentence = text.substring(start, end).trim();
      if (!sentence.isEmpty()) {
        spans.add(new SentenceSpan(start, end, spanEnd, sentence));
      }
      start = spanEnd;
    }
    return spans;
  }

  static int sentenceIndexAt(List<SentenceSpan> sentences, int cursor) {
    if (sentences.isEmpty()) {
      return 0;
    }
    if (cursor <= sentences.get(0).contentStart) {
      return 0;
    }
    for (int i = 0; i < sentences.size(); i++) {
      SentenceSpan span = sentences.get(i);
      if (cursor >= span.contentStart && cursor <= span.spanEnd) {
        return i;
      }
    }
    return sentences.size() - 1;
  }
}
