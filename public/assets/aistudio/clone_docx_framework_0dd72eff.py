#!/usr/bin/env python3
"""
clone_docx_framework.py
Reusable OpenXML builder library for 1:1 DOCX template layout cloning.
Unpacks a reference master DOCX to preserve styles.xml, settings.xml,
fontTable.xml, numbering.xml, headers, footers, and sectPr definitions,
and provides exact OpenXML constructors for paragraphs, headings, tables,
captions, equations, and embedded figures.
"""
import zipfile
import shutil
import os
import xml.etree.ElementTree as ET

ET.register_namespace('w', 'http://schemas.openxmlformats.org/wordprocessingml/2006/main')
ET.register_namespace('r', 'http://schemas.openxmlformats.org/officeDocument/2006/relationships')
ET.register_namespace('wp', 'http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing')
ET.register_namespace('a', 'http://schemas.openxmlformats.org/drawingml/2006/main')
ET.register_namespace('pic', 'http://schemas.openxmlformats.org/drawingml/2006/picture')
ET.register_namespace('w14', 'http://schemas.microsoft.com/office/word/2010/wordml')

W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
WP_NS = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
PIC_NS = "http://schemas.openxmlformats.org/drawingml/2006/picture"

def w(tag):
    return f"{{{W_NS}}}{tag}"

def make_run(text, bold=False, italic=False, sz=None, font=None, color=None, br_after=False):
    """Create a <w:r> element with exact run properties (<w:rPr>)."""
    r = ET.Element(w("r"))
    rPr = ET.SubElement(r, w("rPr"))
    if font:
        ET.SubElement(rPr, w("rFonts"), {w("ascii"): font, w("cs"): font, w("eastAsia"): font, w("hAnsi"): font})
    if bold:
        ET.SubElement(rPr, w("b"), {w("val"): "1"})
        ET.SubElement(rPr, w("bCs"), {w("val"): "1"})
    if italic:
        ET.SubElement(rPr, w("i"), {w("val"): "1"})
        ET.SubElement(rPr, w("iCs"), {w("val"): "1"})
    if color:
        ET.SubElement(rPr, w("color"), {w("val"): color})
    if sz:
        ET.SubElement(rPr, w("sz"), {w("val"): str(sz)})
        ET.SubElement(rPr, w("szCs"), {w("val"): str(sz)})
    ET.SubElement(rPr, w("rtl"), {w("val"): "0"})
    if text is not None and text != "":
        lines = text.split("\n")
        for idx, line in enumerate(lines):
            t = ET.SubElement(r, w("t"))
            t.set("{http://www.w3.org/XML/1998/namespace}space", "preserve")
            t.text = line
            if idx < len(lines) - 1:
                ET.SubElement(r, w("br"), {w("type"): "textWrapping"})
    if br_after:
        ET.SubElement(r, w("br"), {w("type"): "textWrapping"})
    return r

def make_p(style=None, jc=None, sb=None, sa=None, line=None, first_line=None, left=None, hanging=None, pb_before=False, keep_next=False, runs=None):
    """Create a <w:p> element with exact paragraph formatting (<w:pPr>)."""
    p = ET.Element(w("p"))
    pPr = ET.SubElement(p, w("pPr"))
    if style:
        ET.SubElement(pPr, w("pStyle"), {w("val"): style})
    if keep_next:
        ET.SubElement(pPr, w("keepNext"), {w("val"): "1"})
    if pb_before:
        ET.SubElement(pPr, w("pageBreakBefore"), {w("val"): "1"})
    if sb is not None or sa is not None or line is not None:
        sp_attrib = {w("lineRule"): "auto"}
        if sb is not None:
            sp_attrib[w("before")] = str(sb)
        if sa is not None:
            sp_attrib[w("after")] = str(sa)
        if line is not None:
            sp_attrib[w("line")] = str(line)
        ET.SubElement(pPr, w("spacing"), sp_attrib)
    if first_line is not None or left is not None or hanging is not None:
        ind_attrib = {}
        if first_line is not None:
            ind_attrib[w("firstLine")] = str(first_line)
        if left is not None:
            ind_attrib[w("left")] = str(left)
        if hanging is not None:
            ind_attrib[w("hanging")] = str(hanging)
        ET.SubElement(pPr, w("ind"), ind_attrib)
    if jc:
        ET.SubElement(pPr, w("jc"), {w("val"): jc})
    ET.SubElement(pPr, w("rPr"))
    if runs:
        for r in runs:
            p.append(r)
    return p

def make_h1(chap_num, title_text, pb_before=True, font="Times New Roman"):
    return make_p(
        style="Heading1",
        jc="center",
        pb_before=pb_before,
        runs=[make_run(f"Chapter {chap_num}\n{title_text}", font=font)]
    )

def make_h2(text, font="Times New Roman", pb_before=False):
    return make_p(
        style="Heading2",
        jc="left",
        pb_before=pb_before,
        runs=[make_run(text, font=font)]
    )

def make_h3(text, font="Times New Roman", pb_before=False):
    return make_p(
        style="Heading3",
        jc="left",
        pb_before=pb_before,
        runs=[make_run(text, font=font)]
    )

def make_body_p(text, first_line=648):
    return make_p(first_line=first_line, runs=[make_run(text)])

def make_bullet_p(lead_in, text, left=648, hanging=288):
    runs = []
    if lead_in:
        runs.append(make_run(f"• {lead_in}", bold=True))
        runs.append(make_run(text))
    else:
        runs.append(make_run(f"• {text}"))
    return make_p(left=left, hanging=hanging, runs=runs)

def make_eq_p(eq_text):
    return make_p(runs=[make_run(eq_text)])

def make_fig_caption(fig_num, text, sz=22):
    return make_p(jc="center", sb=60, sa=240, runs=[make_run(f"FIG. {fig_num}: {text}", bold=True, sz=sz)])

def make_tbl_caption(tbl_num, text, sz=21):
    return make_p(jc="center", sb=80, sa=240, runs=[make_run(f"Table {tbl_num}: {text}", bold=True, sz=sz)])

def make_image_p(rId, img_name, cx, cy, docPr_id, sb=200, sa=60, keep_next=True, jc="center", first_line=None):
    xml_str = f'''<w:p xmlns:w="{W_NS}" xmlns:wp="{WP_NS}" xmlns:a="{A_NS}" xmlns:pic="{PIC_NS}" xmlns:r="{R_NS}">
        <w:pPr>
            {'<w:keepNext w:val="1"/>' if keep_next else ''}
            {f'<w:spacing w:after="{sa}" w:before="{sb}" w:lineRule="auto"/>' if (sb is not None and sa is not None) else ''}
            {f'<w:ind w:firstLine="{first_line}"/>' if first_line is not None else ''}
            {f'<w:jc w:val="{jc}"/>' if jc else ''}
            <w:rPr/>
        </w:pPr>
        <w:r>
            <w:rPr/>
            <w:drawing>
                <wp:inline distB="0" distT="0" distL="0" distR="0">
                    <wp:extent cx="{cx}" cy="{cy}"/>
                    <wp:effectExtent b="0" l="0" r="0" t="0"/>
                    <wp:docPr id="{docPr_id}" name="{img_name}"/>
                    <a:graphic>
                        <a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
                            <pic:pic>
                                <pic:nvPicPr>
                                    <pic:cNvPr id="0" name="{img_name}"/>
                                    <pic:cNvPicPr preferRelativeResize="0"/>
                                </pic:nvPicPr>
                                <pic:blipFill>
                                    <a:blip r:embed="{rId}"/>
                                    <a:srcRect b="0" l="0" r="0" t="0"/>
                                    <a:stretch><a:fillRect/></a:stretch>
                                </pic:blipFill>
                                <pic:spPr>
                                    <a:xfrm><a:off x="0" y="0"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm>
                                    <a:prstGeom prst="rect"/><a:ln/>
                                </pic:spPr>
                            </pic:pic>
                        </a:graphicData>
                    </a:graphic>
                </wp:inline>
            </w:drawing>
        </w:r>
    </w:p>'''
    return ET.fromstring(xml_str)

def make_data_table(tbl_style, col_widths, headers, rows_data, alignments=None, header_fill="f1f5f9"):
    total_w = float(sum(col_widths))
    tbl = ET.Element(w("tbl"))
    tblPr = ET.SubElement(tbl, w("tblPr"))
    ET.SubElement(tblPr, w("tblStyle"), {w("val"): tbl_style})
    ET.SubElement(tblPr, w("tblW"), {w("w"): str(total_w), w("type"): "dxa"})
    ET.SubElement(tblPr, w("jc"), {w("val"): "center"})
    ET.SubElement(tblPr, w("tblLayout"), {w("type"): "fixed"})
    ET.SubElement(tblPr, w("tblLook"), {w("val"): "0400"})
    
    tblGrid = ET.SubElement(tbl, w("tblGrid"))
    for cw in col_widths:
        ET.SubElement(tblGrid, w("gridCol"), {w("w"): str(cw)})
        
    tr_h = ET.SubElement(tbl, w("tr"))
    trPr_h = ET.SubElement(tr_h, w("trPr"))
    ET.SubElement(trPr_h, w("cantSplit"), {w("val"): "0"})
    ET.SubElement(trPr_h, w("tblHeader"), {w("val"): "0"})
    for idx, h_text in enumerate(headers):
        tc = ET.SubElement(tr_h, w("tc"))
        tcPr = ET.SubElement(tc, w("tcPr"))
        tcBorders = ET.SubElement(tcPr, w("tcBorders"))
        ET.SubElement(tcBorders, w("top"), {w("color"): "000000", w("space"): "0", w("sz"): "6", w("val"): "single"})
        ET.SubElement(tcBorders, w("bottom"), {w("color"): "000000", w("space"): "0", w("sz"): "6", w("val"): "single"})
        ET.SubElement(tcPr, w("shd"), {w("fill"): header_fill, w("val"): "clear"})
        p = make_p(jc="center", sb=60, sa=60, runs=[make_run(h_text, bold=True, sz=21)])
        tc.append(p)
        
    for r_idx, r_data in enumerate(rows_data):
        tr = ET.SubElement(tbl, w("tr"))
        trPr = ET.SubElement(tr, w("trPr"))
        ET.SubElement(trPr, w("cantSplit"), {w("val"): "0"})
        ET.SubElement(trPr, w("tblHeader"), {w("val"): "0"})
        is_last = (r_idx == len(rows_data) - 1)
        for c_idx, val in enumerate(r_data):
            tc = ET.SubElement(tr, w("tc"))
            tcPr = ET.SubElement(tc, w("tcPr"))
            tcBorders = ET.SubElement(tcPr, w("tcBorders"))
            ET.SubElement(tcBorders, w("top"), {w("color"): "cbd5e1", w("space"): "0", w("sz"): "4", w("val"): "single"})
            if is_last:
                ET.SubElement(tcBorders, w("bottom"), {w("color"): "000000", w("space"): "0", w("sz"): "6", w("val"): "single"})
            else:
                ET.SubElement(tcBorders, w("bottom"), {w("color"): "cbd5e1", w("space"): "0", w("sz"): "4", w("val"): "single"})
            al = alignments[c_idx] if alignments else ("center" if len(str(val)) <= 12 else "left")
            p = make_p(jc=al, sb=50, sa=50, runs=[make_run(str(val), sz=20)])
            tc.append(p)
    return tbl

def add_image_relationship_safe(rels_path, rId, target_media_path):
    """Safely add an image relationship to word/_rels/document.xml.rels without corrupting default namespaces."""
    with open(rels_path, "r", encoding="utf-8") as rf:
        rels_str = rf.read()
    new_rel = f'<Relationship Id="{rId}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="{target_media_path}"/>'
    if f'Id="{rId}"' not in rels_str:
        rels_str = rels_str.replace("</Relationships>", new_rel + "</Relationships>")
        with open(rels_path, "w", encoding="utf-8") as rf:
            rf.write(rels_str)
