import xml.etree.ElementTree as ET

tree = ET.parse(r"D:\SoftwareArchitektur\javi\lichess\target\scala-3.3.4\coverage-report\cobertura.xml")
root = tree.getroot()

for pkg in root.findall(".//package"):
    for cls in pkg.findall(".//class"):
        misses = []
        for line in cls.findall(".//line"):
            if line.get("hits") == "0":
                misses.append(line.get("number"))
        if misses:
            print(f"Class {cls.get('name')} misses lines: {', '.join(misses)}")
