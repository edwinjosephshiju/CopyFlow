# Google AI Studio Prompt: Building the CopyFlow Task Mode Android App

Copy and paste the exact prompt below into **Google AI Studio** after connecting your imported `CopyFlow` repository.

---

```markdown
# Role & Mission
You are an expert Senior Android / Jetpack Compose Engineer building the client application for **CopyFlow** — a local, on-device document layout cloning engine powered by the Snapdragon 8 Gen 2 Hexagon v73 HTP NPU and pure Rust OpenXML reconstruction.

The user has connected the `CopyFlow` repository containing:
- `android-bridge/` (Precompiled Rust JNI cdylib exposing `CopyFlowBridge`)
- `ai-studio-handoff/CopyFlowTaskContract.kt` (Coroutines & Flow JNI contract)
- `ai-studio-handoff/task_events_schema.json` (6-stage Argon task events schema)
- `models/dltm_360m_w4a8.onnx` & `models/memory_manifest.json` (Snapdragon 8 Gen 2 NPU model)

Your goal is to build a high-performance **Material 3 / Jetpack Compose Android Application** modeled after **Gemini Task Mode (Argon)**.

---

## Visual Design & Theme System (Argon Obsidian / Indigo)
- **Palette:** Dark obsidian background (`#0B0E14`), deep indigo elevated card surfaces (`#151A24`), luminous cyan telemetry accents (`#22D3EE`), and electric violet progress indicators (`#818CF8`).
- **Typography:** Inter / Roboto Flex with crisp monospace font (`JetBrains Mono`) for hardware telemetry badges and file paths.
- **Micro-Interactions:** Smooth animated transitions, shimmering progress states during NPU execution, and haptic feedback on completion.

---

## Core Screen Layout & Architecture

### 1. Persistent Top NPU Hardware Telemetry Pill
At the top of the screen, display a persistent telemetry chip queried directly from `CopyFlowBridge.validateHardwareBudget()`:
- **SoC:** `Snapdragon 8 Gen 2 (HTP v73)`
- **LPDDR5X Footprint:** `455.25 / 500.00 MB` (with color indicator: Green <480 MB, Yellow >=480 MB)
- **VTCM Ping-Pong Slot:** `6.0 / 8.0 MB (Zero DRAM Spill Verified)`
- **NPU Context:** `1024 Pinned Prefix + 3072 Rolling (W4A8 INT4)`

### 2. Dual Document Selection Cards
Provide two interactive picker cards supporting both `.docx` and `.pdf` files via Android's `ActivityResultContracts.OpenDocument()`:
1. **Reference Template Card (Layout Target):**
   - Shows selected filename, detected page size (e.g. Letter / A4), margins, and style count.
   - Provides a "Preview Layout" modal thumbnail.
2. **Source Content Card (Text & Data):**
   - Shows selected filename, extracted paragraph count, and detected tables.
   - Highlights inline formatting preservation badge (Bold, Italic, Lists).

### 3. Argon 6-Stage Live Execution Stepper
When the user taps **"Clone Layout on NPU"**, launch a foreground coroutine observing `CopyFlowBridge.executeLocalCloneTask(...)` and render an expandable 6-stage stepper:
1. `STAGE_1_INSPECTING_REFERENCE`: Inspecting reference layout, margins & OpenXML styles
2. `STAGE_2_EXTRACTING_SOURCE`: Extracting source paragraphs, runs & tables
3. `STAGE_3_NPU_SEMANTIC_MAPPING`: Mapping content to template on Hexagon HTP (4096 ctx)
4. `STAGE_4_OPENXML_RECONSTRUCTION`: Cloning template container & injecting mapped XML
5. `STAGE_5_TWO_PASS_PAGINATION_FIX`: Running two-pass glyph metric pagination repair
6. `STAGE_6_VISUAL_QA_COMPLETE`: Completeness & layout verification passed

Each stage displays a spinning indicator while active, a luminous green checkmark when complete, and expandable sub-millisecond telemetry.

### 4. Before / After Split Preview & QA Scorecard
Upon completion, slide up the verification scorecard:
- **Completeness Ratio:** `100.0%` (Guarantees zero dropped user paragraphs)
- **Orphan Headings Repaired:** Number of automatic page breaks injected
- **Total Generated Pages:** Final pagination count
- **Action Buttons:**
  - **Share / Export DOCX:** Launches Android system share intent with the rebuilt DOCX.
  - **Open Document:** Opens the reconstructed file in Microsoft Word or Google Docs.

---

## JNI Integration Contract
Reference `CopyFlowTaskContract.kt`:
```kotlin
val flow = CopyFlowBridge.executeLocalCloneTask(templatePath, sourcePath, outputPath)
flow.collect { (event, result) ->
    if (event != null) {
        updateStepper(event.stageNumber, event.progressPercent, event.description)
    }
    if (result != null) {
        displayScorecard(result)
    }
}
```

Implement the complete Compose UI code in `MainActivity.kt` and `TaskModeScreen.kt` with zero stubs or placeholders.
```

