use std::collections::BTreeMap;
use std::fs::File;
use std::io::Read;
use std::path::Path;
use quick_xml::events::Event;
use quick_xml::reader::Reader;
use zip::ZipArchive;

use crate::schema::{
    InlineRun, LayoutBlueprint, PageGeometry, SourceBlock, StyleBlueprint, TableBlueprint,
};

pub fn inspect_docx_template(path: &Path) -> Result<LayoutBlueprint, String> {
    if !path.exists() {
        return Err(format!("Template file does not exist: {}", path.display()));
    }

    let file = File::open(path)
        .map_err(|e| format!("Failed to open template DOCX {}: {}", path.display(), e))?;
    let mut archive = ZipArchive::new(file)
        .map_err(|e| format!("Failed to read ZIP archive {}: {}", path.display(), e))?;

    let mut has_headers = false;
    let mut has_footers = false;
    for i in 0..archive.len() {
        if let Ok(file) = archive.by_index(i) {
            let name = file.name();
            if name.starts_with("word/header") && name.ends_with(".xml") {
                has_headers = true;
            }
            if name.starts_with("word/footer") && name.ends_with(".xml") {
                has_footers = true;
            }
        }
    }

    // 1. Parse word/styles.xml
    let mut styles = BTreeMap::new();
    if let Ok(mut styles_file) = archive.by_name("word/styles.xml") {
        let mut xml_content = String::new();
        styles_file
            .read_to_string(&mut xml_content)
            .map_err(|e| format!("Failed reading word/styles.xml: {}", e))?;
        styles = parse_styles_xml(&xml_content);
    }

    // Ensure default fallback styles exist
    if !styles.contains_key("Normal") {
        styles.insert(
            "Normal".to_string(),
            StyleBlueprint {
                style_id: "Normal".to_string(),
                name: "Normal".to_string(),
                style_type: "paragraph".to_string(),
                based_on: None,
                font_name: Some("Calibri".to_string()),
                font_size_half_pts: Some(24), // 12 pt
                bold: false,
                italic: false,
                color_hex: None,
                alignment: Some("both".to_string()),
                space_before_dxa: Some(0),
                space_after_dxa: Some(160),
                line_spacing_dxa: Some(240),
                keep_next: false,
                page_break_before: false,
            },
        );
    }

    // 2. Parse word/document.xml for tables, sectPr, and page geometry
    let mut doc_xml = String::new();
    if let Ok(mut doc_file) = archive.by_name("word/document.xml") {
        doc_file
            .read_to_string(&mut doc_xml)
            .map_err(|e| format!("Failed reading word/document.xml: {}", e))?;
    } else {
        return Err("Missing word/document.xml in DOCX archive".to_string());
    }

    let (page_geometry, raw_trailing_sect_pr_xml) = parse_document_sect_pr(&doc_xml);
    let tables = parse_document_tables(&doc_xml);

    Ok(LayoutBlueprint {
        template_format: "docx".to_string(),
        page_geometry,
        styles,
        tables,
        has_headers,
        has_footers,
        raw_trailing_sect_pr_xml,
    })
}

pub fn extract_source_blocks(path: &Path) -> Result<Vec<SourceBlock>, String> {
    if !path.exists() {
        return Err(format!("Source document does not exist: {}", path.display()));
    }

    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_lowercase();

    if ext == "docx" {
        extract_source_blocks_from_docx(path)
    } else {
        // Plain text / markdown fallback
        let content = std::fs::read_to_string(path)
            .map_err(|e| format!("Failed reading source file {}: {}", path.display(), e))?;
        extract_source_blocks_from_text(&content)
    }
}

pub fn extract_source_blocks_from_docx(path: &Path) -> Result<Vec<SourceBlock>, String> {
    let file = File::open(path)
        .map_err(|e| format!("Failed to open source DOCX {}: {}", path.display(), e))?;
    let mut archive = ZipArchive::new(file)
        .map_err(|e| format!("Failed to read source ZIP archive: {}", e))?;

    let mut doc_xml = String::new();
    if let Ok(mut doc_file) = archive.by_name("word/document.xml") {
        doc_file
            .read_to_string(&mut doc_xml)
            .map_err(|e| format!("Failed reading word/document.xml: {}", e))?;
    } else {
        return Err("Missing word/document.xml in source DOCX".to_string());
    }

    parse_source_blocks_from_document_xml(&doc_xml)
}

pub fn extract_source_blocks_from_text(text: &str) -> Result<Vec<SourceBlock>, String> {
    let mut blocks = Vec::new();
    let mut block_idx = 0;

    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }

        block_idx += 1;
        let block_id = format!("blk_{:04}", block_idx);

        if let Some(h1_text) = trimmed.strip_prefix("# ") {
            blocks.push(SourceBlock {
                block_id,
                block_type: "heading".to_string(),
                plain_text: h1_text.to_string(),
                runs: vec![InlineRun {
                    text: h1_text.to_string(),
                    bold: true,
                    italic: false,
                    underline: false,
                    monospace: false,
                }],
                heading_level: Some(1),
                table_cells: None,
            });
        } else if let Some(h2_text) = trimmed.strip_prefix("## ") {
            blocks.push(SourceBlock {
                block_id,
                block_type: "heading".to_string(),
                plain_text: h2_text.to_string(),
                runs: vec![InlineRun {
                    text: h2_text.to_string(),
                    bold: true,
                    italic: false,
                    underline: false,
                    monospace: false,
                }],
                heading_level: Some(2),
                table_cells: None,
            });
        } else if let Some(h3_text) = trimmed.strip_prefix("### ") {
            blocks.push(SourceBlock {
                block_id,
                block_type: "heading".to_string(),
                plain_text: h3_text.to_string(),
                runs: vec![InlineRun {
                    text: h3_text.to_string(),
                    bold: true,
                    italic: false,
                    underline: false,
                    monospace: false,
                }],
                heading_level: Some(3),
                table_cells: None,
            });
        } else if trimmed.starts_with("|") && trimmed.ends_with("|") {
            // Table row
            let cells: Vec<String> = trimmed
                .split('|')
                .filter(|s| !s.is_empty())
                .map(|s| s.trim().to_string())
                .collect();
            blocks.push(SourceBlock {
                block_id,
                block_type: "table".to_string(),
                plain_text: trimmed.to_string(),
                runs: vec![],
                heading_level: None,
                table_cells: Some(vec![cells]),
            });
        } else {
            blocks.push(SourceBlock {
                block_id,
                block_type: "paragraph".to_string(),
                plain_text: trimmed.to_string(),
                runs: vec![InlineRun {
                    text: trimmed.to_string(),
                    bold: false,
                    italic: false,
                    underline: false,
                    monospace: false,
                }],
                heading_level: None,
                table_cells: None,
            });
        }
    }

    Ok(blocks)
}

fn parse_styles_xml(xml: &str) -> BTreeMap<String, StyleBlueprint> {
    let mut styles = BTreeMap::new();
    let mut reader = Reader::from_str(xml);
    reader.config_mut().trim_text(true);

    let mut current_style: Option<StyleBlueprint> = None;
    let mut in_rpr = false;
    let mut in_ppr = false;

    let mut buf = Vec::new();
    while let Ok(event) = reader.read_event_into(&mut buf) {
        match event {
            Event::Start(e) => {
                let local_name = e.local_name();
                if local_name.as_ref() == b"style" {
                    let mut style_id = String::new();
                    let mut style_type = "paragraph".to_string();
                    for attr in e.attributes().flatten() {
                        let key = attr.key.local_name();
                        if key.as_ref() == b"styleId" {
                            style_id = String::from_utf8_lossy(&attr.value).to_string();
                        } else if key.as_ref() == b"type" {
                            style_type = String::from_utf8_lossy(&attr.value).to_string();
                        }
                    }
                    if !style_id.is_empty() {
                        current_style = Some(StyleBlueprint {
                            name: style_id.clone(),
                            style_id,
                            style_type,
                            based_on: None,
                            font_name: None,
                            font_size_half_pts: None,
                            bold: false,
                            italic: false,
                            color_hex: None,
                            alignment: None,
                            space_before_dxa: None,
                            space_after_dxa: None,
                            line_spacing_dxa: None,
                            keep_next: false,
                            page_break_before: false,
                        });
                    }
                } else if local_name.as_ref() == b"rPr" {
                    in_rpr = true;
                } else if local_name.as_ref() == b"pPr" {
                    in_ppr = true;
                } else if let Some(ref mut st) = current_style {
                    if in_rpr {
                        if local_name.as_ref() == b"b" {
                            st.bold = true;
                        } else if local_name.as_ref() == b"i" {
                            st.italic = true;
                        } else if local_name.as_ref() == b"sz" {
                            for attr in e.attributes().flatten() {
                                if attr.key.local_name().as_ref() == b"val" {
                                    if let Ok(sz) = String::from_utf8_lossy(&attr.value).parse::<u32>() {
                                        st.font_size_half_pts = Some(sz);
                                    }
                                }
                            }
                        } else if local_name.as_ref() == b"rFonts" {
                            for attr in e.attributes().flatten() {
                                let k = attr.key.local_name();
                                if k.as_ref() == b"ascii" || k.as_ref() == b"hAnsi" {
                                    st.font_name = Some(String::from_utf8_lossy(&attr.value).to_string());
                                }
                            }
                        }
                    } else if in_ppr {
                        if local_name.as_ref() == b"jc" {
                            for attr in e.attributes().flatten() {
                                if attr.key.local_name().as_ref() == b"val" {
                                    st.alignment = Some(String::from_utf8_lossy(&attr.value).to_string());
                                }
                            }
                        } else if local_name.as_ref() == b"keepNext" {
                            st.keep_next = true;
                        } else if local_name.as_ref() == b"pageBreakBefore" {
                            st.page_break_before = true;
                        } else if local_name.as_ref() == b"spacing" {
                            for attr in e.attributes().flatten() {
                                let k = attr.key.local_name();
                                if k.as_ref() == b"before" {
                                    if let Ok(v) = String::from_utf8_lossy(&attr.value).parse::<u32>() {
                                        st.space_before_dxa = Some(v);
                                    }
                                } else if k.as_ref() == b"after" {
                                    if let Ok(v) = String::from_utf8_lossy(&attr.value).parse::<u32>() {
                                        st.space_after_dxa = Some(v);
                                    }
                                } else if k.as_ref() == b"line" {
                                    if let Ok(v) = String::from_utf8_lossy(&attr.value).parse::<u32>() {
                                        st.line_spacing_dxa = Some(v);
                                    }
                                }
                            }
                        }
                    } else if local_name.as_ref() == b"name" {
                        for attr in e.attributes().flatten() {
                            if attr.key.local_name().as_ref() == b"val" {
                                st.name = String::from_utf8_lossy(&attr.value).to_string();
                            }
                        }
                    } else if local_name.as_ref() == b"basedOn" {
                        for attr in e.attributes().flatten() {
                            if attr.key.local_name().as_ref() == b"val" {
                                st.based_on = Some(String::from_utf8_lossy(&attr.value).to_string());
                            }
                        }
                    }
                }
            }
            Event::Empty(e) => {
                let local_name = e.local_name();
                if let Some(ref mut st) = current_style {
                    if in_rpr {
                        if local_name.as_ref() == b"b" {
                            st.bold = true;
                        } else if local_name.as_ref() == b"i" {
                            st.italic = true;
                        } else if local_name.as_ref() == b"sz" {
                            for attr in e.attributes().flatten() {
                                if attr.key.local_name().as_ref() == b"val" {
                                    if let Ok(sz) = String::from_utf8_lossy(&attr.value).parse::<u32>() {
                                        st.font_size_half_pts = Some(sz);
                                    }
                                }
                            }
                        } else if local_name.as_ref() == b"rFonts" {
                            for attr in e.attributes().flatten() {
                                let k = attr.key.local_name();
                                if k.as_ref() == b"ascii" || k.as_ref() == b"hAnsi" {
                                    st.font_name = Some(String::from_utf8_lossy(&attr.value).to_string());
                                }
                            }
                        }
                    } else if in_ppr {
                        if local_name.as_ref() == b"jc" {
                            for attr in e.attributes().flatten() {
                                if attr.key.local_name().as_ref() == b"val" {
                                    st.alignment = Some(String::from_utf8_lossy(&attr.value).to_string());
                                }
                            }
                        } else if local_name.as_ref() == b"keepNext" {
                            st.keep_next = true;
                        } else if local_name.as_ref() == b"pageBreakBefore" {
                            st.page_break_before = true;
                        } else if local_name.as_ref() == b"spacing" {
                            for attr in e.attributes().flatten() {
                                let k = attr.key.local_name();
                                if k.as_ref() == b"before" {
                                    if let Ok(v) = String::from_utf8_lossy(&attr.value).parse::<u32>() {
                                        st.space_before_dxa = Some(v);
                                    }
                                } else if k.as_ref() == b"after" {
                                    if let Ok(v) = String::from_utf8_lossy(&attr.value).parse::<u32>() {
                                        st.space_after_dxa = Some(v);
                                    }
                                } else if k.as_ref() == b"line" {
                                    if let Ok(v) = String::from_utf8_lossy(&attr.value).parse::<u32>() {
                                        st.line_spacing_dxa = Some(v);
                                    }
                                }
                            }
                        }
                    } else if local_name.as_ref() == b"name" {
                        for attr in e.attributes().flatten() {
                            if attr.key.local_name().as_ref() == b"val" {
                                st.name = String::from_utf8_lossy(&attr.value).to_string();
                            }
                        }
                    } else if local_name.as_ref() == b"basedOn" {
                        for attr in e.attributes().flatten() {
                            if attr.key.local_name().as_ref() == b"val" {
                                st.based_on = Some(String::from_utf8_lossy(&attr.value).to_string());
                            }
                        }
                    }
                }
            }
            Event::End(e) => {
                let local_name = e.local_name();
                if local_name.as_ref() == b"style" {
                    if let Some(st) = current_style.take() {
                        styles.insert(st.style_id.clone(), st);
                    }
                } else if local_name.as_ref() == b"rPr" {
                    in_rpr = false;
                } else if local_name.as_ref() == b"pPr" {
                    in_ppr = false;
                }
            }
            Event::Eof => break,
            _ => {}
        }
        buf.clear();
    }

    styles
}

fn parse_document_sect_pr(xml: &str) -> (PageGeometry, String) {
    let mut geom = PageGeometry::default();
    let mut raw_sect_pr = String::new();

    // Find the trailing <w:sectPr> block
    if let Some(start_idx) = xml.rfind("<w:sectPr") {
        if let Some(end_rel) = xml[start_idx..].find("</w:sectPr>") {
            let end_idx = start_idx + end_rel + "</w:sectPr>".len();
            raw_sect_pr = xml[start_idx..end_idx].to_string();

            // Parse attributes from sect_pr block
            let mut reader = Reader::from_str(&raw_sect_pr);
            let mut buf = Vec::new();
            while let Ok(event) = reader.read_event_into(&mut buf) {
                match event {
                    Event::Empty(e) | Event::Start(e) => {
                        let name = e.local_name();
                        if name.as_ref() == b"pgSz" {
                            for attr in e.attributes().flatten() {
                                let k = attr.key.local_name();
                                if k.as_ref() == b"w" {
                                    if let Ok(v) = String::from_utf8_lossy(&attr.value).parse::<u32>() {
                                        geom.width_dxa = v;
                                    }
                                } else if k.as_ref() == b"h" {
                                    if let Ok(v) = String::from_utf8_lossy(&attr.value).parse::<u32>() {
                                        geom.height_dxa = v;
                                    }
                                } else if k.as_ref() == b"orient" {
                                    geom.orientation = String::from_utf8_lossy(&attr.value).to_string();
                                }
                            }
                        } else if name.as_ref() == b"pgMar" {
                            for attr in e.attributes().flatten() {
                                let k = attr.key.local_name();
                                let val_res = String::from_utf8_lossy(&attr.value).parse::<u32>();
                                if let Ok(val) = val_res {
                                    if k.as_ref() == b"top" {
                                        geom.margin_top_dxa = val;
                                    } else if k.as_ref() == b"bottom" {
                                        geom.margin_bottom_dxa = val;
                                    } else if k.as_ref() == b"left" {
                                        geom.margin_left_dxa = val;
                                    } else if k.as_ref() == b"right" {
                                        geom.margin_right_dxa = val;
                                    } else if k.as_ref() == b"header" {
                                        geom.header_dxa = val;
                                    } else if k.as_ref() == b"footer" {
                                        geom.footer_dxa = val;
                                    }
                                }
                            }
                        } else if name.as_ref() == b"cols" {
                            for attr in e.attributes().flatten() {
                                if attr.key.local_name().as_ref() == b"num" {
                                    if let Ok(num) = String::from_utf8_lossy(&attr.value).parse::<u32>() {
                                        geom.columns = num;
                                    }
                                }
                            }
                        }
                    }
                    Event::Eof => break,
                    _ => {}
                }
                buf.clear();
            }
        }
    }

    if raw_sect_pr.is_empty() {
        // Fallback default sectPr
        raw_sect_pr = format!(
            "<w:sectPr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:pgSz w:w=\"{}\" w:h=\"{}\" w:orient=\"{}\"/><w:pgMar w:top=\"{}\" w:bottom=\"{}\" w:left=\"{}\" w:right=\"{}\" w:header=\"{}\" w:footer=\"{}\"/><w:cols w:num=\"{}\"/></w:sectPr>",
            geom.width_dxa, geom.height_dxa, geom.orientation,
            geom.margin_top_dxa, geom.margin_bottom_dxa, geom.margin_left_dxa, geom.margin_right_dxa,
            geom.header_dxa, geom.footer_dxa, geom.columns
        );
    }

    (geom, raw_sect_pr)
}

fn parse_document_tables(xml: &str) -> Vec<TableBlueprint> {
    let mut tables = Vec::new();
    let mut reader = Reader::from_str(xml);
    let mut buf = Vec::new();

    let mut in_tbl = false;
    let mut in_tbl_grid = false;
    let mut current_cols = Vec::new();
    let mut current_style = None;
    let mut tbl_index = 0;

    while let Ok(event) = reader.read_event_into(&mut buf) {
        match event {
            Event::Start(e) => {
                let name = e.local_name();
                if name.as_ref() == b"tbl" {
                    in_tbl = true;
                    current_cols.clear();
                    current_style = None;
                } else if in_tbl && name.as_ref() == b"tblGrid" {
                    in_tbl_grid = true;
                }
            }
            Event::Empty(e) => {
                let name = e.local_name();
                if in_tbl && in_tbl_grid && name.as_ref() == b"gridCol" {
                    for attr in e.attributes().flatten() {
                        if attr.key.local_name().as_ref() == b"w" {
                            if let Ok(w) = String::from_utf8_lossy(&attr.value).parse::<u32>() {
                                current_cols.push(w);
                            }
                        }
                    }
                } else if in_tbl && name.as_ref() == b"tblStyle" {
                    for attr in e.attributes().flatten() {
                        if attr.key.local_name().as_ref() == b"val" {
                            current_style = Some(String::from_utf8_lossy(&attr.value).to_string());
                        }
                    }
                }
            }
            Event::End(e) => {
                let name = e.local_name();
                if name.as_ref() == b"tblGrid" {
                    in_tbl_grid = false;
                } else if name.as_ref() == b"tbl" {
                    in_tbl = false;
                    tables.push(TableBlueprint {
                        template_table_index: tbl_index,
                        style_id: current_style.clone(),
                        grid_col_widths_dxa: if current_cols.is_empty() {
                            vec![3000, 3000, 3000]
                        } else {
                            current_cols.clone()
                        },
                        has_header_row: true,
                        border_summary: "single".to_string(),
                        header_shading_fill: Some("f1f5f9".to_string()),
                    });
                    tbl_index += 1;
                }
            }
            Event::Eof => break,
            _ => {}
        }
        buf.clear();
    }

    tables
}

fn parse_source_blocks_from_document_xml(xml: &str) -> Result<Vec<SourceBlock>, String> {
    let mut blocks = Vec::new();
    let mut reader = Reader::from_str(xml);
    let mut buf = Vec::new();

    let mut in_p = false;
    let mut in_tbl = false;
    let mut in_tr = false;
    let mut in_tc = false;
    let mut in_r = false;
    let mut in_t = false;

    let mut cur_runs = Vec::new();
    let mut cur_p_style: Option<String> = None;
    let mut cur_run_bold = false;
    let mut cur_run_italic = false;
    let mut cur_run_underline = false;
    let mut cur_text = String::new();

    let mut current_table_rows: Vec<Vec<String>> = Vec::new();
    let mut current_row_cells: Vec<String> = Vec::new();
    let mut current_cell_text = String::new();

    let mut block_idx = 0;

    while let Ok(event) = reader.read_event_into(&mut buf) {
        match event {
            Event::Start(e) => {
                let name = e.local_name();
                if name.as_ref() == b"tbl" {
                    in_tbl = true;
                    current_table_rows.clear();
                } else if in_tbl && name.as_ref() == b"tr" {
                    in_tr = true;
                    current_row_cells.clear();
                } else if in_tbl && in_tr && name.as_ref() == b"tc" {
                    in_tc = true;
                    current_cell_text.clear();
                } else if name.as_ref() == b"p" {
                    in_p = true;
                    cur_runs.clear();
                    cur_p_style = None;
                } else if name.as_ref() == b"r" {
                    in_r = true;
                    cur_run_bold = false;
                    cur_run_italic = false;
                    cur_run_underline = false;
                } else if name.as_ref() == b"t" {
                    in_t = true;
                    cur_text.clear();
                } else if in_r && name.as_ref() == b"b" {
                    cur_run_bold = true;
                } else if in_r && name.as_ref() == b"i" {
                    cur_run_italic = true;
                } else if in_r && name.as_ref() == b"u" {
                    cur_run_underline = true;
                }
            }
            Event::Empty(e) => {
                let name = e.local_name();
                if in_p && name.as_ref() == b"pStyle" {
                    for attr in e.attributes().flatten() {
                        if attr.key.local_name().as_ref() == b"val" {
                            cur_p_style = Some(String::from_utf8_lossy(&attr.value).to_string());
                        }
                    }
                } else if in_r && name.as_ref() == b"b" {
                    cur_run_bold = true;
                } else if in_r && name.as_ref() == b"i" {
                    cur_run_italic = true;
                } else if in_r && name.as_ref() == b"u" {
                    cur_run_underline = true;
                }
            }
            Event::Text(t) => {
                if in_t {
                    let s = t.unescape().unwrap_or_default().to_string();
                    cur_text.push_str(&s);
                    if in_tc {
                        current_cell_text.push_str(&s);
                    }
                }
            }
            Event::End(e) => {
                let name = e.local_name();
                if name.as_ref() == b"t" {
                    in_t = false;
                } else if name.as_ref() == b"r" {
                    in_r = false;
                    if !cur_text.is_empty() {
                        cur_runs.push(InlineRun {
                            text: cur_text.clone(),
                            bold: cur_run_bold,
                            italic: cur_run_italic,
                            underline: cur_run_underline,
                            monospace: false,
                        });
                        cur_text.clear();
                    }
                } else if name.as_ref() == b"tc" {
                    in_tc = false;
                    current_row_cells.push(current_cell_text.trim().to_string());
                    current_cell_text.clear();
                } else if name.as_ref() == b"tr" {
                    in_tr = false;
                    if !current_row_cells.is_empty() {
                        current_table_rows.push(current_row_cells.clone());
                    }
                } else if name.as_ref() == b"tbl" {
                    in_tbl = false;
                    if !current_table_rows.is_empty() {
                        block_idx += 1;
                        blocks.push(SourceBlock {
                            block_id: format!("blk_{:04}", block_idx),
                            block_type: "table".to_string(),
                            plain_text: format!("Table with {} rows", current_table_rows.len()),
                            runs: vec![],
                            heading_level: None,
                            table_cells: Some(current_table_rows.clone()),
                        });
                    }
                } else if name.as_ref() == b"p" && !in_tbl {
                    in_p = false;
                    let full_text: String = cur_runs.iter().map(|r| r.text.as_str()).collect();
                    let trimmed = full_text.trim();
                    if !trimmed.is_empty() {
                        block_idx += 1;
                        let mut heading_level = None;
                        let mut block_type = "paragraph".to_string();

                        if let Some(ref st) = cur_p_style {
                            if st.contains("Heading1") || st == "1" {
                                heading_level = Some(1);
                                block_type = "heading".to_string();
                            } else if st.contains("Heading2") || st == "2" {
                                heading_level = Some(2);
                                block_type = "heading".to_string();
                            } else if st.contains("Heading3") || st == "3" {
                                heading_level = Some(3);
                                block_type = "heading".to_string();
                            }
                        }

                        blocks.push(SourceBlock {
                            block_id: format!("blk_{:04}", block_idx),
                            block_type,
                            plain_text: full_text,
                            runs: cur_runs.clone(),
                            heading_level,
                            table_cells: None,
                        });
                    }
                }
            }
            Event::Eof => break,
            _ => {}
        }
        buf.clear();
    }

    Ok(blocks)
}

