#!/usr/bin/env python3
"""
inspect_template.py
Extracts a complete 1:1 structural, typographical, and layout blueprint from a
reference DOCX or PDF template and a content source DOCX or PDF file.

Usage:
    python3 inspect_template.py <template_file> [content_source_file]
"""
import sys
import os
import io
import zipfile
import xml.etree.ElementTree as ET
from contextlib import redirect_stdout

W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
WP_NS = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

def w(tag):
    return f"{{{W_NS}}}{tag}"

def inspect_docx(path, label="DOCUMENT"):
    print(f"\n{'='*70}\n[{label}] DOCX INSPECTION: {path}\n{'='*70}")
    with zipfile.ZipFile(path, "r") as z:
        namelist = z.namelist()
        media_files = [f for f in namelist if f.startswith("word/media/")]
        print(f"Archive entries: {len(namelist)} | Media files: {media_files}")

        # 1. Inspect styles.xml (docDefaults & key styles)
        if "word/styles.xml" in namelist:
            s_root = ET.fromstring(z.read("word/styles.xml"))
            doc_defs = s_root.find(w("docDefaults"))
            if doc_defs is not None:
                print("\n--- docDefaults ---")
                print(ET.tostring(doc_defs, encoding="unicode"))

            print("\n--- Defined Styles ---")
            for st in s_root.findall(w("style")):
                s_id = st.attrib.get(w("styleId"), "")
                name_el = st.find(w("name"))
                s_name = name_el.attrib.get(w("val"), "") if name_el is not None else ""
                pPr = st.find(w("pPr"))
                rPr = st.find(w("rPr"))
                if s_id in ("Normal", "Heading1", "Heading2", "Heading3", "Title", "Subtitle") or s_id.startswith("Table"):
                    p_str = ET.tostring(pPr, encoding="unicode") if pPr is not None else ""
                    r_str = ET.tostring(rPr, encoding="unicode") if rPr is not None else ""
                    print(f"  Style [{s_id}] (name='{s_name}'): pPr={p_str} | rPr={r_str}")

        # 2. Inspect document.xml (Sections, Paragraphs, Tables, Images)
        if "word/document.xml" in namelist:
            d_root = ET.fromstring(z.read("word/document.xml"))
            body = d_root.find(w("body"))
            sectPrs = d_root.findall(f".//{w('sectPr')}")
            print(f"\n--- Sections ({len(sectPrs)} total) ---")
            for idx, sp in enumerate(sectPrs):
                pgSz = sp.find(w("pgSz"))
                pgMar = sp.find(w("pgMar"))
                pgNum = sp.find(w("pgNumType"))
                sz_a = {k.split('}')[-1]: v for k, v in pgSz.attrib.items()} if pgSz is not None else {}
                mar_a = {k.split('}')[-1]: v for k, v in pgMar.attrib.items()} if pgMar is not None else {}
                num_a = {k.split('}')[-1]: v for k, v in pgNum.attrib.items()} if pgNum is not None else {}
                print(f"  Section {idx}: pgSz={sz_a} | pgMar={mar_a} | pgNumType={num_a}")

            print(f"\n--- Body Elements ({len(body)} children) ---")
            for idx, elem in enumerate(body):
                tag = elem.tag.split("}")[-1]
                if tag == "p":
                    texts = [t.text for t in elem.iter(w("t")) if t.text]
                    full_txt = "".join(texts)
                    pPr = elem.find(w("pPr"))
                    st_el = pPr.find(w("pStyle")) if pPr is not None else None
                    st_val = st_el.attrib.get(w("val")) if st_el is not None else "Normal"
                    jc_el = pPr.find(w("jc")) if pPr is not None else None
                    jc_val = jc_el.attrib.get(w("val")) if jc_el is not None else None
                    sp_el = pPr.find(w("spacing")) if pPr is not None else None
                    sp_a = {k.split('}')[-1]: v for k, v in sp_el.attrib.items()} if sp_el is not None else {}
                    ind_el = pPr.find(w("ind")) if pPr is not None else None
                    ind_a = {k.split('}')[-1]: v for k, v in ind_el.attrib.items()} if ind_el is not None else {}
                    pb_el = pPr.find(w("pageBreakBefore")) if pPr is not None else None
                    pb_val = pb_el.attrib.get(w("val"), "1") if pb_el is not None else None
                    sect_el = pPr.find(w("sectPr")) if pPr is not None else None
                    ext_el = elem.find(f".//{{{WP_NS}}}extent")
                    ext_a = dict(ext_el.attrib) if ext_el is not None else None
                    blip_el = elem.find(f".//{{{A_NS}}}blip")
                    rId = blip_el.attrib.get(f"{{{R_NS}}}embed") if blip_el is not None else None

                    if full_txt.strip() or pb_val or sect_el is not None or ext_a:
                        print(f"  {idx:03d} P [{st_val}] jc={jc_val} sp={sp_a} ind={ind_a} pb={pb_val} sectBreak={sect_el is not None} img={rId}({ext_a}): {repr(full_txt[:65])}")
                elif tag == "tbl":
                    rows = elem.findall(w("tr"))
                    grid = elem.find(w("tblGrid"))
                    cols = [c.attrib.get(w("w")) for c in grid.findall(w("gridCol"))] if grid is not None else []
                    first_row = ["".join([t.text for t in tc.iter(w("t")) if t.text]) for tc in rows[0].findall(w("tc"))] if rows else []
                    print(f"  {idx:03d} TBL (rows={len(rows)}, gridCols={cols}): {first_row}")

def inspect_pdf(path, label="DOCUMENT"):
    print(f"\n{'='*70}\n[{label}] PDF INSPECTION: {path}\n{'='*70}")
    try:
        import pypdfium2 as pdfium
        pdf = pdfium.PdfDocument(path)
        print(f"Total PDF Pages: {len(pdf)}")
        for i in range(len(pdf)):
            page = pdf[i]
            w_pt, h_pt = page.get_size()
            txt = page.get_textpage().get_text_range().strip()
            lines = [l.strip() for l in txt.split("\n") if l.strip()]
            first_l = lines[0] if lines else "EMPTY"
            last_l = lines[-1] if lines else "EMPTY"
            print(f"  Page {i+1:02d} ({w_pt:.1f}x{h_pt:.1f} pt, {len(txt)} chars): first={repr(first_l[:45])} | last={repr(last_l[:25])}")
    except Exception as e:
        print(f"PDF inspection error: {e}")

def inspect_pdf_android(path, label="DOCUMENT"):
    """Android-compatible PDF inspector using pypdf if pypdfium2 is unavailable."""
    print(f"\n{'='*70}\n[{label}] PDF INSPECTION: {path}\n{'='*70}")
    try:
        from pypdf import PdfReader
        reader = PdfReader(path)
        print(f"Total PDF Pages: {len(reader.pages)}")
        for i, page in enumerate(reader.pages):
            box = page.mediabox
            w_pt, h_pt = float(box.width), float(box.height)
            txt = (page.extract_text() or "").strip()
            lines = [l.strip() for l in txt.split("\n") if l.strip()]
            first_l = lines[0] if lines else "EMPTY"
            last_l = lines[-1] if lines else "EMPTY"
            print(f"  Page {i+1:02d} ({w_pt:.1f}x{h_pt:.1f} pt, {len(txt)} chars): first={repr(first_l[:45])} | last={repr(last_l[:25])}")
    except Exception:
        inspect_pdf(path, label)

def inspect_template(template_path: str, source_path: str = "", max_chars: int = 12000) -> str:
    """Chaquopy entrypoint: returns the combined structural blueprint as a string."""
    buf = io.StringIO()
    with redirect_stdout(buf):
        for idx, (fpath, lbl) in enumerate([(template_path, "MASTER TEMPLATE"), (source_path, "CONTENT SOURCE")]):
            if not fpath or not os.path.exists(fpath):
                continue
            if fpath.lower().endswith(".docx"):
                inspect_docx(fpath, lbl)
            elif fpath.lower().endswith(".pdf"):
                inspect_pdf_android(fpath, lbl)
            else:
                print(f"Unsupported extension for {fpath}")
    output = buf.getvalue()
    if len(output) > max_chars:
        half = max_chars // 2
        output = output[:half] + f"\n\n... [TRUNCATED {len(output) - max_chars} CHARS FOR CONTEXT WINDOW] ...\n\n" + output[-half:]
    return output

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 inspect_template.py <template_file> [content_source_file]")
        sys.exit(1)
    for i, fpath in enumerate(sys.argv[1:]):
        lbl = "MASTER TEMPLATE" if i == 0 else f"CONTENT SOURCE {i}"
        if fpath.lower().endswith(".docx"):
            inspect_docx(fpath, lbl)
        elif fpath.lower().endswith(".pdf"):
            inspect_pdf_android(fpath, lbl)
        else:
            print(f"Unsupported extension for {fpath}")
