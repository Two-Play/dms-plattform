# DMS rendering worker (DMS-44, ADR 0008, ADR 0025): WeasyPrint behind a small HTTP API.
# Everything that influences PDF bytes is pinned as in spike DMS-23: base image by digest, Pango/HarfBuzz/
# FreeType/fontconfig and fonts by Debian package version, Python packages by version and hash.
FROM python:3.13-slim-trixie@sha256:b3575dcf816cdacc5292df77a6e3e59b8941631078373a045efa2ac1e9c5c3b2

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libpango-1.0-0=1.56.3-1 \
      libpangoft2-1.0-0=1.56.3-1 \
      libharfbuzz0b=10.2.0-1+deb13u1 \
      libharfbuzz-subset0=10.2.0-1+deb13u1 \
      libfreetype6=2.13.3+dfsg-1+deb13u1 \
      libfontconfig1=2.15.0-2.3 \
      fontconfig=2.15.0-2.3 \
      fonts-noto-core=20201225-2 \
 && rm -rf /var/lib/apt/lists/* \
 && fc-cache -f

COPY requirements.txt /worker/requirements.txt
RUN pip install --no-cache-dir --root-user-action=ignore --require-hashes --no-deps -r /worker/requirements.txt

ENV TZ=UTC LANG=C.UTF-8 PYTHONDONTWRITEBYTECODE=1 PYTHONHASHSEED=0 PYTHONUNBUFFERED=1
COPY server.py /worker/server.py
RUN useradd --uid 10001 --no-create-home renderer
USER renderer
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://localhost:8080/health', timeout=2)"]
ENTRYPOINT ["python", "/worker/server.py", "--port", "8080"]
