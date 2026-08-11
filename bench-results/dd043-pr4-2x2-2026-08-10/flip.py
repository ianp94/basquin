#!/usr/bin/env python3
"""Extract per-method INSTRUCTION covered/missed counters from a jacoco-cli XML report,
for a named class + method list, across multiple dump-point XML files. Used to show the
route-method coverage flip (Task 4 checklist step 5) without trusting a byte diff.

Usage: flip.py <class-name-substring> <method1[,method2,...]> <file1.xml> [file2.xml ...]
"""
import sys
import xml.etree.ElementTree as ET

def main():
    class_sub = sys.argv[1]
    methods = sys.argv[2].split(",")
    files = sys.argv[3:]
    for f in files:
        tree = ET.parse(f)
        root = tree.getroot()
        found_class = None
        for cls in root.iter("class"):
            if class_sub in cls.get("name", ""):
                found_class = cls
                break
        print(f"--- {f} ---")
        if found_class is None:
            print(f"  class matching {class_sub!r} NOT FOUND")
            continue
        for m in found_class.iter("method"):
            name = m.get("name")
            if name in methods:
                instr = None
                for c in m.iter("counter"):
                    if c.get("type") == "INSTRUCTION":
                        instr = c
                if instr is not None:
                    print(f"  {found_class.get('name')}.{name}: covered={instr.get('covered')} missed={instr.get('missed')}")
                else:
                    print(f"  {found_class.get('name')}.{name}: NO INSTRUCTION COUNTER (fully missed, 0 lines emitted)")

if __name__ == "__main__":
    main()
