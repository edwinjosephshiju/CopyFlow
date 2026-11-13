# OpenXML & PDF 1:1 Template Layout Cloning Guide

This reference details the exact OpenXML patterns, unit conversions, schematic diagram standards, and two-pass pagination workflows required to achieve 1:1 visual and structural fidelity when cloning a master `.docx` or `.pdf` template onto another document's content.

## 1. OpenXML Unit Conversions

Word OpenXML (`word/document.xml` and `word/styles.xml`) uses specific integer units rather than floating-point inches or points:

| Property | OpenXML Unit | Conversion Formula | Example |
| :--- | :--- | :--- | :--- |
| **Font Size (`w:sz`, `w:szCs`)** | Half-points (`1/2 pt`) | `pt * 2` | `12 pt` = `"24"`, `10.5 pt` = `"21"`, `10 pt` = `"20"` |
| **Page Size & Margins (`w:pgSz`, `w:pgMar`)** | Twips / `dxa` (`1/20 pt` = `1/1440 in`) | `inches * 1440` or `pt * 20` | `1.0 in` = `"1440"`, `1.25 in` = `"1800"`, A4 = `11909 x 16834` |
| **Paragraph Spacing (`w:before`, `w:after`)** | Twips / `dxa` (`1/20 pt`) | `pt * 20` | `36 pt` = `"720"`, `14 pt` = `"280"`, `6 pt` = `"120"`, `2 pt` = `"40"` |
| **Line Spacing (`w:line`, `w:lineRule="auto"`)** | `240ths` of a line | `lines * 240` | Single (`1.0`) = `"240"`, `1.3` = `"312"`, `1.5` = `"360"`, Double (`2.0`) = `"480"` |
| **Indentation (`w:firstLine`, `w:left`, `w:hanging`)** | Twips / `dxa` (`1/1440 in`) | `inches * 1440` | `0.45 in` = `"648"`, `0.40 in` = `"576"`, `0.20 in` = `"288"` |
| **Table Borders (`w:sz`)** | Eighths of a point (`1/8 pt`) | `pt * 8` | `0.75 pt` = `"6"`, `0.5 pt` = `"4"` |
| **Drawing Extents (`wp:extent cx, cy`)** | EMUs (`1/914400 in`) | `inches * 914400` | `3.8 in` = `"3474720"`, `1.1 in` = `"1005840"` |

## 2. Why Unpacking the Master DOCX Archive Beats `docx.Document()`

When you call `docx.Document()` in `python-docx`, it loads a generic built-in template that:
1. Overwrites `docDefaults` (defaulting to 11pt Calibri, 1.15 spacing, 8pt space-after).
2. Lacks custom table styles (`Table1`..`Table11`) and cell margin defaults defined in the user's master `.docx`.
3. Alters section header/footer relationships (`header1.xml`, `footer2.xml`, `footer3.xml`).

**The 1:1 Archive Cloning Pattern**:
1. Extract the reference `.docx` into a temporary build folder (`zipfile.ZipFile(template_path).extractall(temp_dir)`).
2. Parse `word/document.xml` and extract all `<w:sectPr>` elements (`body.findall('.//w:sectPr')`).
3. Clear `body` children, construct the new paragraphs and tables using `clone_docx_framework.py` helpers, and re-insert the exact `<w:sectPr>` elements at their corresponding section boundaries:
   - **Section 0 (`sect0_p`)**: Placed inside `<w:p><w:pPr><w:sectPr>...</w:sectPr></w:pPr></w:p>` immediately after the Certificate page (unnumbered).
   - **Section 1 (`sect1_p`)**: Placed inside `<w:p><w:pPr><w:sectPr>...</w:sectPr></w:pPr></w:p>` immediately after the List of Tables (Roman numerals `i, ii, ...`).
   - **Section 2 (`sect2_sectPr`)**: Appended as the final child of `<w:body>` after the Appendix (Arabic numerals `1, 2, ...` starting at `1`).

## 3. Preserving `.rels` Integrity When Adding Images

Never use `xml.etree.ElementTree.write()` to serialize `word/_rels/document.xml.rels`. `ElementTree` rewrites the root element to `<ns0:Relationships xmlns:ns0="...">`, which causes LibreOffice and Microsoft Word to reject the `.docx` file as corrupted. Always use `add_image_relationship_safe(rels_path, rId, target_media_path)` from `clone_docx_framework.py` to inject `<Relationship .../>` via clean string replacement before `</Relationships>`.

## 4. Clean Technical Diagram & Schematic Generation Rules

When generating replacement block diagrams, circuit schematics, or characteristic plots with `matplotlib`:

1. **Generous Inter-Block Gaps**: Leave at least `0.8–1.0` data units between adjacent block diagram boxes so signal labels (`ΔT`, `ΔR`, `Vs`, `Vout`) sit cleanly above the arrows without touching box borders.
2. **No Wires Passing Through Text**: Never route power supply lines (`Vcc`, `GND`, or relay coil leads) through the center of a component box where label text resides. Terminate lines cleanly at the top and bottom edges of the rectangle (`y_top`, `y_bottom`).
3. **Component Indicators Outside Text Boxes**: For thermistors (`-t°`), photodiodes, or variable resistors, place diagonal indicator lines near the outer corner of the component rectangle so the internal label (`R1 (NTC) 10 kΩ`) is 100% unobstructed.
4. **Plot Legends in Open Whitespace**: Inspect the curve trajectory before placing legends. Use `bbox_to_anchor` to position legends in an empty region of the plot canvas, and expand `ylim` by 15–20% so legends never touch data curves or threshold lines.
