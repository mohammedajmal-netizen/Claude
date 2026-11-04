"""Prints one thread's stack from a Java stack dump, to show where it is stuck."""
import sys

text = open(sys.argv[1], errors="replace").read()
for block in text.split("\n\n"):
    if f'"{sys.argv[2]}"' in block:
        print("\n".join(block.splitlines()[:28]))
        break
else:
    print("thread not found, dump was", len(text), "bytes")
