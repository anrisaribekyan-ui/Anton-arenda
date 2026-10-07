"""Сервер озвучки для «Читалки на слух».

POST /tts  {"text": "...", "speaker": "xenia"}  ->  audio/ogg (opus)
Авторизация: заголовок  Authorization: Bearer <TTS_TOKEN>
"""
import hashlib
import os
import re
import subprocess
import threading
import time

import numpy as np
import torch
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import FileResponse
from num2words import num2words
from pydantic import BaseModel

TOKEN = os.environ.get("TTS_TOKEN", "")
MODEL_ID = os.environ.get("SILERO_MODEL", "v4_ru")
CACHE_DIR = os.environ.get("CACHE_DIR", "/data/cache")
CACHE_DAYS = float(os.environ.get("CACHE_DAYS", "14"))
SAMPLE_RATE = 48000
SPEAKERS = {"aidar", "baya", "kseniya", "xenia", "eugene"}

os.makedirs(CACHE_DIR, exist_ok=True)
torch.set_num_threads(int(os.environ.get("TORCH_THREADS", "4")))

model, _ = torch.hub.load(
    repo_or_dir="snakers4/silero-models",
    model="silero_tts",
    language="ru",
    speaker=MODEL_ID,
    trust_repo=True,
)
model.to(torch.device("cpu"))
model_lock = threading.Lock()

app = FastAPI(title="Silero TTS")


class TtsRequest(BaseModel):
    text: str
    speaker: str = "xenia"


def spell_numbers(text: str) -> str:
    # «10 000» -> «10000», потом каждое число прописью: Silero не читает цифры.
    text = re.sub(r"(\d)[  ](?=\d{3}\b)", r"\1", text)

    def repl(m: re.Match) -> str:
        try:
            return " " + num2words(int(m.group(0)), lang="ru") + " "
        except Exception:
            return m.group(0)

    return re.sub(r"\d+", repl, text)


def clean(text: str) -> str:
    text = spell_numbers(text)
    text = text.replace("…", "...").replace("—", " - ").replace("–", " - ")
    text = re.sub(r"[^\w\s.,!?;:()\-«»\"']", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def chunks(text: str, limit: int = 600):
    """Режем по предложениям, чтобы Silero не упёрся в лимит длины."""
    out, cur = [], ""
    for sent in re.split(r"(?<=[.!?])\s+", text):
        while len(sent) > limit:  # очень длинное предложение режем по словам
            cut = sent.rfind(" ", 0, limit)
            cut = cut if cut > 0 else limit
            if cur:
                out.append(cur)
                cur = ""
            out.append(sent[:cut])
            sent = sent[cut:].strip()
        if len(cur) + len(sent) + 1 > limit and cur:
            out.append(cur)
            cur = sent
        else:
            cur = f"{cur} {sent}".strip()
    if cur:
        out.append(cur)
    return out


def synthesize(text: str, speaker: str) -> np.ndarray:
    pause = np.zeros(int(SAMPLE_RATE * 0.25), dtype=np.float32)
    parts = []
    for piece in chunks(text):
        if not re.search(r"[A-Za-zА-Яа-яЁё]", piece):
            continue
        with model_lock:
            audio = model.apply_tts(
                text=piece,
                speaker=speaker,
                sample_rate=SAMPLE_RATE,
                put_accent=True,
                put_yo=True,
            )
        parts += [audio.numpy().astype(np.float32), pause]
    return np.concatenate(parts) if parts else pause


def encode_opus(samples: np.ndarray, path: str) -> None:
    pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype(np.int16).tobytes()
    tmp = path + ".tmp"
    proc = subprocess.run(
        [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "s16le", "-ar", str(SAMPLE_RATE), "-ac", "1", "-i", "pipe:0",
            "-ar", "24000", "-c:a", "libopus", "-b:a", "32k",
            "-application", "voip", "-f", "ogg", tmp,
        ],
        input=pcm,
        capture_output=True,
    )
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.decode(errors="ignore")[:300])
    os.replace(tmp, path)


def cleanup_loop() -> None:
    while True:
        limit = time.time() - CACHE_DAYS * 86400
        for name in os.listdir(CACHE_DIR):
            p = os.path.join(CACHE_DIR, name)
            try:
                if os.path.getmtime(p) < limit:
                    os.remove(p)
            except OSError:
                pass
        time.sleep(6 * 3600)


threading.Thread(target=cleanup_loop, daemon=True).start()


@app.get("/health")
def health():
    return {"ok": True, "model": MODEL_ID, "speakers": sorted(SPEAKERS)}


@app.post("/tts")
def tts(req: TtsRequest, authorization: str = Header(default="")):
    if TOKEN and authorization != f"Bearer {TOKEN}":
        raise HTTPException(status_code=401, detail="bad token")
    if req.speaker not in SPEAKERS:
        raise HTTPException(status_code=400, detail="unknown speaker")
    if len(req.text) > 5000:
        raise HTTPException(status_code=413, detail="text too long")

    text = clean(req.text)
    key = hashlib.sha1(f"{MODEL_ID}|{req.speaker}|{text}".encode()).hexdigest()
    path = os.path.join(CACHE_DIR, key + ".ogg")
    if not os.path.exists(path):
        try:
            encode_opus(synthesize(text, req.speaker), path)
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"tts failed: {e}")
    return FileResponse(path, media_type="audio/ogg")
