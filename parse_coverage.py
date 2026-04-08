import xml.etree.ElementTree as ET
import glob

files = glob.glob('target/scala-*/scoverage-report/scoverage.xml')
if not files:
    print('No coverage report found')
    exit(1)

tree = ET.parse(files[0])
root = tree.getroot()
print(f'Total Statement Coverage: {root.attrib.get("statement-rate", "?")}%')

for pkg in root.findall('.//package'):
    for cls in pkg.findall('.//class'):
        cls_name = cls.attrib.get('name', 'Unknown')
        cls_rate = float(cls.attrib.get('statement-rate', '0'))
        if cls_rate < 100.0:
            print(f'{cls_name}: {cls_rate}% ({cls.attrib.get("statement-count")} statements, {cls.attrib.get("statements-invoked")} invoked)')
