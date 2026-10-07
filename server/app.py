"""Сервер озвучки для «Читалки на слух».

POST /tts  {"text": "...", "speaker": "xenia"}  ->  audio/ogg (opus)
Авторизация: заголовок  Authorization: Bearer <TTS_TOKEN>
"""
import base64
import hashlib
import json
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
MODEL_ID = os.environ.get("SILERO_MODEL", "v5_5_ru")
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


LAT = {
    "sh": "ш", "ch": "ч", "zh": "ж", "th": "т", "ph": "ф", "kh": "х", "ts": "ц",
    "ya": "я", "yu": "ю", "ee": "и", "oo": "у", "ck": "к", "qu": "кв",
}
LAT1 = {
    "a": "а", "b": "б", "c": "к", "d": "д", "e": "е", "f": "ф", "g": "г", "h": "х",
    "i": "и", "j": "дж", "k": "к", "l": "л", "m": "м", "n": "н", "o": "о", "p": "п",
    "q": "к", "r": "р", "s": "с", "t": "т", "u": "у", "v": "в", "w": "в", "x": "кс",
    "y": "и", "z": "з",
}
CYR = re.compile(r"[А-Яа-яЁё]")


def translit(text: str) -> str:
    """Латиницу — в кириллицу: русская модель Silero латинские буквы не читает."""
    def word(m: re.Match) -> str:
        w = m.group(0).lower()
        for k, v in LAT.items():
            w = w.replace(k, v)
        return "".join(LAT1.get(c, c) for c in w)

    return re.sub(r"[A-Za-z]+", word, text)


def clean(text: str) -> str:
    text = spell_numbers(text)
    text = translit(text)
    text = text.replace("…", "...").replace("—", " - ").replace("–", " - ")
    text = re.sub(r"[^А-Яа-яЁё\s.,!?;:()\-«»\"']", " ", text)
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


SENTENCE_RE = re.compile(r"[^\n]+?(?:[.!?…]+[\"»”)']*(?=\s|$)|$)", re.M)


def sentences(text: str):
    """Предложения исходного текста с позициями: [(начало, конец), ...]."""
    out = []
    for m in SENTENCE_RE.finditer(text):
        s, e = m.start(), m.end()
        while s < e and text[s].isspace():
            s += 1
        while e > s and text[e - 1].isspace():
            e -= 1
        if e > s:
            out.append((s, e))
    return out


def tts_piece(piece: str, speaker: str) -> np.ndarray:
    """Озвучивает кусок; если Silero не справился — тишина вместо ошибки на весь фрагмент."""
    if not CYR.search(piece):
        return np.zeros(int(SAMPLE_RATE * 0.1), dtype=np.float32)
    try:
        return _apply(piece, speaker)
    except Exception as e:
        print(f"silero failed on {piece[:80]!r}: {e}", flush=True)
    try:  # вторая попытка: только буквы и простая пунктуация
        simple = re.sub(r"[^А-Яа-яЁё\s.,!?]", " ", piece)
        simple = re.sub(r"\s+", " ", simple).strip()
        if CYR.search(simple):
            return _apply(simple, speaker)
    except Exception as e:
        print(f"silero failed again: {e}", flush=True)
    return np.zeros(int(SAMPLE_RATE * 0.3), dtype=np.float32)


def _apply(piece: str, speaker: str) -> np.ndarray:
    global ACCENT_ARGS
    with model_lock:
        try:
            audio = model.apply_tts(text=piece, speaker=speaker, sample_rate=SAMPLE_RATE, **ACCENT_ARGS)
        except TypeError:
            ACCENT_ARGS = {}  # новые модели сами ставят ударения и могут не знать этих параметров
            audio = model.apply_tts(text=piece, speaker=speaker, sample_rate=SAMPLE_RATE)
    return audio.numpy().astype(np.float32)


ACCENT_ARGS = {"put_accent": True, "put_yo": True}
SENTENCE_PAUSE = 0.18


def synthesize_timed(raw: str, speaker: str):
    """Озвучивает текст по предложениям.

    Возвращает аудио и тайминги [[начало_символа, конец_символа, начало_сек, конец_сек], ...]
    в координатах исходного текста — по ним приложение подсвечивает слова.
    """
    pause = np.zeros(int(SAMPLE_RATE * SENTENCE_PAUSE), dtype=np.float32)
    parts, timing, t = [], [], 0.0
    for s, e in sentences(raw):
        text = clean(raw[s:e])
        if not CYR.search(text):
            continue
        audio = np.concatenate([tts_piece(p, speaker) for p in chunks(text)])
        dur = len(audio) / SAMPLE_RATE
        timing.append([s, e, round(t, 3), round(t + dur, 3)])
        parts += [audio, pause]
        t += dur + SENTENCE_PAUSE
    audio = np.concatenate(parts) if parts else pause
    return audio, timing


def encode_opus(samples: np.ndarray, path: str) -> None:
    pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype(np.int16).tobytes()
    tmp = path + ".tmp"
    proc = subprocess.run(
        [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "s16le", "-ar", str(SAMPLE_RATE), "-ac", "1", "-i", "pipe:0",
            "-c:a", "libopus", "-b:a", "64k", "-application", "audio",
            "-f", "ogg", tmp,
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

    key = hashlib.sha1(f"v3|{MODEL_ID}|{req.speaker}|{req.text}".encode()).hexdigest()
    path = os.path.join(CACHE_DIR, key + ".ogg")
    meta = os.path.join(CACHE_DIR, key + ".json")
    if not (os.path.exists(path) and os.path.exists(meta)):
        try:
            audio, timing = synthesize_timed(req.text, req.speaker)
            with open(meta, "w") as f:
                json.dump(timing, f)
            encode_opus(audio, path)
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"tts failed: {e}")
    with open(meta) as f:
        timing_b64 = base64.b64encode(f.read().encode()).decode()
    return FileResponse(path, media_type="audio/ogg", headers={"X-Timing": timing_b64})
