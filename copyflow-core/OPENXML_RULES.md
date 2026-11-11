# CopyFlow OpenXML Cloning Rules & Invariants

This document establishes the foundational architectural invariants for deterministic, non-destructive DOCX layout cloning, extracted from `template-layout-cloner/SKILL.md` and related reference specifications.

---

## 1. Section Properties Architecture (`w:sectPr`)

In WordprocessingML, section properties (`<w:sectPr>`) govern document-level and section-level page geometry:
- The trailing `<w:sectPr>` placed as the direct final child of `<w:body>` defines the geometry and layout rules for the final section (or the entire document if only one section exists).
- Intermediate section breaks are embedded inside a paragraph's property block: `<w:p><w:pPr><w:sectPr>...</w:sectPr></w:pPr></w:p>`.
- Inside `<w:sectPr>`:
  - `<w:pgSz w:w="..." w:h="..." w:orient="..."/>`: Sets page physical dimensions in twips / dxa ($1/20$ pt, e.g., $12240 \times 15840$ dxa for Letter, $11909 \times 16834$ dxa for A4).
  - `<w:pgMar w:top="..." w:bottom="..." w:left="..." w:right="..." w:header="..." w:footer="..."/>`: Sets page margins and distance of headers/footers from page edge in dxa.
  - `<w:cols w:num="..." w:space="..."/>`: Controls multi-column layouts and gutter spacing.
  - `<w:headerReference w:type="..." r:id="..."/>` and `<w:footerReference w:type="..." r:id="..."/>`: Bind headers and footers (default, first-page, even-page) to relationship IDs defined in `word/_rels/document.xml.rels`.
  - `<w:pgNumType w:fmt="..." w:start="..."/>`: Controls page numbering format (e.g., Roman `roman` vs. Arabic `decimal`) and numbering sequence resets.

**Invariant:** When cloning a template, never generate `<w:sectPr>` from scratch. CopyFlow preserves the exact XML sub-tree of the reference document's trailing `<w:sectPr>` (and any intermediate section breaks) to guarantee identical margins, orientations, and header/footer bindings.

---

## 2. ZIP Archive Integrity & Subsystem Preservation

A `.docx` file is an Open Packaging Conventions (OPC) ZIP archive. Calling naive generator libraries (like basic `docx.Document()`) creates a generic blank container that erases master branding, custom fonts, numbering schemes, and latent styles.

CopyFlow enforces byte-level archive container cloning:
- **`word/styles.xml`**: Defines `docDefaults` (`rPrDefault`, `pPrDefault`), latent style definitions, and paragraph/character/table styles (`Heading1`, `BodyText`, `TableGrid`, custom styles). Preserved verbatim to guarantee all cascading inheritance rules hold.
- **`word/numbering.xml`**: Controls multilevel abstract lists, custom bullet glyphs, indentations, and list numbering states. Kept intact to preserve bullet and enumeration formatting.
- **`word/settings.xml` & `word/fontTable.xml`**: Maintain document-level zoom, compatibility settings, embedded font records, and font substitutions.
- **`word/_rels/document.xml.rels`**: Maintains URI bindings for images, hyperlinks, headers, and footers.
- **Namespace Stability:** Never serialize relationships using naive XML serializers that rewrite the default namespace to `ns0:Relationships`. Relationships must retain the standard default namespace:
  `xmlns="http://schemas.openxmlformats.org/package/2006/relationships"`.

---

## 3. Table Grids & Cell Properties Preservation

Tables in OpenXML are structurally defined by:
- `<w:tblPr>`: Table-level properties including style (`<w:tblStyle w:val="..."/>`), table width (`<w:tblW w:w="..." w:type="dxa"/>`), alignment (`<w:jc w:val="..."/>`), and layout mode (`<w:tblLayout w:type="fixed"/>`).
- `<w:tblGrid>`: Defines column widths in dxa via sequential `<w:gridCol w:w="..."/>` elements. Fixed column widths ensure tables do not reflow or collapse when rendered across different office suites.
- `<w:trPr>`: Row properties such as `<w:tblHeader/>` (repeat header row across pages) and `<w:cantSplit/>` (prevent row from splitting across page breaks).
- `<w:tcPr>`: Cell properties specifying cell width (`<w:tcW w:w="..." w:type="dxa"/>`), shading/background fill (`<w:shd w:val="clear" w:fill="..."/>`), cell margins (`<w:tcMar>`), and borders (`<w:tcBorders>`).

**Invariant:** When mapping content into table structures, CopyFlow matches source tabular data into the reference `TableBlueprint`, preserving grid column proportions, header shading fills, and border specifications.

---

## 4. Two-Pass Metric Pagination & Orphan Prevention

In document layout transfer, typographical reflow can cause page drift and orphaned headings (where a heading appears at the very bottom of a page with its body text pushed to the next page).

CopyFlow employs a two-pass layout calculation:
- **Usable Canvas Calculation:**
  $$H_{\text{usable}} = H_{\text{page}} - M_{\text{top}} - M_{\text{bottom}}$$
  $$W_{\text{usable}} = \frac{W_{\text{page}} - M_{\text{left}} - M_{\text{right}}}{\text{columns}}$$
- **Pass 1 (Metric Accumulation):**
  Walk through all source blocks and their mapped target styles, tracking cumulative vertical displacement ($Y$ position) in dxa using font size ($10 \times \text{half-points}$ dxa per line height estimate), paragraph spacing (`space_before_dxa`, `space_after_dxa`), line spacing rules, and table row heights.
- **Pass 2 (Orphan Heading Detection & Page Break Injection):**
  If a heading block lands within the bottom $12\%$ of $H_{\text{usable}}$ (i.e. $Y > 0.88 \times H_{\text{usable}}$), it is flagged as an orphaned heading.
  CopyFlow automatically mutates the corresponding `MappingDirective`:
  - Sets `keep_next = true` (`<w:keepNext w:val="1"/>`).
  - Sets `page_break_before = true` (`<w:pageBreakBefore w:val="1"/>`).
  - Increments `orphan_headings_fixed` and `pagination_fixes_applied`.
- Recomputing pagination ensures $100\%$ content completeness without unsightly trailing headings.

