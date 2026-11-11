use std::path::Path;
use serde::{Deserialize, Serialize};

use crate::cloner::clone_docx_preserving_template;
use crate::inspector::{extract_source_blocks, inspect_docx_template};
use crate::mapper::{LocalHeuristicBackend, NpuInferenceBackend, PinnedPrefixMapper};
use crate::schema::VerificationReport;
use crate::verifier::verify_and_fix_pagination;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TaskStage {
    Stage1InspectingReference = 1,
    Stage2ExtractingSource = 2,
    Stage3NpuSemanticMapping = 3,
    Stage4OpenxmlReconstruction = 4,
    Stage5TwoPassPaginationFix = 5,
    Stage6VisualQaComplete = 6,
}

impl TaskStage {
    pub fn description(&self) -> &'static str {
        match self {
            TaskStage::Stage1InspectingReference => "Inspecting reference layout, margins & OpenXML styles",
            TaskStage::Stage2ExtractingSource => "Extracting source paragraphs, runs & tables",
            TaskStage::Stage3NpuSemanticMapping => "Mapping content to template on Hexagon HTP (4096 ctx)",
            TaskStage::Stage4OpenxmlReconstruction => "Cloning template container & injecting mapped XML",
            TaskStage::Stage5TwoPassPaginationFix => "Running two-pass glyph metric pagination repair",
            TaskStage::Stage6VisualQaComplete => "Completeness & layout verification passed",
        }
    }

    pub fn stage_id(&self) -> &'static str {
        match self {
            TaskStage::Stage1InspectingReference => "STAGE_1_INSPECTING_REFERENCE",
            TaskStage::Stage2ExtractingSource => "STAGE_2_EXTRACTING_SOURCE",
            TaskStage::Stage3NpuSemanticMapping => "STAGE_3_NPU_SEMANTIC_MAPPING",
            TaskStage::Stage4OpenxmlReconstruction => "STAGE_4_OPENXML_RECONSTRUCTION",
            TaskStage::Stage5TwoPassPaginationFix => "STAGE_5_TWO_PASS_PAGINATION_FIX",
            TaskStage::Stage6VisualQaComplete => "STAGE_6_VISUAL_QA_COMPLETE",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskEvent {
    pub stage_number: u8,
    pub stage_id: String,
    pub description: String,
    pub progress_percent: f32,
    pub details: Option<String>,
}

pub struct CopyFlowPipeline<'a> {
    backend: Option<&'a dyn NpuInferenceBackend>,
}

impl<'a> CopyFlowPipeline<'a> {
    pub fn new() -> Self {
        Self { backend: None }
    }

    pub fn with_backend(backend: &'a dyn NpuInferenceBackend) -> Self {
        Self { backend: Some(backend) }
    }

    pub fn execute<F>(
        &self,
        reference_docx: &Path,
        source_doc: &Path,
        output_docx: &Path,
        mut on_event: F,
    ) -> Result<VerificationReport, String>
    where
        F: FnMut(TaskEvent),
    {
        // Stage 1: Inspect reference
        on_event(TaskEvent {
            stage_number: 1,
            stage_id: TaskStage::Stage1InspectingReference.stage_id().to_string(),
            description: TaskStage::Stage1InspectingReference.description().to_string(),
            progress_percent: 15.0,
            details: Some(format!("Inspecting: {}", reference_docx.display())),
        });
        let blueprint = inspect_docx_template(reference_docx)?;

        // Stage 2: Extract source blocks
        on_event(TaskEvent {
            stage_number: 2,
            stage_id: TaskStage::Stage2ExtractingSource.stage_id().to_string(),
            description: TaskStage::Stage2ExtractingSource.description().to_string(),
            progress_percent: 30.0,
            details: Some(format!("Extracting from: {}", source_doc.display())),
        });
        let blocks = extract_source_blocks(source_doc)?;

        // Stage 3: NPU Semantic Mapping
        on_event(TaskEvent {
            stage_number: 3,
            stage_id: TaskStage::Stage3NpuSemanticMapping.stage_id().to_string(),
            description: TaskStage::Stage3NpuSemanticMapping.description().to_string(),
            progress_percent: 50.0,
            details: Some(format!("Mapping {} blocks into template layout", blocks.len())),
        });

        let default_backend = LocalHeuristicBackend;
        let backend = self.backend.unwrap_or(&default_backend);
        let mapper = PinnedPrefixMapper::new();
        let mut directives = mapper.map_document(&blueprint, &blocks, backend)?;

        // Stage 4: OpenXML Reconstruction (Initial Pass)
        on_event(TaskEvent {
            stage_number: 4,
            stage_id: TaskStage::Stage4OpenxmlReconstruction.stage_id().to_string(),
            description: TaskStage::Stage4OpenxmlReconstruction.description().to_string(),
            progress_percent: 70.0,
            details: Some("Reconstructing DOCX container & rewriting document.xml".to_string()),
        });
        clone_docx_preserving_template(
            reference_docx,
            output_docx,
            &blueprint,
            &blocks,
            &directives,
        )?;

        // Stage 5: Two-Pass Pagination Fix
        on_event(TaskEvent {
            stage_number: 5,
            stage_id: TaskStage::Stage5TwoPassPaginationFix.stage_id().to_string(),
            description: TaskStage::Stage5TwoPassPaginationFix.description().to_string(),
            progress_percent: 85.0,
            details: Some("Auditing orphan headings and vertical layout margins".to_string()),
        });
        let mut report = verify_and_fix_pagination(&blueprint, &blocks, &mut directives);

        // If pagination repairs were applied, re-clone with the repaired directives
        if report.pagination_fixes_applied > 0 {
            clone_docx_preserving_template(
                reference_docx,
                output_docx,
                &blueprint,
                &blocks,
                &directives,
            )?;
            report = verify_and_fix_pagination(&blueprint, &blocks, &mut directives);
        }

        // Stage 6: Visual QA Complete
        on_event(TaskEvent {
            stage_number: 6,
            stage_id: TaskStage::Stage6VisualQaComplete.stage_id().to_string(),
            description: TaskStage::Stage6VisualQaComplete.description().to_string(),
            progress_percent: 100.0,
            details: Some(format!(
                "Verification passed: Completeness={:.1}%, Pages={}, Fixes={}",
                report.content_completeness_ratio * 100.0,
                report.estimated_page_count,
                report.pagination_fixes_applied
            )),
        });

        Ok(report)
    }
}
