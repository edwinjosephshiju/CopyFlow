use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum SocTarget {
    Snapdragon8Gen2,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HardwareProfile {
    pub target: SocTarget,
    pub soc_name: String,
    pub htp_arch: String,
    pub physical_vtcm_bytes: usize,
    pub usable_vtcm_bytes: usize,
    pub hvx_vector_lanes_bytes: usize,
    pub max_lpddr5x_budget_bytes: usize,
    pub supported_ops: Vec<String>,
    pub forbidden_ops: Vec<String>,
}

impl HardwareProfile {
    pub fn snapdragon_8_gen_2() -> Self {
        Self {
            target: SocTarget::Snapdragon8Gen2,
            soc_name: "SM8550_Snapdragon_8_Gen_2".to_string(),
            htp_arch: "v73".to_string(),
            physical_vtcm_bytes: 8 * 1024 * 1024,      // 8,388,608 bytes
            usable_vtcm_bytes: 6 * 1024 * 1024,        // 6,291,456 bytes
            hvx_vector_lanes_bytes: 128,              // 128-byte vector alignment
            max_lpddr5x_budget_bytes: 500 * 1024 * 1024, // 524,288,000 bytes (500 MiB)
            supported_ops: vec![
                "MatMul".to_string(),
                "Gemm".to_string(),
                "QLinearMatMul".to_string(),
                "DynamicQuantizeLinear".to_string(),
                "DequantizeLinear".to_string(),
                "QuantizeLinear".to_string(),
                "Add".to_string(),
                "Mul".to_string(),
                "Sigmoid".to_string(),
                "Softmax".to_string(),
                "SimplifiedLayerNormalization".to_string(),
                "SkipSimplifiedLayerNormalization".to_string(),
                "RotaryEmbedding".to_string(),
                "Reshape".to_string(),
                "Transpose".to_string(),
                "Concat".to_string(),
                "Slice".to_string(),
                "Gather".to_string(),
                "Cast".to_string(),
                "Where".to_string(),
            ],
            forbidden_ops: vec![
                "Loop".to_string(),
                "Scan".to_string(),
                "If".to_string(),
                "NonMaxSuppression".to_string(),
                "StringNormalizer".to_string(),
            ],
        }
    }
}

