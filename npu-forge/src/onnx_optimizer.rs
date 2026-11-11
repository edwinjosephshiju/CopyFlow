use std::fs::File;
use std::io::Read;
use std::path::Path;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::hardware_profile::HardwareProfile;
use crate::memory_planner::{compute_memory_plan, MemoryPlanReport, ModelDimensions};
use crate::vtcm_simulator::{simulate_vtcm_tiling, VtcmScheduleReport};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OnnxValidationReport {
    pub file_path: String,
    pub file_size_bytes: u64,
    pub sha256_hex: String,
    pub forbidden_ops_detected: Vec<String>,
    pub passed_operator_audit: bool,
    pub matches_hardware_budget: bool,
    pub memory_plan: MemoryPlanReport,
    pub vtcm_schedule: VtcmScheduleReport,
}

pub fn validate_onnx_model(
    onnx_path: &Path,
    dims: &ModelDimensions,
    profile: &HardwareProfile,
) -> Result<OnnxValidationReport, String> {
    if !onnx_path.exists() {
        return Err(format!("ONNX model file not found at: {}", onnx_path.display()));
    }

    let mut file = File::open(onnx_path)
        .map_err(|e| format!("Failed to open ONNX file {}: {}", onnx_path.display(), e))?;

    let file_size_bytes = file
        .metadata()
        .map_err(|e| format!("Failed to read metadata for {}: {}", onnx_path.display(), e))?
        .len();

    let mut hasher = Sha256::new();
    let mut buffer = [0u8; 64 * 1024];
    let mut forbidden_detected = Vec::new();

    // Read and hash the entire file while scanning for forbidden ops in chunks
    let mut window = Vec::new();
    loop {
        let bytes_read = file
            .read(&mut buffer)
            .map_err(|e| format!("Error reading ONNX file: {}", e))?;
        if bytes_read == 0 {
            break;
        }
        hasher.update(&buffer[..bytes_read]);

        // Keep a rolling window to detect forbidden ops across chunk boundaries
        window.extend_from_slice(&buffer[..bytes_read]);
        if window.len() > 128 * 1024 {
            let drain_len = window.len() - 64 * 1024;
            window.drain(..drain_len);
        }

        for op in &profile.forbidden_ops {
            let op_bytes = op.as_bytes();
            if window.windows(op_bytes.len()).any(|w| w == op_bytes) {
                if !forbidden_detected.contains(op) {
                    forbidden_detected.push(op.clone());
                }
            }
        }
    }

    let sha256_hex = format!("{:x}", hasher.finalize());

    // Validate memory plan and VTCM schedule
    let memory_plan = compute_memory_plan(dims, profile)?;
    let vtcm_schedule = simulate_vtcm_tiling(dims, profile)?;

    let passed_operator_audit = forbidden_detected.is_empty();
    let matches_hardware_budget = memory_plan.fits_in_budget && vtcm_schedule.zero_dram_spill_verified;

    if !passed_operator_audit {
        return Err(format!(
            "ONNX model rejected: detected forbidden dynamic operators: {:?}",
            forbidden_detected
        ));
    }

    Ok(OnnxValidationReport {
        file_path: onnx_path.display().to_string(),
        file_size_bytes,
        sha256_hex,
        forbidden_ops_detected: forbidden_detected,
        passed_operator_audit,
        matches_hardware_budget,
        memory_plan,
        vtcm_schedule,
    })
}

