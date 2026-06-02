from __future__ import annotations

import logging
from io import BytesIO
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import scipy.io
import torch
import torch.nn as nn
import uvicorn
from fastapi import FastAPI, File, HTTPException, Query, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s - %(message)s")
logger = logging.getLogger("inference_service")

BASE_DIR = Path(__file__).resolve().parent
MODEL_PATH = BASE_DIR / "get" / "best_model_classwise_maha.pth"
DATA_DIR = BASE_DIR / "get" / "got"
DEFAULT_FS = 10240.0
DISPLAY_POINTS = 2048
MODEL_INPUT_LENGTH = 1024


class VibrationDiagnosisCNN(nn.Module):
    def __init__(self, num_classes: int = 10) -> None:
        super().__init__()
        self.features = nn.Sequential(
            nn.Conv1d(1, 16, kernel_size=7, stride=2, padding=3),
            nn.BatchNorm1d(16),
            nn.ReLU(inplace=True),
            nn.MaxPool1d(2),
            nn.Conv1d(16, 32, kernel_size=5, stride=2, padding=2),
            nn.BatchNorm1d(32),
            nn.ReLU(inplace=True),
            nn.MaxPool1d(2),
            nn.Conv1d(32, 64, kernel_size=3, stride=1, padding=1),
            nn.BatchNorm1d(64),
            nn.ReLU(inplace=True),
            nn.AdaptiveAvgPool1d(1),
        )
        self.classifier = nn.Sequential(
            nn.Flatten(),
            nn.Linear(64, 32),
            nn.ReLU(inplace=True),
            nn.Dropout(0.2),
            nn.Linear(32, num_classes),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.classifier(self.features(x))


class MatFileItem(BaseModel):
    name: str
    label: str
    source_name: str


class AnalyzeResponse(BaseModel):
    success: bool
    data: Dict[str, Any]


DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
MODEL: Optional[nn.Module] = None
CLASS_NAMES: List[str] = []

app = FastAPI(title="Vibration Diagnosis Sidecar Service", version="1.0.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_credentials=True, allow_methods=["*"], allow_headers=["*"])


def _extract_signal_from_mat_dict(mat: Dict[str, Any], source_name: str = "<memory>") -> np.ndarray:
    keys = [key for key in mat.keys() if not key.startswith("__")]
    if not keys:
        raise KeyError("No valid data variables found in .mat file.")
    preferred = ["data", "signal", "vibration", "x", "X", "raw", "wave", "values"]
    selected = next((key for key in preferred if key in mat), keys[0])
    signal = np.asarray(mat[selected]).squeeze()
    if signal.size == 0:
        raise ValueError(f"Variable '{selected}' contains no samples.")
    if signal.ndim != 1:
        signal = signal.reshape(-1)
    signal = signal.astype(np.float32)
    signal = np.nan_to_num(signal, nan=0.0, posinf=0.0, neginf=0.0)
    logger.info("Loaded signal variable '%s' from %s", selected, source_name)
    return signal


def load_signal_from_mat(mat_path: Path) -> np.ndarray:
    if not mat_path.exists():
        raise FileNotFoundError(f".mat file not found: {mat_path}")
    mat = scipy.io.loadmat(mat_path.as_posix())
    return _extract_signal_from_mat_dict(mat, source_name=mat_path.name)


def crop_or_pad_signal(signal: np.ndarray, target_length: int) -> np.ndarray:
    signal = np.asarray(signal, dtype=np.float32).reshape(-1)
    if signal.size >= target_length:
        return signal[:target_length]
    padded = np.zeros(target_length, dtype=np.float32)
    padded[: signal.size] = signal
    return padded


def downsample_for_display(signal: np.ndarray, display_points: int) -> np.ndarray:
    signal = np.asarray(signal, dtype=np.float32).reshape(-1)
    if signal.size <= display_points:
        return signal.copy()
    x_old = np.linspace(0.0, 1.0, signal.size, endpoint=True)
    x_new = np.linspace(0.0, 1.0, display_points, endpoint=True)
    return np.interp(x_new, x_old, signal).astype(np.float32)


def compute_time_axis(sample_count: int, fs: float) -> np.ndarray:
    if sample_count <= 0:
        return np.array([], dtype=np.float32)
    return (np.arange(sample_count, dtype=np.float32) / fs).astype(np.float32)


def compute_fft_spectrum(signal: np.ndarray, fs: float) -> Tuple[np.ndarray, np.ndarray]:
    signal = np.asarray(signal, dtype=np.float32).reshape(-1)
    n = signal.size
    if n == 0:
        return np.array([], dtype=np.float32), np.array([], dtype=np.float32)
    signal = signal - np.mean(signal)
    fft_vals = np.fft.rfft(signal)
    amp = np.abs(fft_vals) / n
    if amp.size > 2:
        amp[1:-1] *= 2.0
    max_val = float(np.max(amp)) if amp.size else 1.0
    if max_val > 0:
        amp = amp / max_val
    freq_axis = np.fft.rfftfreq(n, d=1.0 / fs).astype(np.float32)
    return freq_axis.astype(np.float32), np.nan_to_num(amp.astype(np.float32), nan=0.0, posinf=0.0, neginf=0.0)


def load_model(model_path: Path, device: torch.device) -> Tuple[nn.Module, List[str]]:
    if not model_path.exists():
        raise FileNotFoundError(f"Model file not found: {model_path}")
    try:
        checkpoint = torch.load(model_path.as_posix(), map_location=device, weights_only=False)
    except TypeError:
        checkpoint = torch.load(model_path.as_posix(), map_location=device)
    class_names: List[str] = []
    if isinstance(checkpoint, nn.Module):
        return checkpoint.to(device).eval(), class_names
    if isinstance(checkpoint, dict):
        if isinstance(checkpoint.get("class_names"), (list, tuple)):
            class_names = [str(x) for x in checkpoint["class_names"]]
        state_dict = checkpoint.get("state_dict", checkpoint)
        inferred_num_classes = int(checkpoint.get("num_classes", 10))
        for key, value in state_dict.items():
            if isinstance(value, torch.Tensor) and value.ndim == 2 and ("classifier" in str(key) or "fc" in str(key)):
                inferred_num_classes = int(value.shape[0])
                break
        model = VibrationDiagnosisCNN(num_classes=inferred_num_classes).to(device)
        cleaned = {str(k).replace("module.", ""): v for k, v in state_dict.items()}
        model.load_state_dict(cleaned, strict=False)
        return model.eval(), class_names
    raise TypeError(f"Unsupported checkpoint type: {type(checkpoint)}")


def predict_label(model: nn.Module, signal: np.ndarray, device: torch.device, class_names: Optional[List[str]] = None) -> Tuple[str, float]:
    class_names = class_names or []
    x = torch.from_numpy(np.asarray(signal, dtype=np.float32).reshape(-1)).unsqueeze(0).unsqueeze(0).to(device)
    with torch.no_grad():
        outputs = model(x)
        if isinstance(outputs, (tuple, list)):
            outputs = outputs[0]
        if outputs.ndim == 1:
            outputs = outputs.unsqueeze(0)
        probs = torch.softmax(outputs, dim=-1)
        confidence, pred_idx = torch.max(probs, dim=-1)
        idx = int(pred_idx.item())
        label = class_names[idx] if 0 <= idx < len(class_names) else f"class_{idx}"
        return label, float(confidence.item())


def analyze_raw_signal(raw_signal: np.ndarray, source_name: str) -> Dict[str, Any]:
    display_signal = downsample_for_display(raw_signal, DISPLAY_POINTS)
    time_axis = compute_time_axis(display_signal.size, DEFAULT_FS)
    freq_axis, freq_data = compute_fft_spectrum(display_signal, DEFAULT_FS)
    model_signal = crop_or_pad_signal(raw_signal, MODEL_INPUT_LENGTH)
    label, confidence = predict_label(MODEL, model_signal, DEVICE, CLASS_NAMES)
    rms = float(np.sqrt(np.mean(np.square(raw_signal)))) if raw_signal.size else 0.0
    peak = float(np.max(np.abs(raw_signal))) if raw_signal.size else 0.0
    diagnosis = label
    if confidence >= 0.8:
        diagnosis_detail = f"模型对 {label} 的判别置信度较高。"
    else:
        diagnosis_detail = f"模型输出 {label}，建议结合人工复核。"
    health_index = max(0, min(100, int(round(100 - rms * 5))))
    risk_level = "高" if rms > 7 else "中" if rms > 4 else "低"
    return {
        "label": label,
        "confidence": round(confidence * 100.0, 2),
        "time_axis": time_axis.astype(float).tolist(),
        "time_data": display_signal.astype(float).tolist(),
        "freq_axis": freq_axis.astype(float).tolist(),
        "freq_data": freq_data.astype(float).tolist(),
        "rms": round(rms, 6),
        "peak": round(peak, 6),
        "diagnosis": diagnosis,
        "diagnosis_detail": diagnosis_detail,
        "health_index": health_index,
        "risk_level": risk_level,
        "source_name": source_name,
        "sourceName": source_name,
        "sample_rate": DEFAULT_FS,
        "sampleRate": DEFAULT_FS,
        "count": int(display_signal.size),
    }


def get_latest_mat_file(data_dir: Path) -> Path:
    mat_files = list(data_dir.glob("*.mat"))
    if not mat_files:
        raise FileNotFoundError(f"No .mat files found in: {data_dir}")
    return max(mat_files, key=lambda p: (p.stat().st_mtime, p.name))


@app.on_event("startup")
def startup_event() -> None:
    global MODEL, CLASS_NAMES
    logger.info("Loading model from %s", MODEL_PATH)
    MODEL, CLASS_NAMES = load_model(MODEL_PATH, DEVICE)
    logger.info("Model loaded successfully on %s", DEVICE)


@app.get("/health")
def health() -> Dict[str, Any]:
    return {"status": "ok", "device": str(DEVICE), "model_loaded": MODEL is not None, "model_path": str(MODEL_PATH)}


@app.get("/mat-files")
def mat_files() -> Dict[str, Any]:
    items: List[Dict[str, Any]] = []
    if DATA_DIR.exists():
        for mat_path in sorted(DATA_DIR.glob("*.mat")):
            items.append({
                "name": mat_path.stem,
                "label": mat_path.stem,
                "source_name": mat_path.name,
            })
    return {"success": True, "data": items}


@app.get("/analyze", response_model=AnalyzeResponse)
def analyze(file_name: Optional[str] = Query(default=None, min_length=1)) -> Dict[str, Any]:
    if MODEL is None:
        raise HTTPException(status_code=500, detail="Model is not loaded.")
    try:
        if file_name:
            safe_name = Path(file_name).stem
            if safe_name != file_name and file_name != Path(file_name).stem:
                raise HTTPException(status_code=400, detail="Invalid file_name.")
            mat_path = DATA_DIR / f"{safe_name}.mat"
        else:
            mat_path = get_latest_mat_file(DATA_DIR)
        raw_signal = load_signal_from_mat(mat_path)
        result = analyze_raw_signal(raw_signal, mat_path.name)
        result["analysis_mode"] = "latest" if file_name is None else "specified"
        return {"success": True, "data": result}
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/analyze/upload", response_model=AnalyzeResponse)
async def analyze_upload(file: UploadFile = File(...)) -> Dict[str, Any]:
    if MODEL is None:
        raise HTTPException(status_code=500, detail="Model is not loaded.")
    if not file.filename.lower().endswith(".mat"):
        raise HTTPException(status_code=400, detail="Only .mat files are supported.")
    content = await file.read()
    mat = scipy.io.loadmat(BytesIO(content))
    raw_signal = _extract_signal_from_mat_dict(mat, source_name=file.filename)
    result = analyze_raw_signal(raw_signal, file.filename)
    return {"success": True, "data": result}


@app.post("/infer")
def infer(payload: Dict[str, Any]) -> Dict[str, Any]:
    if MODEL is None:
        raise HTTPException(status_code=500, detail="Model is not loaded.")
    file_path = str(payload.get("filePath") or "")
    if not file_path:
        raise HTTPException(status_code=400, detail="filePath is required.")
    mat_path = Path(file_path)
    raw_signal = load_signal_from_mat(mat_path)
    result = analyze_raw_signal(raw_signal, mat_path.name)
    result.update({
        "analysis_mode": payload.get("analysisMode") or "current",
        "deviceCode": payload.get("deviceCode"),
        "filename": payload.get("filename") or mat_path.name,
        "batchId": payload.get("batchId"),
        "sampleTime": payload.get("sampleTime"),
        "modelVersion": "best_model_classwise_maha.pth",
    })
    return {"success": True, "data": result}


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=5000, reload=False)
