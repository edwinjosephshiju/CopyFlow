use crate::schema::{LayoutBlueprint, MappingDirective, SourceBlock, VerificationReport};

pub fn verify_and_fix_pagination(
    blueprint: &LayoutBlueprint,
    blocks: &[SourceBlock],
    directives: &mut [MappingDirective],
) -> VerificationReport {
    let page_h = blueprint.page_geometry.height_dxa;
    let page_w = blueprint.page_geometry.width_dxa;
    let m_top = blueprint.page_geometry.margin_top_dxa;
    let m_bottom = blueprint.page_geometry.margin_bottom_dxa;
    let m_left = blueprint.page_geometry.margin_left_dxa;
    let m_right = blueprint.page_geometry.margin_right_dxa;
    let cols = blueprint.page_geometry.columns.max(1);

    let h_usable = page_h.saturating_sub(m_top + m_bottom).max(1000);
    let w_usable = (page_w.saturating_sub(m_left + m_right) / cols).max(1000);

    let mut orphan_headings_fixed = 0;
    let mut pagination_fixes_applied = 0;

    // Pass 1: Metric accumulation & detection
    let mut current_y: u32 = 0;
    let mut estimated_pages: usize = 1;

    for i in 0..blocks.len() {
        let block = &blocks[i];
        let directive = &directives[i];

        if directive.page_break_before {
            current_y = 0;
            estimated_pages += 1;
        }

        let style = blueprint.styles.get(&directive.target_style_id);
        let font_half_pts = style.and_then(|s| s.font_size_half_pts).unwrap_or(24);
        let line_height_dxa = (font_half_pts as f32 * 10.0 * 1.2) as u32; // ~288 dxa for 12pt
        let space_before = style.and_then(|s| s.space_before_dxa).unwrap_or(0);
        let space_after = style.and_then(|s| s.space_after_dxa).unwrap_or(160);

        let block_height_dxa = if block.block_type == "table" {
            let row_count = block.table_cells.as_ref().map(|r| r.len()).unwrap_or(1) as u32;
            row_count * 400 + 200
        } else {
            // Estimate number of wrapped lines based on usable width
            // Approx 120 dxa advance per character for typical 12pt proportional text
            let total_chars = block.plain_text.len().max(1);
            let approx_line_chars = (w_usable / 120).max(10) as usize;
            let lines = ((total_chars + approx_line_chars - 1) / approx_line_chars) as u32;
            space_before + (lines * line_height_dxa) + space_after
        };

        // Check for orphan heading in Pass 1 / Pass 2
        // Bottom 12% threshold = 0.88 * h_usable
        let orphan_threshold = (h_usable as f32 * 0.88) as u32;
        if block.block_type == "heading" && current_y > orphan_threshold {
            // Fix orphan heading: mutate directive
            directives[i].keep_next = true;
            directives[i].page_break_before = true;
            orphan_headings_fixed += 1;
            pagination_fixes_applied += 1;

            // Reset current page Y position
            current_y = block_height_dxa;
            estimated_pages += 1;
        } else {
            if current_y + block_height_dxa > h_usable {
                // Natural page overflow
                current_y = block_height_dxa;
                estimated_pages += 1;
            } else {
                current_y += block_height_dxa;
            }
        }
    }

    let source_block_count = blocks.len();
    let cloned_block_count = directives.len();
    let content_completeness_ratio = if source_block_count == 0 {
        1.0
    } else {
        cloned_block_count as f32 / source_block_count as f32
    };

    let passed_qa = content_completeness_ratio >= 1.0;

    VerificationReport {
        source_block_count,
        cloned_block_count,
        content_completeness_ratio,
        estimated_page_count: estimated_pages,
        pagination_fixes_applied,
        orphan_headings_fixed,
        passed_qa,
    }
}

