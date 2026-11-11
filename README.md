# CopyFlow: On-Device NPU Document Layout Transfer Engine

> **Argon Task Mode Core** | Snapdragon 8 Gen 2 (`SM8550` Hexagon v73 HTP) | $500\text{ MB}$ LPDDR5X Budget | Pure Rust OpenXML Cloning

CopyFlow is an ultra-fast, local, privacy-preserving document layout transfer engine. It clones the exact 1:1 typography, margins, section geometry, header/footer bindings, table styles, and page break architecture of a reference template (`.docx` / `.pdf`) onto the text and tabular contents of an arbitrary source document.

---

## Key Architectural Highlights

1. **Hardware-Certified Snapdragon 8 Gen 2 Budget ($500\text{ MB}$ Hard Ceiling):**
   - **Quantization:** W4A8 (INT4 symmetric group-128 weights with FP16 scales, INT8 activations and KV cache).
   - **LPDDR5X Footprint:** $455.25\text{ MiB}$ allocated, providing a $\ge 44.75\text{ MiB}$ safety margin against Android Low Memory Killer (LMK).
   - **Hexagon v73 VTCM Tiling:** Micro-tiled inside $6\text{ MiB}$ usable on-chip VTCM via $3\text{ MiB}$ double-buffered ping-pong DMA slots with **zero external DRAM spill**.

2. **Non-Destructive Byte-for-Byte Container Surgery (`copyflow-core`):**
   - Never builds a `.docx` from scratch. Unpacks the reference template ZIP container and preserves `word/styles.xml`, `word/numbering.xml`, `word/_rels/document.xml.rels`, embedded fonts, headers, footers, and the trailing `<w:sectPr>` byte-verbatim.
   - Deterministically injects mapped source content while maintaining 100% OpenXML validity.

3. **Two-Pass Metric Pagination & Orphan Heading Repair:**
   - Evaluates cumulative vertical position using font sizes and line spacings.
   - Automatically injects `<w:pageBreakBefore w:val="1"/>` and `<w:keepNext w:val="1"/>` on headings that fall in the bottom $12\%$ of usable page height, preventing orphan headings.

4. **Argon 6-Stage Task Mode State Machine:**
   - Real-time progress events emitted via JNI Flow:
     - `STAGE_1_INSPECTING_REFERENCE`: Template layout, margins & styles
     - `STAGE_2_EXTRACTING_SOURCE`: Source paragraphs, runs & tables
     - `STAGE_3_NPU_SEMANTIC_MAPPING`: Hexagon HTP 4096-token mapping
     - `STAGE_4_OPENXML_RECONSTRUCTION`: Container surgery & XML injection
     - `STAGE_5_TWO_PASS_PAGINATION_FIX`: Glyph metric pagination repair
     - `STAGE_6_VISUAL_QA_COMPLETE`: Verified completeness & layout fidelity

---

## Repository Structure

```
CopyFlow/
├── PROMPT.md                     # Master engineering specification
├── README.md                     # This documentation
├── Cargo.toml                    # Root workspace configuration
├── .gitattributes                # Git LFS rules for ONNX models
├── .gitignore                    # Build and artifact exclusions
│
├── npu-forge/                    # Snapdragon 8 Gen 2 hardware emulator & memory planner
│   ├── Cargo.toml
│   └── src/
│       ├── hardware_profile.rs   # Hexagon v73 HTP profile & operator constraints
│       ├── memory_planner.rs     # 500 MB budget calculation & memory report
│       ├── vtcm_simulator.rs     # 6 MB usable VTCM ping-pong DMA simulator
│       ├── onnx_optimizer.rs     # SHA-256 integrity & operator validator
│       └── main.rs               # Validation CLI binary
│
├── copyflow-core/                # Pure Rust OpenXML extraction, mapping & cloning
│   ├── Cargo.toml
│   ├── OPENXML_RULES.md          # OpenXML invariants and cloning guide
│   ├── src/
│   │   ├── schema.rs             # LayoutBlueprint, SourceBlock, MappingDirective
│   │   ├── inspector.rs          # DOCX template inspector & source extractor
│   │   ├── mapper.rs             # 4096-token pinned-prefix scheduler & NPU trait
│   │   ├── cloner.rs             # Non-destructive ZIP container rebuilder
│   │   ├── verifier.rs           # Two-pass pagination & orphan heading fixer
│   │   └── pipeline.rs           # 6-stage Argon task pipeline
│   └── tests/
│       └── docx_cloning_test.rs  # End-to-end integration test
│
├── android-bridge/               # JNI bindings for Jetpack Compose integration
│   ├── Cargo.toml
│   └── src/
│       └── lib.rs                # Native JNI exports (cdylib)
│
├── distillation-trainer/         # 6 GB VRAM safe distillation pipeline
│   ├── requirements.txt
│   ├── configs/
│   │   └── dltm_360m_sd8gen2.yaml
│   ├── 01_generate_teacher_data.py
│   ├── 02_init_pruned_student.py
│   ├── 03_distill_student_6gb.py
│   ├── 04_quantize_export_w4a8.py
│   ├── 05_compile_qnn_context.py
│   └── run_all_6gb_safe.py
│
├── models/                       # NPU model artifacts & hardware manifest
│   ├── dltm_360m_w4a8.onnx       # W4A8 static-shape deployment model
│   ├── tokenizer_32k.json        # 32K structural BPE tokenizer
│   ├── qnn_htp_v73_config.json   # QNN HTP v73 runtime configuration
│   └── memory_manifest.json      # Hardware-signed memory & VTCM report
│
└── ai-studio-handoff/            # Google AI Studio Android handoff package
    ├── AI_STUDIO_PROMPT.md       # Master prompt for Google AI Studio
    ├── CopyFlowTaskContract.kt   # Kotlin Coroutines & Flow JNI contract
    └── task_events_schema.json   # JSON Schema for Argon task events
```

---

## Verification & Testing

### 1. Run All Workspace Unit & Integration Tests
```bash
cargo test --workspace --release
```

### 2. Run Complete 6 GB Safe Distillation & Model Export
```bash
python distillation-trainer/run_all_6gb_safe.py
```

### 3. Validate Hardware Memory Budget & VTCM Tiling
```bash
cargo run --release -p npu-forge -- validate --onnx models/dltm_360m_w4a8.onnx --out models/memory_manifest.json
```

---

## Google AI Studio Integration

To build the Gemini Task Mode Android application:
1. Push this repository to GitHub.
2. In Google AI Studio, import the repository.
3. Paste the contents of `ai-studio-handoff/AI_STUDIO_PROMPT.md` into the prompt editor to generate the complete Jetpack Compose application.

