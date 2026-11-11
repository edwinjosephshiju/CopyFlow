use std::path::Path;
use std::sync::Mutex;
use once_cell::sync::Lazy;

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

use copyflow_core::pipeline::{CopyFlowPipeline, TaskEvent};
use npu_forge::hardware_profile::HardwareProfile;
use npu_forge::memory_planner::{compute_memory_plan, ModelDimensions};
use npu_forge::vtcm_simulator::simulate_vtcm_tiling;

static LAST_TASK_EVENTS: Lazy<Mutex<Vec<TaskEvent>>> = Lazy::new(|| Mutex::new(Vec::new()));

#[no_mangle]
pub extern "system" fn Java_com_copyflow_app_bridge_CopyFlowBridge_nativeValidateHardwareBudget(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let profile = HardwareProfile::snapdragon_8_gen_2();
    let dims = ModelDimensions::dltm_360m();

    let plan_res = compute_memory_plan(&dims, &profile);
    let vtcm_res = simulate_vtcm_tiling(&dims, &profile);

    let (fits, total_bytes, guardband_bytes, zero_spill, err_msg) = match (plan_res, vtcm_res) {
        (Ok(plan), Ok(vtcm)) => (
            plan.fits_in_budget,
            plan.total_allocated_bytes,
            plan.safety_guardband_bytes,
            vtcm.zero_dram_spill_verified,
            None,
        ),
        (Err(e), _) => (false, 0, 0, false, Some(e)),
        (_, Err(e)) => (false, 0, 0, false, Some(e)),
    };

    let response = serde_json::json!({
        "targetSoc": profile.soc_name,
        "htpArch": profile.htp_arch,
        "fitsInBudget": fits,
        "totalAllocatedBytes": total_bytes,
        "hardLimitBytes": profile.max_lpddr5x_budget_bytes,
        "safetyGuardbandBytes": guardband_bytes,
        "zeroDramSpill": zero_spill,
        "errorMessage": err_msg
    });

    let json_str = response.to_string();
    env.new_string(json_str)
        .expect("Failed to create Java string")
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_copyflow_app_bridge_CopyFlowBridge_nativeRunClonePipeline(
    mut env: JNIEnv,
    _class: JClass,
    template_path: JString,
    source_path: JString,
    output_path: JString,
) -> jstring {
    let t_str: String = env.get_string(&template_path).map(|s| s.into()).unwrap_or_default();
    let s_str: String = env.get_string(&source_path).map(|s| s.into()).unwrap_or_default();
    let o_str: String = env.get_string(&output_path).map(|s| s.into()).unwrap_or_default();

    if let Ok(mut events) = LAST_TASK_EVENTS.lock() {
        events.clear();
    }

    let pipeline = CopyFlowPipeline::new();
    let result = pipeline.execute(
        Path::new(&t_str),
        Path::new(&s_str),
        Path::new(&o_str),
        |event| {
            if let Ok(mut events) = LAST_TASK_EVENTS.lock() {
                events.push(event);
            }
        },
    );

    let response = match result {
        Ok(report) => serde_json::json!({
            "success": true,
            "report": report,
            "errorMessage": null
        }),
        Err(e) => serde_json::json!({
            "success": false,
            "report": null,
            "errorMessage": e
        }),
    };

    let json_str = response.to_string();
    env.new_string(json_str)
        .expect("Failed to create Java string")
        .into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_copyflow_app_bridge_CopyFlowBridge_nativeGetLastTaskEventsJson(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let events = LAST_TASK_EVENTS.lock().map(|e| e.clone()).unwrap_or_default();
    let json_str = serde_json::to_string(&events).unwrap_or_else(|_| "[]".to_string());

    env.new_string(json_str)
        .expect("Failed to create Java string")
        .into_raw()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_bridge_direct_validation() {
        let profile = HardwareProfile::snapdragon_8_gen_2();
        let dims = ModelDimensions::dltm_360m();
        let plan = compute_memory_plan(&dims, &profile).expect("Memory plan should succeed");
        assert!(plan.fits_in_budget);
    }
}
