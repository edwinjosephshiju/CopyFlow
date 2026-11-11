use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PageGeometry {
    pub width_dxa: u32,       // e.g. 12240 (8.5 inches)
    pub height_dxa: u32,      // e.g. 15840 (11 inches)
    pub margin_top_dxa: u32,
    pub margin_bottom_dxa: u32,
    pub margin_left_dxa: u32,
    pub margin_right_dxa: u32,
    pub header_dxa: u32,
    pub footer_dxa: u32,
    pub columns: u32,
    pub orientation: String,  // "portrait" | "landscape"
}

impl Default for PageGeometry {
    fn default() -> Self {
        Self {
            width_dxa: 12240,
            height_dxa: 15840,
            margin_top_dxa: 1440,
            margin_bottom_dxa: 1440,
            margin_left_dxa: 1440,
            margin_right_dxa: 1440,
            header_dxa: 720,
            footer_dxa: 720,
            columns: 1,
            orientation: "portrait".to_string(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct StyleBlueprint {
    pub style_id: String,
    pub name: String,
    pub style_type: String,   // "paragraph" | "character" | "table"
    pub based_on: Option<String>,
    pub font_name: Option<String>,
    pub font_size_half_pts: Option<u32>,
    pub bold: bool,
    pub italic: bool,
    pub color_hex: Option<String>,
    pub alignment: Option<String>,
    pub space_before_dxa: Option<u32>,
    pub space_after_dxa: Option<u32>,
    pub line_spacing_dxa: Option<u32>,
    pub keep_next: bool,
    pub page_break_before: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct TableBlueprint {
    pub template_table_index: usize,
    pub style_id: Option<String>,
    pub grid_col_widths_dxa: Vec<u32>,
    pub has_header_row: bool,
    pub border_summary: String,
    pub header_shading_fill: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LayoutBlueprint {
    pub template_format: String, // "docx" | "pdf"
    pub page_geometry: PageGeometry,
    pub styles: BTreeMap<String, StyleBlueprint>,
    pub tables: Vec<TableBlueprint>,
    pub has_headers: bool,
    pub has_footers: bool,
    pub raw_trailing_sect_pr_xml: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct InlineRun {
    pub text: String,
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub monospace: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SourceBlock {
    pub block_id: String,
    pub block_type: String, // "heading" | "paragraph" | "table" | "list_item" | "equation" | "figure_caption"
    pub plain_text: String,
    pub runs: Vec<InlineRun>,
    pub heading_level: Option<u8>,
    pub table_cells: Option<Vec<Vec<String>>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct MappingDirective {
    pub source_block_id: String,
    pub semantic_role: String,
    pub target_style_id: String,
    pub preserve_inline_runs: bool,
    pub page_break_before: bool,
    pub keep_next: bool,
    pub table_template_index: Option<usize>,
    pub confidence: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct VerificationReport {
    pub source_block_count: usize,
    pub cloned_block_count: usize,
    pub content_completeness_ratio: f32,
    pub estimated_page_count: usize,
    pub pagination_fixes_applied: usize,
    pub orphan_headings_fixed: usize,
    pub passed_qa: bool,
}
