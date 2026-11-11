use std::fs::File;
use std::io::{Read, Write};
use std::path::Path;
use zip::write::SimpleFileOptions;
use zip::{ZipArchive, ZipWriter};

use copyflow_core::pipeline::CopyFlowPipeline;

fn create_mock_reference_docx(path: &Path) {
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let file = File::create(path).unwrap();
    let mut zip = ZipWriter::new(file);
    let options = SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);

    // [Content_Types].xml
    zip.start_file("[Content_Types].xml", options).unwrap();
    zip.write_all(b"<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/></Types>").unwrap();

    // _rels/.rels
    zip.start_file("_rels/.rels", options).unwrap();
    zip.write_all(b"<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>").unwrap();

    // word/_rels/document.xml.rels
    zip.start_file("word/_rels/document.xml.rels", options).unwrap();
    zip.write_all(b"<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rIdHeader\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/header\" Target=\"header1.xml\"/></Relationships>").unwrap();

    // word/header1.xml (Crucial test invariant: this file must survive verbatim in the cloned output!)
    zip.start_file("word/header1.xml", options).unwrap();
    zip.write_all(b"<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:hdr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:p><w:r><w:t>CONFIDENTIAL &amp; PROPRIETARY INSTITUTIONAL TEMPLATE</w:t></w:r></w:p></w:hdr>").unwrap();

    // word/styles.xml
    zip.start_file("word/styles.xml", options).unwrap();
    let styles_xml = r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:style w:type="paragraph" w:styleId="Normal">
    <w:name w:val="Normal"/>
    <w:pPr><w:jc w:val="both"/><w:spacing w:before="0" w:after="160" w:line="240"/></w:pPr>
    <w:rPr><w:rFonts w:ascii="Calibri"/><w:sz w:val="24"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading1">
    <w:name w:val="Heading 1"/>
    <w:pPr><w:jc w:val="left"/><w:spacing w:before="240" w:after="120"/><w:keepNext/><w:pageBreakBefore/></w:pPr>
    <w:rPr><w:rFonts w:ascii="Times New Roman"/><w:b/><w:sz w:val="32"/></w:rPr>
  </w:style>
  <w:style w:type="table" w:styleId="TableGrid">
    <w:name w:val="Table Grid"/>
  </w:style>
</w:styles>"#;
    zip.write_all(styles_xml.as_bytes()).unwrap();

    // word/document.xml
    zip.start_file("word/document.xml", options).unwrap();
    let doc_xml = r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    <w:p><w:r><w:t>Template Placeholder</w:t></w:r></w:p>
    <w:tbl>
      <w:tblPr><w:tblStyle w:val="TableGrid"/></w:tblPr>
      <w:tblGrid>
        <w:gridCol w:w="4000"/>
        <w:gridCol w:w="5000"/>
      </w:tblGrid>
      <w:tr><w:tc><w:p><w:r><w:t>Header A</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Header B</w:t></w:r></w:p></w:tc></w:tr>
    </w:tbl>
    <w:sectPr>
      <w:pgSz w:w="12240" w:h="15840" w:orient="portrait"/>
      <w:pgMar w:top="1440" w:bottom="1440" w:left="1440" w:right="1440" w:header="720" w:footer="720"/>
      <w:cols w:num="1"/>
    </w:sectPr>
  </w:body>
</w:document>"#;
    zip.write_all(doc_xml.as_bytes()).unwrap();

    zip.finish().unwrap();
}

fn create_mock_source_docx(path: &Path) {
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let file = File::create(path).unwrap();
    let mut zip = ZipWriter::new(file);
    let options = SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);

    zip.start_file("word/document.xml", options).unwrap();
    let doc_xml = r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    <w:p>
      <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
      <w:r><w:b/><w:t>Executive Summary</w:t></w:r>
    </w:p>
    <w:p>
      <w:r><w:t>This is the first paragraph of real source content.</w:t></w:r>
      <w:r><w:b/><w:t> Bold insight: </w:t></w:r>
      <w:r><w:i/><w:t>on-device NPU inference scales effortlessly.</w:t></w:r>
    </w:p>
    <w:tbl>
      <w:tr>
        <w:tc><w:p><w:r><w:t>Metric</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>Score</w:t></w:r></w:p></w:tc>
      </w:tr>
      <w:tr>
        <w:tc><w:p><w:r><w:t>Latency</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>18.4 ms</w:t></w:r></w:p></w:tc>
      </w:tr>
    </w:tbl>
  </w:body>
</w:document>"#;
    zip.write_all(doc_xml.as_bytes()).unwrap();
    zip.finish().unwrap();
}

#[test]
fn test_end_to_end_docx_cloning() {
    let temp_dir = std::env::temp_dir().join("copyflow_test_workspace");
    let _ = std::fs::create_dir_all(&temp_dir);

    let ref_path = temp_dir.join("reference_template.docx");
    let src_path = temp_dir.join("source_draft.docx");
    let out_path = temp_dir.join("cloned_output.docx");

    create_mock_reference_docx(&ref_path);
    create_mock_source_docx(&src_path);

    let pipeline = CopyFlowPipeline::new();
    let mut stages_received = Vec::new();

    let report = pipeline
        .execute(&ref_path, &src_path, &out_path, |event| {
            stages_received.push(event.stage_number);
        })
        .expect("Pipeline execution failed");

    // 1. Verify all 6 stages fired
    assert_eq!(stages_received, vec![1, 2, 3, 4, 5, 6]);

    // 2. Verify QA report
    assert!(report.passed_qa, "Pipeline verification report passed_qa must be true");
    assert_eq!(report.content_completeness_ratio, 1.0, "Completeness ratio must be 1.0");
    assert_eq!(report.source_block_count, 3, "Source blocks: 1 heading + 1 paragraph + 1 table");
    assert_eq!(report.cloned_block_count, 3);

    // 3. Inspect cloned output ZIP
    assert!(out_path.exists(), "Output DOCX must exist on disk");
    let out_file = File::open(&out_path).unwrap();
    let mut out_archive = ZipArchive::new(out_file).unwrap();

    // Verify word/header1.xml survived intact
    {
        let mut header_file = out_archive
            .by_name("word/header1.xml")
            .expect("word/header1.xml must survive verbatim in cloned output");
        let mut header_content = String::new();
        header_file.read_to_string(&mut header_content).unwrap();
        assert!(header_content.contains("CONFIDENTIAL &amp; PROPRIETARY"));
    }

    // Verify word/document.xml contents
    {
        let mut doc_file = out_archive
            .by_name("word/document.xml")
            .expect("word/document.xml must exist in output");
        let mut doc_content = String::new();
        doc_file.read_to_string(&mut doc_content).unwrap();

        // Assert source texts exist in document.xml
        assert!(doc_content.contains("Executive Summary"), "Heading text must be present");
        assert!(doc_content.contains("Bold insight:"), "Bold run text must be present");
        assert!(doc_content.contains("Latency"), "Table cell text must be present");
        assert!(doc_content.contains("18.4 ms"), "Table cell value must be present");

        // Assert preserved page geometry from reference
        assert!(doc_content.contains("w:w=\"12240\""), "Page width 12240 dxa must be preserved");
        assert!(doc_content.contains("w:h=\"15840\""), "Page height 15840 dxa must be preserved");
    }

    // Cleanup test artifacts
    let _ = std::fs::remove_dir_all(&temp_dir);
}
