"""Spike DMS-23: minimal rendering worker, HTML + CSS -> PDF/A with WeasyPrint.

Not production code. Reads a job description (JSON), renders the referenced HTML
with the shared print stylesheet and writes a PDF/A file plus one JSON result line
on stdout (checksum, size, render time, page count).

Three modes:
  default        WeasyPrint defaults: no dates in the document metadata, file
                 identifier = md5 of the content, attachment dates from the clock.
  naive          what a straightforward integration does: creation/modification
                 date = render time, attachment dates taken from the clock.
  deterministic  every value that ends up in the PDF is derived from the job:
                 dates = release timestamp of the document version, file
                 identifier = hash of document id + version.
"""

import argparse
import hashlib
import html
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import pydyf
import weasyprint
from xml.etree import ElementTree

for _prefix, _uri in {
    "pdfaExtension": "http://www.aiim.org/pdfa/ns/extension/",
    "pdfaSchema": "http://www.aiim.org/pdfa/ns/schema#",
    "pdfaProperty": "http://www.aiim.org/pdfa/ns/property#",
    "dms": "https://dms.example/ns/document/1.0/",
}.items():
    ElementTree.register_namespace(_prefix, _uri)

HERE = Path(__file__).resolve().parent
BASE_CSS = HERE / "templates" / "base.css"

# Custom XMP schema for DMS identifiers. PDF/A requires every non-standard XMP
# property to be declared in a pdfaExtension schema inside the same packet.
DMS_NS = "https://dms.example/ns/document/1.0/"
XMP_TEMPLATE = """<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
 <rdf:Description rdf:about=""
   xmlns:pdfaExtension="http://www.aiim.org/pdfa/ns/extension/"
   xmlns:pdfaSchema="http://www.aiim.org/pdfa/ns/schema#"
   xmlns:pdfaProperty="http://www.aiim.org/pdfa/ns/property#">
  <pdfaExtension:schemas><rdf:Bag><rdf:li rdf:parseType="Resource">
   <pdfaSchema:schema>DMS document identification</pdfaSchema:schema>
   <pdfaSchema:namespaceURI>{ns}</pdfaSchema:namespaceURI>
   <pdfaSchema:prefix>dms</pdfaSchema:prefix>
   <pdfaSchema:property><rdf:Seq>
    <rdf:li rdf:parseType="Resource"><pdfaProperty:name>documentId</pdfaProperty:name><pdfaProperty:valueType>Text</pdfaProperty:valueType><pdfaProperty:category>external</pdfaProperty:category><pdfaProperty:description>DMS document id</pdfaProperty:description></rdf:li>
    <rdf:li rdf:parseType="Resource"><pdfaProperty:name>version</pdfaProperty:name><pdfaProperty:valueType>Integer</pdfaProperty:valueType><pdfaProperty:category>external</pdfaProperty:category><pdfaProperty:description>Released document version</pdfaProperty:description></rdf:li>
    <rdf:li rdf:parseType="Resource"><pdfaProperty:name>validFrom</pdfaProperty:name><pdfaProperty:valueType>Date</pdfaProperty:valueType><pdfaProperty:category>external</pdfaProperty:category><pdfaProperty:description>Date the version becomes valid</pdfaProperty:description></rdf:li>
    <rdf:li rdf:parseType="Resource"><pdfaProperty:name>renderer</pdfaProperty:name><pdfaProperty:valueType>Text</pdfaProperty:valueType><pdfaProperty:category>internal</pdfaProperty:category><pdfaProperty:description>Renderer and template version</pdfaProperty:description></rdf:li>
   </rdf:Seq></pdfaSchema:property>
  </rdf:li>{extra_schemas}</rdf:Bag></pdfaExtension:schemas>
 </rdf:Description>
 <rdf:Description rdf:about="" xmlns:dms="{ns}">
  <dms:documentId>{document_id}</dms:documentId>
  <dms:version>{version}</dms:version>
  <dms:validFrom>{valid_from}</dms:validFrom>
  <dms:renderer>{renderer}</dms:renderer>
 </rdf:Description>
</rdf:RDF>"""

# Optional PDF/UA-1 identification, combined with a tagged PDF/A variant (pdf/a-2a, pdf/a-3a).
# pdfuaid is not a predefined schema in PDF/A-2/3, so it needs its own extension schema entry.
PDFUA_SCHEMA = """<rdf:li rdf:parseType="Resource">
   <pdfaSchema:schema>PDF/UA identification schema</pdfaSchema:schema>
   <pdfaSchema:namespaceURI>http://www.aiim.org/pdfua/ns/id/</pdfaSchema:namespaceURI>
   <pdfaSchema:prefix>pdfuaid</pdfaSchema:prefix>
   <pdfaSchema:property><rdf:Seq><rdf:li rdf:parseType="Resource"><pdfaProperty:name>part</pdfaProperty:name><pdfaProperty:valueType>Integer</pdfaProperty:valueType><pdfaProperty:category>internal</pdfaProperty:category><pdfaProperty:description>PDF/UA version identifier</pdfaProperty:description></rdf:li></rdf:Seq></pdfaSchema:property>
  </rdf:li>"""
PDFUA_XMP = b"""<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
 <rdf:Description rdf:about="" xmlns:pdfuaid="http://www.aiim.org/pdfua/ns/id/"><pdfuaid:part>1</pdfuaid:part></rdf:Description>
</rdf:RDF>"""

# Bump when templates/base.css or this file change in a way that affects output.
TEMPLATE_VERSION = "spike-1"


def merge_xmp(metadata, extra_rdf: list[bytes]) -> None:
    """Put our rdf:Description elements into WeasyPrint's single rdf:RDF element.

    WeasyPrint's xmp_metadata option appends a second rdf:RDF to x:xmpmeta. XMP allows
    only one, and veraPDF ignores everything after the first (a PDF/UA identification
    placed there is reported as missing). So we wrap the metadata generator instead.
    """
    original = metadata.generate_rdf_metadata

    def generate(variant, version, conformance):
        rdf = ElementTree.fromstring(original(variant, version, conformance))
        for extra in extra_rdf:
            rdf.extend(list(ElementTree.fromstring(extra)))
        return ElementTree.tostring(rdf, encoding="utf-8")

    metadata.generate_rdf_metadata = generate


def css_string(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def page_css(job: dict) -> str:
    """Running header/footer content, generated from the job."""
    return f"""
@page {{
  @top-left {{ content: {css_string(job["title"])}; }}
  @top-right {{ content: {css_string(f'{job["document_id"]} · Version {job["version"]}')}; }}
  @bottom-left {{ content: {css_string(f'Valid from {job["valid_from"]}')}; }}
  @bottom-right {{ content: "Page " counter(page) " of " counter(pages); }}
}}"""


def head_meta(job: dict, created: str | None) -> str:
    """Document metadata as HTML meta tags, which WeasyPrint maps to Info + XMP."""
    meta = [
        f"<title>{html.escape(job['title'])}</title>",
        f'<meta name="author" content="{html.escape(job.get("author", ""))}">',
        f'<meta name="description" content="{html.escape(job.get("description", ""))}">',
        f'<meta name="keywords" content="{html.escape(job["document_id"])}">',
        f'<meta name="keywords" content="version {job["version"]}">',
        f'<meta name="generator" content="DMS rendering worker {TEMPLATE_VERSION}">',
    ]
    if created:
        meta.append(f'<meta name="dcterms.created" content="{created}">')
        meta.append(f'<meta name="dcterms.modified" content="{created}">')
    return "\n".join(meta)


def render(job_path: Path, out_path: Path, mode: str, variant: str | None, pdfua: bool = False) -> dict:
    job = json.loads(job_path.read_text(encoding="utf-8"))
    variant = variant or job.get("pdf_variant", "pdf/a-2b")
    body = (job_path.parent / job["content"]).read_text(encoding="utf-8")

    if mode == "deterministic":
        # Release timestamp of the version, normalised to UTC with second precision.
        released = datetime.fromisoformat(job["released_at"]).astimezone(timezone.utc)
        created = released.strftime("%Y-%m-%dT%H:%M:%SZ")
        attachment_time = released.replace(tzinfo=None)
        identifier = hashlib.sha256(
            f'{job["tenant_id"]}:{job["document_id"]}:{job["version"]}'.encode()
        ).hexdigest()[:32].encode()
    elif mode == "default":
        created = None
        attachment_time = None
        identifier = None
    else:
        now = datetime.now(timezone.utc)
        created = now.strftime("%Y-%m-%dT%H:%M:%SZ")
        attachment_time = None  # WeasyPrint falls back to datetime.now()
        identifier = None  # WeasyPrint default: md5 of the file content

    document_html = (
        f'<!DOCTYPE html><html lang="{job.get("lang", "en")}"><head><meta charset="utf-8">'
        f"{head_meta(job, created)}</head><body>{body}</body></html>"
    )

    renderer = f"WeasyPrint {weasyprint.__version__}; pydyf {pydyf.__version__}; template {TEMPLATE_VERSION}"
    xmp = XMP_TEMPLATE.format(
        ns=DMS_NS,
        document_id=html.escape(job["document_id"]),
        version=int(job["version"]),
        valid_from=job["valid_from"],
        renderer=html.escape(renderer),
        extra_schemas=PDFUA_SCHEMA if pdfua else "",
    ).encode()

    attachments = []
    if variant.startswith("pdf/a-3"):
        # PDF/A-3 allows associated files: embed the machine-readable source of the
        # rendition (here the job itself) so the archive copy is self-describing.
        attachments.append(
            weasyprint.Attachment(
                string=json.dumps(job, sort_keys=True, indent=2),
                name="source-job.json",
                description="Source job of this rendition",
                relationship="Source",
                created=attachment_time,
                modified=attachment_time,
            )
        )

    started = time.perf_counter()
    document = weasyprint.HTML(string=document_html, base_url=str(job_path.parent)).render(
        stylesheets=[weasyprint.CSS(filename=str(BASE_CSS)), weasyprint.CSS(string=page_css(job))],
    )
    merge_xmp(document.metadata, [xmp] + ([PDFUA_XMP] if pdfua else []))
    pdf_bytes = document.write_pdf(
        pdf_variant=variant,
        pdf_identifier=identifier,
        attachments=attachments,
    )
    elapsed_ms = (time.perf_counter() - started) * 1000

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(pdf_bytes)
    return {
        "job": str(job_path.parent.name),
        "variant": variant,
        "mode": mode,
        "pages": len(document.pages),
        "bytes": len(pdf_bytes),
        "ms": round(elapsed_ms, 1),
        "sha256": hashlib.sha256(pdf_bytes).hexdigest(),
        "renderer": renderer,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("job", type=Path, help="path to job.json")
    parser.add_argument("out", type=Path, help="output PDF path")
    parser.add_argument("--mode", choices=["default", "naive", "deterministic"], default="deterministic")
    parser.add_argument("--variant", help="override pdf_variant from the job, e.g. pdf/a-3b")
    parser.add_argument("--pdfua", action="store_true", help="also declare PDF/UA-1 (use with pdf/a-2a or pdf/a-3a)")
    args = parser.parse_args()
    print(json.dumps(render(args.job, args.out, args.mode, args.variant, args.pdfua)), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
