"""Prints the centre of the control with the given text or description in ui.xml."""
import re
import sys

xml = open("ui.xml").read()
for node in re.findall(r"<node [^>]*>", xml):
    if f'text="{sys.argv[1]}"' in node or f'content-desc="{sys.argv[1]}"' in node:
        a = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups()
        print((int(a[0]) + int(a[2])) // 2, (int(a[1]) + int(a[3])) // 2)
        break
