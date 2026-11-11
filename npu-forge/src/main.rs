use std::env;
use std::fs;
use std::path::PathBuf;
use npu_forge::hardware_profile::HardwareProfile;
use npu_forge::memory_planner::ModelDimensions;
use npu_forge::onnx_optimizer::validate_onnx_model;

fn print_usage() {
    println!("Usage:");
    println!("  npu-forge validate --onnx <path_to_onnx> --out <path_to_manifest>");
    println!("  npu-forge plan");
}

fn main() {
    let args: Vec<String> = env::args().collect();
    if args.len() < 2 {
        print_usage();
        std::process::exit(1);
    }

    let command = &args[1];
    match command.as_str() {
        "plan" => {
            let profile = HardwareProfile::snapdragon_8_gen_2();
            let dims = ModelDimensions::dltm_360m();
            match npu_forge::memory_planner::compute_memory_plan(&dims, &profile) {
                Ok(report) => {
                    let json = serde_json::to_string_pretty(&report).unwrap();
                    println!("{}", json);
                }
                Err(e) => {
                    eprintln!("Error computing memory plan: {}", e);
                    std::process::exit(1);
                }
            }
        }
        "validate" => {
            let mut onnx_path = PathBuf::from("models/dltm_360m_w4a8.onnx");
            let mut out_path = PathBuf::from("models/memory_manifest.json");

            let mut i = 2;
            while i < args.len() {
                match args[i].as_str() {
                    "--onnx" => {
                        if i + 1 < args.len() {
                            onnx_path = PathBuf::from(&args[i + 1]);
                            i += 2;
                        } else {
                            eprintln!("Missing argument for --onnx");
                            std::process::exit(1);
                        }
                    }
                    "--out" => {
                        if i + 1 < args.len() {
                            out_path = PathBuf::from(&args[i + 1]);
                            i += 2;
                        } else {
                            eprintln!("Missing argument for --out");
                            std::process::exit(1);
                        }
                    }
                    other => {
                        eprintln!("Unknown argument: {}", other);
                        print_usage();
                        std::process::exit(1);
                    }
                }
            }

            println!("Validating ONNX model: {}", onnx_path.display());
            let profile = HardwareProfile::snapdragon_8_gen_2();
            let dims = ModelDimensions::dltm_360m();

            match validate_onnx_model(&onnx_path, &dims, &profile) {
                Ok(report) => {
                    println!("\n=== NPU-FORGE HARDWARE VALIDATION REPORT ===");
                    println!("Target SoC:              {}", profile.soc_name);
                    println!("HTP Architecture:        {}", profile.htp_arch);
                    println!("ONNX File Size:          {:.2} MiB", report.file_size_bytes as f64 / (1024.0 * 1024.0));
                    println!("SHA-256 Digest:          {}", report.sha256_hex);
                    println!("Total LPDDR5X Budget:    {:.2} MiB", report.memory_plan.total_allocated_bytes as f64 / (1024.0 * 1024.0));
                    println!("LPDDR5X Hard Ceiling:    {:.2} MiB", report.memory_plan.hard_limit_bytes as f64 / (1024.0 * 1024.0));
                    println!("Safety Margin:           {:.2} MiB", report.memory_plan.safety_guardband_bytes as f64 / (1024.0 * 1024.0));
                    println!("Fits In 500MB Budget:    {}", report.memory_plan.fits_in_budget);
                    println!("Zero DRAM Spill in VTCM: {}", report.vtcm_schedule.zero_dram_spill_verified);
                    println!("Forbidden Ops Detected:  {:?}", report.forbidden_ops_detected);
                    println!("Operator Audit Passed:   {}", report.passed_operator_audit);

                    if let Some(parent) = out_path.parent() {
                        let _ = fs::create_dir_all(parent);
                    }
                    let manifest_json = serde_json::to_string_pretty(&report).unwrap();
                    if let Err(e) = fs::write(&out_path, &manifest_json) {
                        eprintln!("Failed to write manifest to {}: {}", out_path.display(), e);
                        std::process::exit(1);
                    }
                    println!("\nManifest successfully written to: {}", out_path.display());
                }
                Err(e) => {
                    eprintln!("Validation FAILED: {}", e);
                    std::process::exit(1);
                }
            }
        }
        unknown => {
            eprintln!("Unknown command: {}", unknown);
            print_usage();
            std::process::exit(1);
        }
    }
}

