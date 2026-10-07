FROM python:3.11-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg \
 && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu \
 && pip install --no-cache-dir fastapi "uvicorn[standard]" num2words numpy omegaconf

WORKDIR /app
COPY app.py .

ENV TORCH_HOME=/data/torch \
    CACHE_DIR=/data/cache

EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
