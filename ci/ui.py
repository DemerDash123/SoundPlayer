#!/usr/bin/env python3
"""Tiny UI driver for CI: find a view by resource-id or text via `uiautomator dump`, tap it, or check it exists.

usage: ui.py tap id:<resource-id> | text:<text>
       ui.py exists id:<...> | text:<...>
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.demerdash.soundplayer"


def dump():
    for _ in range(3):
        subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
        out = subprocess.run(["adb", "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True).stdout
        try:
            return ET.fromstring(out)
        except ET.ParseError:
            time.sleep(1)
    raise SystemExit("could not dump UI")


def find(root, query):
    kind, _, value = query.partition(":")
    for node in root.iter("node"):
        if kind == "id" and node.get("resource-id") in (value, f"{PKG}:id/{value}"):
            return node
        if kind == "text" and (node.get("text") == value or node.get("content-desc") == value):
            return node
        if kind == "contains" and value.lower() in (node.get("text") or "").lower():
            return node
    return None


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def main():
    action, query = sys.argv[1], sys.argv[2]
    timeout = float(sys.argv[3]) if len(sys.argv) > 3 else 8
    end = time.time() + timeout
    node = None
    while time.time() < end:
        node = find(dump(), query)
        if node is not None:
            break
        time.sleep(1)
    if node is None:
        print(f"NOT FOUND: {query}")
        sys.exit(1)
    if action == "tap":
        x, y = center(node)
        subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)], check=True)
        print(f"tapped {query} at {x},{y}")
    else:
        print(f"found {query}: text={node.get('text')!r}")


if __name__ == "__main__":
    main()
