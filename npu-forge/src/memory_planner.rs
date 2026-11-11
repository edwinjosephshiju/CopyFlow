use serde::{Deserialize, Serialize};
use crate::hardware_profile::HardwareProfile;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelDimensions {
    pub num_layers: usize,        // 24
    pub hidden_dim: usize,        // 1024
    pub ffn_dim: usize,           // 2816
    pub num_q_heads: usize,       // 16
    pub num_kv_heads: usize,      // 4
    pub head_dim: usize,          // 64
    pub vocab_size: usize,        // 32768
    pub max_seq_len: usize,       // 4096
    pub prefill_chunk_len: usize, // 512
    pub weight_group_size: usize, // 128
}

impl ModelDimensions {
    pub fn dltm_360m() -> Self {
        Self {
            num_layers: 24,
            hidden_dim: 1024,
            ffn_dim: 2816,
            num_q_heads: 16,
            num_kv_heads: 4,
            head_dim: 64,
            vocab_size: 32768,
            max_seq_len: 4096,
            prefill_chunk_len: 512,
            weight_group_size: 128,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MemoryPlanReport {
    pub total_params: usize,
    pub weights_bytes: usize,
    pub scales_bytes: usize,
    pub kv_cache_bytes: usize,
    pub activation_arena_bytes: usize,
    pub qnn_context_bytes: usize,
    pub rust_doc_arena_bytes: usize,
    pub total_allocated_bytes: usize,
    pub hard_limit_bytes: usize,
    pub safety_guardband_bytes: usize,
    pub fits_in_budget: bool,
}

pub fn compute_memory_plan(
    dims: &ModelDimensions,
    profile: &HardwareProfile,
) -> Result<MemoryPlanReport, String> {
    if dims.ffn_dim % profile.hvx_vector_lanes_bytes != 0 {
        return Err(format!(
            "Architecture violation: ffn_dim ({}) must be a multiple of HVX vector lanes ({} bytes) for HTP alignment",
            dims.ffn_dim, profile.hvx_vector_lanes_bytes
        ));
    }

    // Tied token embeddings
    let tied_embedding_params = dims.vocab_size * dims.hidden_dim;

    // Per-layer attention: W_q, W_k, W_v, W_o
    let q_dim = dims.num_q_heads * dims.head_dim;
    let kv_dim = dims.num_kv_heads * dims.head_dim;
    let per_layer_attn = (dims.hidden_dim * q_dim)
        + (dims.hidden_dim * kv_dim)
        + (dims.hidden_dim * kv_dim)
        + (q_dim * dims.hidden_dim);

    // Per-layer SwiGLU FFN: W_gate, W_up, W_down
    let per_layer_ffn = 3 * (dims.hidden_dim * dims.ffn_dim);

    // Per-layer RMSNorm: attn_norm + ffn_norm
    let per_layer_norm = 2 * dims.hidden_dim;

    // Final output norm
    let final_norm = dims.hidden_dim;

    let total_params = tied_embedding_params
        + dims.num_layers * (per_layer_attn + per_layer_ffn + per_layer_norm)
        + final_norm;

    // INT4 weights: 0.5 bytes per parameter
    let weights_bytes = (total_params + 1) / 2;

    // FP16 scale (2 bytes) per weight group (typically 128)
    let num_groups = (total_params + dims.weight_group_size - 1) / dims.weight_group_size;
    let scales_bytes = num_groups * 2;

    // 4096-token INT8 KV cache: 2 * L * n_kv * d_k * S * 1 byte
    let kv_cache_bytes = 2
        * dims.num_layers
        * dims.num_kv_heads
        * dims.head_dim
        * dims.max_seq_len;

    // Fixed arenas reserved for HTP runtime execution
    let activation_arena_bytes = 64 * 1024 * 1024; // 64 MiB
    let qnn_context_bytes = 56 * 1024 * 1024;      // 56 MiB
    let rust_doc_arena_bytes = 96 * 1024 * 1024;   // 96 MiB

    let total_allocated_bytes = weights_bytes
        + scales_bytes
        + kv_cache_bytes
        + activation_arena_bytes
        + qnn_context_bytes
        + rust_doc_arena_bytes;

    let hard_limit_bytes = profile.max_lpddr5x_budget_bytes;
    let fits_in_budget = total_allocated_bytes <= hard_limit_bytes;

    if !fits_in_budget {
        return Err(format!(
            "Memory budget exceeded: Total allocated {} bytes ({} MiB) exceeds hard ceiling of {} bytes ({} MiB)",
            total_allocated_bytes,
            total_allocated_bytes / (1024 * 1024),
            hard_limit_bytes,
            hard_limit_bytes / (1024 * 1024)
        ));
    }

    let safety_guardband_bytes = hard_limit_bytes.saturating_sub(total_allocated_bytes);

    Ok(MemoryPlanReport {
        total_params,
        weights_bytes,
        scales_bytes,
        kv_cache_bytes,
        activation_arena_bytes,
        qnn_context_bytes,
        rust_doc_arena_bytes,
        total_allocated_bytes,
        hard_limit_bytes,
        safety_guardband_bytes,
        fits_in_budget,
    })
}

