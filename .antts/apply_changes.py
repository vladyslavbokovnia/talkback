#!/usr/bin/env python3
"""Idempotent AnTTS edits to existing TalkBack files. Run from the repository root."""
import os
import sys

changed = False


def edit(path, old, new, marker):
    global changed
    with open(path, encoding="utf-8") as f:
        text = f.read()
    if marker in text:
        print("already applied:", path, "->", marker)
        return
    if text.count(old) != 1:
        print("ANCHOR NOT FOUND (%d matches) in %s:\n%s" % (text.count(old), path, old))
        sys.exit(1)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text.replace(old, new))
    changed = True
    print("applied:", path, "->", marker)


SRC = "talkback/src/main/java/com/google/android/accessibility/talkback/"

# Earliest hook: save crashes of the whole app, so they can be read in AnTTS settings.
edit(
    SRC + "TalkBackApplication.java",
    "    super.onCreate();\n\n    FormFactorUtils.initialize(this);",
    "    super.onCreate();\n    AnttsCrashLog.install(this);\n\n    FormFactorUtils.initialize(this);",
    "AnttsCrashLog.install",
)

# AnTTS code must never take TalkBack down: guard the two hooks.
edit(
    SRC + "TalkBackService.java",
    "    if (anttsOverlay != null) {\n      anttsOverlay.onAccessibilityEvent(event);\n    }\n",
    "    if (anttsOverlay != null) {\n      try {\n        anttsOverlay.onAccessibilityEvent(event);\n"
    "      } catch (RuntimeException e) {\n        AnttsCrashLog.record(this, \"overlay event\", e);\n      }\n    }\n",
    "\"overlay event\"",
)
edit(
    SRC + "TalkBackService.java",
    "    if (anttsOverlay == null) {\n      anttsOverlay = new AnttsOverlay(this);\n    }\n    anttsOverlay.show();\n",
    "    try {\n      if (anttsOverlay == null) {\n        anttsOverlay = new AnttsOverlay(this);\n      }\n      anttsOverlay.show();\n"
    "    } catch (RuntimeException e) {\n      AnttsCrashLog.record(this, \"overlay show\", e);\n    }\n",
    "\"overlay show\"",
)

# Launcher icon (speaker, transparent background) and dark theme for the settings screen.
edit(
    "talkback/src/main/AndroidManifest.xml",
    '            android:label="AnTTS"\n'
    '            android:theme="@android:style/Theme.DeviceDefault.Light.NoActionBar">',
    '            android:icon="@mipmap/antts_ic_launcher"\n'
    '            android:label="AnTTS"\n'
    '            android:theme="@android:style/Theme.DeviceDefault.NoActionBar">',
    "antts_ic_launcher",
)

if changed and os.environ.get("GITHUB_ENV"):
    with open(os.environ["GITHUB_ENV"], "a") as f:
        f.write("TWEAKED=1\n")
