---
name: template-layout-cloner
description: Clone the exact 1:1 visual, structural, and typographical layout of a reference DOCX or PDF template onto the content of a different DOCX or PDF source file. Use whenever the user asks to copy a layout 1:1, match a master report or thesis template, replicate fonts, headings, line spacing, margins, tables, citations, headers/footers, and page breaks from one document onto another document's contents, or convert raw notes into a strictly formatted institutional report.
---
# Template Layout Cloner

Clone the exact 1:1 layout, typography, section geometry, table styling, page breaks, and header/footer numbering of a master `.docx` or `.pdf` template onto the text, tables, and figures of a target content file.

## When to Use

- The user provides two files (a **Master Layout Template** in `.docx` or `.pdf` and a **Content Source File** in `.docx` or `.pdf`) and asks to copy the layout 1:1.
- The user wants all headings, subheadings, fonts, font sizes, line spacing, bold/italic runs, first-line indents, hanging indents, table borders/shading, citations, and page breaks from a reference document applied to their own draft or notes.
- The user needs an academic report, thesis, or institutional document formatted to match an existing master sample without losing any original content.

## Core Workflow

### Step 1: Extract & Audit Both Files (Never Build From Scratch When Cloning DOCX)

Run the bundled `scripts/inspect_template.py` script on both the template file and the content source file to extract a complete structural blueprint:

```bash
python3 skills/template-layout-cloner/scripts/inspect_template.py "<template_path>" "<content_source_path>"
```

1. **If the Master Template is a `.docx` file**:
   - Do **NOT** create a blank `docx.Document()` from scratch. Creating a blank document discards `docDefaults`, custom table styles (`Table1`..`TableN`), latent styles, header/footer XML relationships, and theme definitions.
   - Instead, **unpack the master `.docx` ZIP archive** as your base container, preserve `word/styles.xml`, `word/settings.xml`, `word/fontTable.xml`, `word/numbering.xml`, `word/theme/`, and `word/header*.xml` / `word/footer*.xml` verbatim, and rebuild only `word/document.xml` (and `word/media/` + `word/_rels/document.xml.rels` for new figures).
2. **If the Master Template is a `.pdf` file**:
   - Inspect page dimensions, margins, font families, font sizes (in pt), RGB colors, and bounding boxes using `pdfplumber` and rasterize reference pages with `pypdfium2` to visually inspect the exact layout before constructing the target `.docx` or `.pdf`.

### Step 2: Lock In the 1:1 Layout & Content Mapping Plan

Before generating the output document, map every element of the Content Source File into the Master Template's structural slots:

1. **Section & Page-Numbering Architecture (`<w:sectPr>`)**:
   - Preserve the exact `<w:sectPr>` elements from the master template so section breaks, page margins (`w:pgMar`), page size (`w:pgSz`), and page numbering formats (`w:pgNumType`, e.g., unnumbered Cover/Certificate in Section 0, Roman numerals `i, ii, ...` in Section 1, Arabic numerals `1, 2, ...` starting at `1` in Section 2) behave identically.
2. **Typography & Paragraph Properties (`<w:pPr>` & `<w:rPr>`)**:
   - **Never override `docDefaults` unnecessarily**: If the master template defines `sz="24"` (12pt), `w:line="360"` (1.5 line spacing), and `w:jc="both"` (justified) in `styles.xml`, omit redundant run-level sizes on standard body text so paragraphs inherit cleanly.
   - **Check explicit run overrides on headings**: Word templates frequently define `Calibri` in `Heading1`/`Heading2` style definitions inside `styles.xml` while applying explicit `Times New Roman` `<w:rFonts>` overrides on the runs inside `document.xml`. Always replicate the exact run-level `<w:rFonts>` and `<w:br w:type="textWrapping"/>` line breaks used in the template's headings.
   - **Match exact indentation units (dxa)**: Replicate body first-line indents (e.g., `w:firstLine="648"`), bullet hanging indents (`w:left="648" w:hanging="288"`), and reference hanging indents (`w:left="576" w:hanging="576"`).
3. **Complete Content Preservation**:
   - Map 100% of the user's headings, subheadings, body paragraphs, equations, tables, and references from the content source file. Never drop user sections or invent unverified experimental results.
4. **Hard Page Breaks**:
   - Place `<w:pageBreakBefore w:val="1"/>` on every major front-matter section (`ABSTRACT`, `CONTENTS`, `LIST OF FIGURES`, `LIST OF TABLES`), every `Chapter` heading (`Heading1`), `REFERENCES`, and `APPENDIX` as dictated by the template or user instructions.

### Step 3: Build via OpenXML Clone Pipeline (`scripts/clone_docx_framework.py`)

Use `scripts/clone_docx_framework.py` (detailed in `references/openxml_cloning_guide.md`) to construct the target document:

- **Preserve `.rels` Namespace Integrity**: When adding new images to `word/_rels/document.xml.rels`, insert `<Relationship .../>` tags without altering the default `xmlns="http://schemas.openxmlformats.org/package/2006/relationships"` namespace prefix. Never let `ElementTree` serialize `.rels` with `<ns0:Relationships>`, which causes LibreOffice and Word load errors.
- **Generate Clean Technical Diagrams**: When replacing or generating block diagrams, circuit schematics, or plots with `matplotlib`, allocate generous coordinate spacing between boxes, place signal labels (`Vs`, `Vref`, `ΔT`) in open gaps away from borders, keep legends in empty plot regions away from curves, and never draw diagonal indicator lines across text inside component boxes.

### Step 4: Two-Pass Pagination Sync & Visual Verification

1. **Pass 1 (Initial Build & PDF Render)**:
   - Convert the generated `.docx` to `.pdf` using headless LibreOffice with an isolated profile:
     ```bash
     HOME=/tmp TMPDIR=/tmp timeout 45 soffice "-env:UserInstallation=file:///tmp/lo_profile_$$" --headless --convert-to pdf output.docx
     ```
2. **Pass 2 (Programmatic TOC / LOF / LOT Page Number Sync)**:
   - Scan the rendered PDF with `pypdfium2` to find the exact Arabic or Roman page number where every Chapter, Section, Figure (`FIG. X.Y`), and Table (`Table X.Y`) actually lands.
   - Update the `CONTENTS`, `LIST OF FIGURES`, and `LIST OF TABLES` tables with the verified page numbers and re-generate the `.docx` and `.pdf`.
3. **Visual Critique (`view_file`)**:
   - Render key pages (Cover, Certificate, Contents, List of Figures/Tables, Chapter openings, Tables, and Figures) to PNGs using `pypdfium2` and inspect them to verify zero overlapping labels, zero orphan headings, and 1:1 visual fidelity.

## Gotchas

- **Corrupted `.rels` XML from `ElementTree`**: Python's `xml.etree.ElementTree` rewrites the default namespace in `document.xml.rels` to `ns0:Relationships`, causing LibreOffice (`Error: source file could not be loaded`) and Word to reject the file. Always append new `<Relationship>` nodes via string replacement before `</Relationships>` or use `lxml` preserving namespaces.
- **Mismatched Heading Fonts**: Inspecting only `p.style.font.name` in `python-docx` can report `Calibri` when every heading run in the template actually overrides the font to `Times New Roman`. Always inspect run-level `w:rFonts` in `document.xml`.
- **Overlapping Labels in Matplotlib Schematics**: Never place text labels at the same `(x, y)` coordinate as a wire or box edge. Use explicit bounding boxes (`bbox=dict(fc="white", ec=...)`) or offset coordinates by at least `0.25–0.4` data units, and visually inspect every generated PNG before embedding.
