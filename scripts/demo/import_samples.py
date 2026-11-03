#!/usr/bin/env python3
"""
Synthetic student-import sample files for the Meridian demonstration organization (increment 1.6).

  python3 scripts/demo/import_samples.py            writes docs/samples/student-import/*
The same definitions are used by demo_data.py (with dates relative to the day the demonstration is seeded).
Synthetic names and contact details only; no real person is represented.
"""
import csv
import datetime
import io
import os
import zipfile
from xml.sax.saxutils import escape

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "docs", "samples", "student-import")

HEADER = ["Student ID", "Registration number", "First name", "Last name", "Date of birth", "Gender", "Personal email", "Mobile", "Admission number",
          "Admission date", "Admission type", "Batch", "Semester", "Student status", "Guardian 1 name", "Guardian 1 relationship", "Guardian 1 mobile"]

# Meridian Institute of Engineering generates student IDs (the column stays empty for new students); registration numbers follow AAA/9999/*.
MIE_INTAKE = [  # first, last, gender, batch, status, guardian (name, relationship)
    ("Aanya", "Bhosale", "Female", "CSE-2026-A", "Active", ("Sunita Bhosale", "Mother")),
    ("Advik", "Chopra", "Male", "CSE-2026-A", "Active", ("Rakesh Chopra", "Father")),
    ("Anika", "Dutta", "Female", "CSE-2026-A", "Active", ("Moumita Dutta", "Mother")),
    ("Atharv", "Ghosh", "Male", "CSE-2026-A", "Enrolled", ("Partha Ghosh", "Father")),
    ("Bhavya", "Hegde", "Female", "CSE-2026-A", "Active", ("Shobha Hegde", "Mother")),
    ("Darsh", "Jain", "Male", "CSE-2026-A", "Active", ("Manoj Jain", "Father")),
    ("Eshaan", "Kamath", "Male", "CSE-2026-B", "Active", ("Vidya Kamath", "Mother")),
    ("Gauri", "Lal", "Female", "CSE-2026-B", "Active", ("Harish Lal", "Father")),
    ("Hridaan", "Mehta", "Male", "CSE-2026-B", "Enrolled", ("Pooja Mehta", "Mother")),
    ("Ira", "Naidu", "Female", "CSE-2026-B", "Active", ("Srinivas Naidu", "Father")),
    ("Jivika", "Oberoi", "Female", "CSE-2026-B", "Active", ("Kavita Oberoi", "Mother")),
    ("Krish", "Pandey", "Male", "CSE-2026-B", "Active", ("Alok Pandey", "Father")),
    ("Lavanya", "Qadri", "Female", "ECE-2026", "Active", ("Nasreen Qadri", "Mother")),
    ("Madhav", "Raghavan", "Male", "ECE-2026", "Active", ("Sridhar Raghavan", "Father")),
    ("Naira", "Saxena", "Female", "ECE-2026", "Enrolled", ("Ritu Saxena", "Mother")),
    ("Parth", "Thakur", "Male", "ECE-2026", "Active", ("Vikram Thakur", "Father")),
]

# Rows with deliberate problems: the validation report explains each one.
MIE_ISSUES = [  # first, last, dob, email, batch, admission type, note
    ("Ritika", "Arora", "2007-02-10", "ritika.arora@example.test", "CSE-2026-A", "Regular", "valid row"),
    ("Samar", "Bakshi", "2007-03-11", "samar.bakshi@example", "CSE-2026-A", "Regular", "invalid email"),
    ("Tanvi", "Chandra", "2007-04-12", "tanvi.chandra@example.test", "CSE-2030-Z", "Regular", "unknown batch"),
    ("", "Dhillon", "2007-05-13", "dhillon@example.test", "CSE-2026-B", "Regular", "missing first name"),
    ("Uday", "Edwards", "2021-06-14", "uday.edwards@example.test", "CSE-2026-B", "Regular", "date of birth too recent"),
    ("Vanya", "Fadnavis", "2007-07-15", "vanya.fadnavis@example.test", "ECE-2026", "Transfer", "transfer without previous institution"),
    ("Aanya", "Bhosale", "2008-01-01", "aanya.second@example.test", "ECE-2026", "Regular", "possible duplicate (same name and date of birth)"),
    ("Ritika", "Arora", "2007-02-10", "ritika.arora@example.test", "CSE-2026-A", "Regular", "exact duplicate of the first row"),
]

# Meridian College of Commerce enters student IDs (format MCC-9999).
MCC_INTAKE = [("Ojas", "Kapadia"), ("Pihu", "Lobo"), ("Rudra", "Mathur"), ("Siya", "Nanda"), ("Tejas", "Ojha"), ("Urvi", "Puri")]


def mie_rows(admission_date, prefix="MIE/2026/"):
    rows = []
    for i, (first, last, gender, batch, status, guardian) in enumerate(MIE_INTAKE):
        dob = datetime.date(2007, 1 + i % 12, 1 + (i * 3) % 27)
        rows.append(["", f"{prefix}{301 + i}", first, last, dob.isoformat(), gender, f"{first.lower()}.{last.lower()}@mail.example.test",
                     f"+91 98{i:02d}0 1{i:04d}", f"MIE-ADM-26-{301 + i}", admission_date, "Regular", batch, "1", status,
                     guardian[0], guardian[1], f"+91 97{i:02d}0 2{i:04d}"])
    return rows


def issue_rows(admission_date):
    rows = []
    for i, (first, last, dob, email, batch, kind, _) in enumerate(MIE_ISSUES):
        dob_text = datetime.date.fromisoformat(dob).strftime("%d/%m/%Y") if i % 2 else dob   # both accepted date formats
        no = 401 + i
        rows.append(["", f"MIE/2026/{no}", first, last, dob_text, "Female" if i % 2 == 0 else "Male", email, f"+91 96{i:02d}0 3{i:04d}",
                     f"MIE-ADM-26-{no}", admission_date, kind, batch, "1", "", f"Parent of {first or last}", "Guardian", f"+91 95{i:02d}0 4{i:04d}"])
    rows[-2][4] = "2007-01-01"      # same name and date of birth as the imported Aanya Bhosale → possible duplicate
    rows[-1] = list(rows[0])        # exact copy of the first row → duplicate within the file
    return rows


def mcc_rows(admission_date):
    rows = []
    for i, (first, last) in enumerate(MCC_INTAKE):
        rows.append([f"MCC-{2001 + i}", "", first, last, datetime.date(2006, 2 + i, 10 + i).isoformat(), "Female" if i % 2 else "Male",
                     f"{first.lower()}.{last.lower()}@mail.example.test", f"+91 93{i:02d}0 5{i:04d}", f"MCC-ADM-26-{2001 + i}", admission_date, "Regular",
                     "BCOM-2026", "1", "Active", f"Parent of {first}", "Guardian", f"+91 92{i:02d}0 6{i:04d}"])
    return rows


def to_csv(rows):
    out = io.StringIO()
    w = csv.writer(out, lineterminator="\r\n")
    w.writerow(HEADER)
    w.writerows(rows)
    return out.getvalue().encode("utf-8")


def to_xlsx(rows, sheet="Students"):
    """Minimal Office Open XML workbook (inline strings), no third-party library."""
    def col(i):
        s = ""
        i += 1
        while i:
            i, r = divmod(i - 1, 26)
            s = chr(65 + r) + s
        return s
    xml_rows = []
    for r, values in enumerate([HEADER] + rows, start=1):
        cells = "".join(f'<c r="{col(c)}{r}" t="inlineStr"><is><t xml:space="preserve">{escape(str(v))}</t></is></c>' for c, v in enumerate(values) if v != "")
        xml_rows.append(f'<row r="{r}">{cells}</row>')
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                   '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>'
                   '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
                   '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>')
        z.writestr("_rels/.rels", '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
        z.writestr("xl/workbook.xml", '<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
                   f'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="{sheet}" sheetId="1" r:id="rId1"/></sheets></workbook>')
        z.writestr("xl/_rels/workbook.xml.rels", '<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
        z.writestr("xl/worksheets/sheet1.xml", '<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
                   f'<sheetData>{"".join(xml_rows)}</sheetData></worksheet>')
    return buf.getvalue()


def main():
    os.makedirs(OUT, exist_ok=True)
    files = {
        "meridian-mie-intake.csv": to_csv(mie_rows("2026-07-01")),
        "meridian-mie-intake.xlsx": to_xlsx(mie_rows("2026-07-01")),
        "meridian-mie-with-issues.csv": to_csv(issue_rows("2026-07-01")),
        "meridian-mcc-intake.xlsx": to_xlsx(mcc_rows("2026-07-01")),
    }
    for name, content in files.items():
        with open(os.path.join(OUT, name), "wb") as f:
            f.write(content)
        print(f"wrote docs/samples/student-import/{name} ({len(content)} bytes)")


if __name__ == "__main__":
    main()
