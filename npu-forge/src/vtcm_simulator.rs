use serde::{Deserialize, Serialize};
use crate::hardware_profile::HardwareProfile;
use crate::memory_planner::ModelDimensions;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LayerVtcmSchedule {
    pub layer_index: usize,
    pub ffn_weight_tile_bytes: usize,
    pub ffn_channel_tile_size: usize,
    pub attn_query_tile_tokens: usize,
    pub attn_tile_bytes: usize,
    pub activation_tile_bytes: usize,
    pub slot_capacity_bytes: usize,
    pub slot_utilization_ratio: f32,
    pub fits_in_slot: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VtcmScheduleReport {
    pub usable_vtcm_bytes: usize,
    pub ping_pong_slot_bytes: usize,
    pub optimal_query_tile_tokens: usize,
    pub optimal_ffn_channel_tile: usize,
    pub estimated_dma_transfers_per_token: usize,
    pub zero_dram_spill_verified: bool,
    pub layer_schedules: Vec<LayerVtcmSchedule>,
}

pub fn simulate_vtcm_tiling(
    dims: &ModelDimensions,
    profile: &HardwareProfile,
) -> Result<VtcmScheduleReport, String> {
    if profile.usable_vtcm_bytes < 2 * 1024 * 1024 {
        return Err("Usable VTCM must be at least 2 MiB for ping-pong buffering".to_string());
    }

    let ping_pong_slot_bytes = profile.usable_vtcm_bytes / 2; // 3 MiB (3,145,728 bytes)
    let half_slot_budget = ping_pong_slot_bytes / 2;          // 1.5 MiB (1,572,864 bytes)

    // Calculate maximum FFN channel tile along ffn_dim that fits inside half_slot_budget
    // INT4 weights: 0.5 B/weight + FP16 scale per 128 weights (2 / 128 = 0.015625 B/weight)
    let bytes_per_weight = 0.5f64 + (2.0 / dims.weight_group_size as f64);
    let bytes_per_ffn_channel = (dims.hidden_dim as f64 * bytes_per_weight) as usize;

    let mut optimal_ffn_channel_tile = half_slot_budget / bytes_per_ffn_channel;
    // Align down to 128-byte HVX vector lanes
    let hvx_lanes = profile.hvx_vector_lanes_bytes;
    optimal_ffn_channel_tile = (optimal_ffn_channel_tile / hvx_lanes) * hvx_lanes;
    if optimal_ffn_channel_tile > dims.ffn_dim {
        optimal_ffn_channel_tile = dims.ffn_dim;
    }

    let ffn_weight_tile_bytes = optimal_ffn_channel_tile * bytes_per_ffn_channel;

    // Attention QK^T tile sizing
    // Formula from Spec 2.3: M_attn_tile = num_kv_heads * q_tile * kv_step * 2 bytes <= 1.5 MiB
    let kv_step = dims.prefill_chunk_len; // 512
    let mut optimal_query_tile_tokens = 256;
    let mut attn_tile_bytes = dims.num_kv_heads * optimal_query_tile_tokens * kv_step * 2;

    while attn_tile_bytes > half_slot_budget && optimal_query_tile_tokens > 16 {
        optimal_query_tile_tokens /= 2;
        attn_tile_bytes = dims.num_kv_heads * optimal_query_tile_tokens * kv_step * 2;
    }

    if attn_tile_bytes > half_slot_budget {
        return Err(format!(
            "Cannot fit attention tile in half-slot budget: {} > {}",
            attn_tile_bytes, half_slot_budget
        ));
    }

    let mut layer_schedules = Vec::with_capacity(dims.num_layers);
    let mut all_fit = true;

    for layer_idx in 0..dims.num_layers {
        let activation_tile_bytes = attn_tile_bytes.max(optimal_query_tile_tokens * dims.hidden_dim * 2);
        let total_tile_usage = ffn_weight_tile_bytes + activation_tile_bytes;
        let fits = total_tile_usage <= ping_pong_slot_bytes;
        if !fits {
            all_fit = false;
        }

        layer_schedules.push(LayerVtcmSchedule {
            layer_index: layer_idx,
            ffn_weight_tile_bytes,
            ffn_channel_tile_size: optimal_ffn_channel_tile,
            attn_query_tile_tokens: optimal_query_tile_tokens,
            attn_tile_bytes,
            activation_tile_bytes,
            slot_capacity_bytes: ping_pong_slot_bytes,
            slot_utilization_ratio: total_tile_usage as f32 / ping_pong_slot_bytes as f32,
            fits_in_slot: fits,
        });
    }

    if !all_fit {
        return Err("VTCM ping-pong scheduling failed: tile exceeded 3 MiB slot".to_string());
    }

    // Estimated DMA transfers per token: (Attention Q/K/V + FFN Gate/Up/Down weights per layer) * 2 slots
    let estimated_dma_transfers_per_token = dims.num_layers * 6;

    Ok(VtcmScheduleReport {
        usable_vtcm_bytes: profile.usable_vtcm_bytes,
        ping_pong_slot_bytes,
        optimal_query_tile_tokens,
        optimal_ffn_channel_tile,
        estimated_dma_transfers_per_token,
        zero_dram_spill_verified: true,
        layer_schedules,
    })
}

