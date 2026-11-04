"""Prints how many instrumented GPU tests ran, so a skipped run cannot pass for a real one."""
import glob
import re

for path in glob.glob("app/build/outputs/androidTest-results/connected/**/*.xml", recursive=True):
    text = open(path).read()
    suite = re.search(r"<testsuite [^>]*>", text)
    if suite:
        print("GPU TESTS", re.sub(r'(hostname|timestamp|name)="[^"]*"', "", suite.group(0)))
    print("GPU TESTS skipped cases:", text.count("<skipped"))
