use std::fs::File;
use std::io::{Read, Write};
use std::path::Path;
use zip::write::SimpleFileOptions;
use zip::{ZipArchive, ZipWriter};

use crate::schema::{LayoutBlueprint, MappingDirective, SourceBlock};

fn escape_xml(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    for c in input.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&apos;"),
            other => out.push(other),
        }
    }
    out
}

pub fn clone_docx_preserving_template(
    reference_docx: &Path,
    output_docx: &Path,
    blueprint: &LayoutBlueprint,
    blocks: &[SourceBlock],
    directives: &[MappingDirective],
) -> Result<(), String> {
    if !reference_docx.exists() {
        return Err(format!("Reference template does not exist: {}", reference_docx.display()));
    }

    if let Some(parent) = output_docx.parent() {
        let _ = std::fs::create_dir_all(parent);
    }

    let src_file = File::open(reference_docx)
        .map_err(|e| format!("Failed to open reference DOCX: {}", e))?;
    let mut archive = ZipArchive::new(src_file)
        .map_err(|e| format!("Failed to read reference DOCX ZIP: {}", e))?;

    let out_file = File::create(output_docx)
        .map_err(|e| format!("Failed to create output DOCX: {}", e))?;
    let mut zip_writer = ZipWriter::new(out_file);

    let default_options = SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);

    // Copy every entry from reference DOCX verbatim, except word/document.xml
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i)
            .map_err(|e| format!("Failed reading entry {} from ZIP: {}", i, e))?;
        let entry_name = entry.name().to_string();

        if entry_name == "word/document.xml" {
            continue;
        }

        zip_writer.start_file(&entry_name, default_options)
            .map_err(|e| format!("Failed to write ZIP entry {}: {}", entry_name, e))?;

        let mut buf = Vec::new();
        entry.read_to_end(&mut buf)
            .map_err(|e| format!("Failed reading entry data {}: {}", entry_name, e))?;
        zip_writer.write_all(&buf)
            .map_err(|e| format!("Failed writing entry data {}: {}", entry_name, e))?;
    }

    // Now construct the rebuilt word/document.xml with cloned template styles & sectPr
    let mut body_xml = String::new();
    body_xml.push_str("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
    body_xml.push_str("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">\n");
    body_xml.push_str("<w:body>\n");

    for (idx, block) in blocks.iter().enumerate() {
        let directive = directives.get(idx);
        let target_style = directive
            .map(|d| d.target_style_id.as_str())
            .unwrap_or("Normal");
        let page_break_before = directive
            .map(|d| d.page_break_before)
            .unwrap_or(false);
        let keep_next = directive
            .map(|d| d.keep_next)
            .unwrap_or(false);

        if block.block_type == "table" {
            // Render table using reference TableBlueprint grid columns
            let default_grid = vec![3000, 3000, 3000];
            let col_widths = if let Some(tbl_idx) = directive.and_then(|d| d.table_template_index) {
                blueprint.tables.get(tbl_idx)
                    .map(|t| &t.grid_col_widths_dxa)
                    .unwrap_or(&default_grid)
            } else if let Some(first_tbl) = blueprint.tables.first() {
                &first_tbl.grid_col_widths_dxa
            } else {
                &default_grid
            };

            let total_w: u32 = col_widths.iter().sum();
            body_xml.push_str("<w:tbl>\n");
            body_xml.push_str("<w:tblPr>\n");
            body_xml.push_str(&format!("<w:tblStyle w:val=\"{}\"/>\n", escape_xml(target_style)));
            body_xml.push_str(&format!("<w:tblW w:w=\"{}\" w:type=\"dxa\"/>\n", total_w));
            body_xml.push_str("<w:jc w:val=\"center\"/>\n");
            body_xml.push_str("<w:tblLayout w:type=\"fixed\"/>\n");
            body_xml.push_str("</w:tblPr>\n");

            // tblGrid
            body_xml.push_str("<w:tblGrid>\n");
            for cw in col_widths {
                body_xml.push_str(&format!("<w:gridCol w:w=\"{}\"/>\n", cw));
            }
            body_xml.push_str("</w:tblGrid>\n");

            if let Some(ref rows) = block.table_cells {
                for (r_idx, row) in rows.iter().enumerate() {
                    body_xml.push_str("<w:tr>\n");
                    body_xml.push_str("<w:trPr><w:cantSplit w:val=\"0\"/>");
                    if r_idx == 0 {
                        body_xml.push_str("<w:tblHeader w:val=\"0\"/>");
                    }
                    body_xml.push_str("</w:trPr>\n");

                    for (c_idx, cell) in row.iter().enumerate() {
                        let cell_w = col_widths.get(c_idx).copied().unwrap_or(3000);
                        body_xml.push_str("<w:tc>\n");
                        body_xml.push_str("<w:tcPr>\n");
                        body_xml.push_str(&format!("<w:tcW w:w=\"{}\" w:type=\"dxa\"/>\n", cell_w));
                        if r_idx == 0 {
                            body_xml.push_str("<w:shd w:fill=\"f1f5f9\" w:val=\"clear\"/>\n");
                        }
                        body_xml.push_str("</w:tcPr>\n");

                        // Cell paragraph
                        body_xml.push_str("<w:p>\n");
                        body_xml.push_str("<w:pPr><w:jc w:val=\"left\"/><w:spacing w:before=\"40\" w:after=\"40\"/></w:pPr>\n");
                        body_xml.push_str("<w:r>\n");
                        if r_idx == 0 {
                            body_xml.push_str("<w:rPr><w:b w:val=\"1\"/></w:rPr>\n");
                        }
                        body_xml.push_str(&format!(
                            "<w:t xml:space=\"preserve\">{}</w:t>\n",
                            escape_xml(cell)
                        ));
                        body_xml.push_str("</w:r>\n");
                        body_xml.push_str("</w:p>\n");

                        body_xml.push_str("</w:tc>\n");
                    }
                    body_xml.push_str("</w:tr>\n");
                }
            }
            body_xml.push_str("</w:tbl>\n");
        } else {
            // Render paragraph
            body_xml.push_str("<w:p>\n");
            body_xml.push_str("<w:pPr>\n");
            body_xml.push_str(&format!("<w:pStyle w:val=\"{}\"/>\n", escape_xml(target_style)));
            if keep_next {
                body_xml.push_str("<w:keepNext w:val=\"1\"/>\n");
            }
            if page_break_before {
                body_xml.push_str("<w:pageBreakBefore w:val=\"1\"/>\n");
            }
            body_xml.push_str("</w:pPr>\n");

            if block.runs.is_empty() {
                body_xml.push_str("<w:r>\n");
                body_xml.push_str(&format!(
                    "<w:t xml:space=\"preserve\">{}</w:t>\n",
                    escape_xml(&block.plain_text)
                ));
                body_xml.push_str("</w:r>\n");
            } else {
                for run in &block.runs {
                    body_xml.push_str("<w:r>\n");
                    if run.bold || run.italic || run.underline {
                        body_xml.push_str("<w:rPr>\n");
                        if run.bold {
                            body_xml.push_str("<w:b w:val=\"1\"/>\n");
                        }
                        if run.italic {
                            body_xml.push_str("<w:i w:val=\"1\"/>\n");
                        }
                        if run.underline {
                            body_xml.push_str("<w:u w:val=\"single\"/>\n");
                        }
                        body_xml.push_str("</w:rPr>\n");
                    }
                    body_xml.push_str(&format!(
                        "<w:t xml:space=\"preserve\">{}</w:t>\n",
                        escape_xml(&run.text)
                    ));
                    body_xml.push_str("</w:r>\n");
                }
            }
            body_xml.push_str("</w:p>\n");
        }
    }

    // Append the preserved trailing sectPr from the reference template
    body_xml.push_str(&blueprint.raw_trailing_sect_pr_xml);
    body_xml.push_str("\n</w:body>\n</w:document>\n");

    zip_writer.start_file("word/document.xml", default_options)
        .map_err(|e| format!("Failed to start word/document.xml in ZIP: {}", e))?;
    zip_writer.write_all(body_xml.as_bytes())
        .map_err(|e| format!("Failed writing word/document.xml: {}", e))?;

    zip_writer.finish()
        .map_err(|e| format!("Failed finalizing output ZIP: {}", e))?;

    Ok(())
}

