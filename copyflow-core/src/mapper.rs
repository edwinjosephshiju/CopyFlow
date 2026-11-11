use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use crate::schema::{LayoutBlueprint, MappingDirective, SourceBlock};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CompactBlueprintSummary {
    pub page_w: u32,
    pub page_h: u32,
    pub margins: [u32; 4],
    pub styles: BTreeMap<String, String>, // style_id -> summary string
    pub tables_count: usize,
}

pub trait NpuInferenceBackend {
    fn infer_directives(
        &self,
        blueprint_prefix_json: &str,
        source_chunk: &[SourceBlock],
    ) -> Result<Vec<MappingDirective>, String>;
}

/// Fallback and local rule-backed heuristic backend for deterministic execution & testing
pub struct LocalHeuristicBackend;

impl NpuInferenceBackend for LocalHeuristicBackend {
    fn infer_directives(
        &self,
        _blueprint_prefix_json: &str,
        source_chunk: &[SourceBlock],
    ) -> Result<Vec<MappingDirective>, String> {
        let mut directives = Vec::with_capacity(source_chunk.len());

        for block in source_chunk {
            let directive = match block.block_type.as_str() {
                "heading" => {
                    let level = block.heading_level.unwrap_or(1);
                    let target_style_id = match level {
                        1 => "Heading1".to_string(),
                        2 => "Heading2".to_string(),
                        _ => "Heading3".to_string(),
                    };
                    MappingDirective {
                        source_block_id: block.block_id.clone(),
                        semantic_role: format!("heading_level_{}", level),
                        target_style_id,
                        preserve_inline_runs: true,
                        page_break_before: level == 1,
                        keep_next: true,
                        table_template_index: None,
                        confidence: 0.99,
                    }
                }
                "table" => MappingDirective {
                    source_block_id: block.block_id.clone(),
                    semantic_role: "data_table".to_string(),
                    target_style_id: "TableGrid".to_string(),
                    preserve_inline_runs: false,
                    page_break_before: false,
                    keep_next: false,
                    table_template_index: Some(0),
                    confidence: 0.98,
                },
                _ => MappingDirective {
                    source_block_id: block.block_id.clone(),
                    semantic_role: "body_paragraph".to_string(),
                    target_style_id: "Normal".to_string(),
                    preserve_inline_runs: true,
                    page_break_before: false,
                    keep_next: false,
                    table_template_index: None,
                    confidence: 0.95,
                },
            };
            directives.push(directive);
        }

        Ok(directives)
    }
}

pub struct PinnedPrefixMapper {
    pub max_prefix_tokens: usize, // 1024
    pub max_source_window_tokens: usize, // 2048
}

impl Default for PinnedPrefixMapper {
    fn default() -> Self {
        Self {
            max_prefix_tokens: 1024,
            max_source_window_tokens: 2048,
        }
    }
}

impl PinnedPrefixMapper {
    pub fn new() -> Self {
        Self::default()
    }

    /// Creates a compact blueprint summary guaranteed to fit in <= 1024 tokens (~4000 characters)
    pub fn build_pinned_prefix(&self, blueprint: &LayoutBlueprint) -> String {
        let mut style_summaries = BTreeMap::new();
        for (id, style) in &blueprint.styles {
            let summary = format!(
                "sz={} font={} bold={} align={}",
                style.font_size_half_pts.unwrap_or(24),
                style.font_name.as_deref().unwrap_or("Calibri"),
                style.bold,
                style.alignment.as_deref().unwrap_or("left")
            );
            style_summaries.insert(id.clone(), summary);
        }

        let summary = CompactBlueprintSummary {
            page_w: blueprint.page_geometry.width_dxa,
            page_h: blueprint.page_geometry.height_dxa,
            margins: [
                blueprint.page_geometry.margin_top_dxa,
                blueprint.page_geometry.margin_bottom_dxa,
                blueprint.page_geometry.margin_left_dxa,
                blueprint.page_geometry.margin_right_dxa,
            ],
            styles: style_summaries,
            tables_count: blueprint.tables.len(),
        };

        serde_json::to_string(&summary).unwrap_or_else(|_| "{}".to_string())
    }

    /// Partitions source blocks into token windows (approx 4 chars per token)
    pub fn slice_source_windows<'a>(&self, blocks: &'a [SourceBlock]) -> Vec<Vec<SourceBlock>> {
        let mut windows = Vec::new();
        let mut current_window = Vec::new();
        let mut current_chars = 0;
        let max_window_chars = self.max_source_window_tokens * 4;

        for block in blocks {
            let block_chars = block.plain_text.len() + 100;
            if current_chars + block_chars > max_window_chars && !current_window.is_empty() {
                windows.push(current_window);
                current_window = Vec::new();
                current_chars = 0;
            }
            current_chars += block_chars;
            current_window.push(block.clone());
        }

        if !current_window.is_empty() {
            windows.push(current_window);
        }

        windows
    }

    /// Map source blocks using the provided NPU backend
    pub fn map_document(
        &self,
        blueprint: &LayoutBlueprint,
        blocks: &[SourceBlock],
        backend: &dyn NpuInferenceBackend,
    ) -> Result<Vec<MappingDirective>, String> {
        let prefix_json = self.build_pinned_prefix(blueprint);
        let windows = self.slice_source_windows(blocks);

        let mut all_directives = Vec::with_capacity(blocks.len());
        for window in windows {
            let directives = backend.infer_directives(&prefix_json, &window)?;
            for mut dir in directives {
                // Ensure target style exists in blueprint
                if !blueprint.styles.contains_key(&dir.target_style_id) {
                    if blueprint.styles.contains_key("Normal") {
                        dir.target_style_id = "Normal".to_string();
                    } else if let Some(first_key) = blueprint.styles.keys().next() {
                        dir.target_style_id = first_key.clone();
                    }
                }
                all_directives.push(dir);
            }
        }

        Ok(all_directives)
    }
}

