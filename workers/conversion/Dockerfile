# DMS conversion worker (DMS-115, ADR 0042): LibreOffice (headless) behind a small HTTP API.
# Converts office files into PDF/A-2b and HTML pages into Word documents. A separate image from the rendering worker:
# LibreOffice is large, slow to start and has a different attack surface than WeasyPrint; the rendering image stays
# small and its PDF bytes reproducible.
# Pinned like the rendering worker: base image by digest, LibreOffice and fonts by Debian package version. Fonts with the
# metrics of the common Office fonts (Liberation: Arial/Times New Roman/Courier New; Carlito: Calibri; Caladea: Cambria)
# keep line and page breaks of Word documents close to the original.
FROM python:3.13-slim-trixie@sha256:b3575dcf816cdacc5292df77a6e3e59b8941631078373a045efa2ac1e9c5c3b2

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libreoffice-writer-nogui=4:25.2.3-2+deb13u8 \
      libreoffice-calc-nogui=4:25.2.3-2+deb13u8 \
      libreoffice-impress-nogui=4:25.2.3-2+deb13u8 \
      libreoffice-draw-nogui=4:25.2.3-2+deb13u8 \
      fonts-noto-core=20201225-2 \
      fonts-liberation2=1:2.1.5-3 \
      fonts-crosextra-carlito=20230309-2 \
      fonts-crosextra-caladea=20200211-2 \
 && rm -rf /var/lib/apt/lists/* \
 && fc-cache -f

ENV TZ=UTC LANG=C.UTF-8 HOME=/tmp PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY server.py /worker/server.py
RUN useradd --uid 10002 --no-create-home converter
USER converter
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://localhost:8080/health', timeout=2)"]
ENTRYPOINT ["python", "/worker/server.py", "--port", "8080"]
