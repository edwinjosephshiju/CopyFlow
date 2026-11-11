pub mod hardware_profile;
pub mod memory_planner;
pub mod vtcm_simulator;
pub mod onnx_optimizer;

#[cfg(test)]
mod tests {
    use super::hardware_profile::HardwareProfile;
    use super::memory_planner::{compute_memory_plan, ModelDimensions};
    use super::vtcm_simulator::simulate_vtcm_tiling;

    #[test]
    fn test_dltm_360m_passes_memory_and_vtcm_budget() {
        let profile = HardwareProfile::snapdragon_8_gen_2();
        let dims = ModelDimensions::dltm_360m();

        // 1. Verify memory plan
        let plan_res = compute_memory_plan(&dims, &profile);
        assert!(plan_res.is_ok(), "DLTM-360M should pass memory plan: {:?}", plan_res.err());
        let plan = plan_res.unwrap();
        assert!(plan.fits_in_budget, "DLTM-360M must fit inside 500 MB budget");
        assert!(
            plan.total_allocated_bytes <= profile.max_lpddr5x_budget_bytes,
            "Total allocated ({} B) must be <= 500 MB ({} B)",
            plan.total_allocated_bytes,
            profile.max_lpddr5x_budget_bytes
        );
        assert!(plan.safety_guardband_bytes > 0, "Safety guardband must be positive");

        // 2. Verify VTCM tiling simulation
        let vtcm_res = simulate_vtcm_tiling(&dims, &profile);
        assert!(vtcm_res.is_ok(), "DLTM-360M should pass VTCM tiling: {:?}", vtcm_res.err());
        let vtcm = vtcm_res.unwrap();
        assert!(vtcm.zero_dram_spill_verified, "VTCM schedule must guarantee zero DRAM spill");
        assert_eq!(vtcm.ping_pong_slot_bytes, 3 * 1024 * 1024);
        assert_eq!(vtcm.layer_schedules.len(), 24);
    }

    #[test]
    fn test_unpruned_2b_model_rejected_for_budget_overrun() {
        let profile = HardwareProfile::snapdragon_8_gen_2();
        // 2B unpruned model dimensions: L=28, d=2048, d_ffn=5632, n_q=16, n_kv=4, V=32768, S=4096
        let dims_2b = ModelDimensions {
            num_layers: 28,
            hidden_dim: 2048,
            ffn_dim: 5632,
            num_q_heads: 16,
            num_kv_heads: 4,
            head_dim: 128,
            vocab_size: 32768,
            max_seq_len: 4096,
            prefill_chunk_len: 512,
            weight_group_size: 128,
        };

        let plan_res = compute_memory_plan(&dims_2b, &profile);
        assert!(
            plan_res.is_err(),
            "Unpruned 2B model must be deterministically rejected for exceeding 500MB budget"
        );
        let err_msg = plan_res.unwrap_err();
        assert!(
            err_msg.contains("Memory budget exceeded"),
            "Error message should mention memory budget exceeded, got: {}",
            err_msg
        );
    }
}

