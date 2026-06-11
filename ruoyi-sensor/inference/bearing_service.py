"""
轴承诊断推理服务 (port 5001)

继承 DiagnosisServiceBase，仅定义轴承特有的模型加载、诊断逻辑和前端映射。
与 gear_service.py 共享 inference_common.py 中的基类和工具函数。
不依赖 04.4_diagnose_unlabeled_target.py。
"""
from __future__ import annotations

import logging
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import torch
import torch.nn as nn

from models.resnet18_1d import ResNet1D18
from inference_common import (
    BASE_DIR, DEVICE,
    DISPLAY_POINTS,
    CONFIDENCE_MIN, CONFIDENCE_MAX,
    safe_torch_load, _clean_state_dict,
    _compute_spectrum_and_metrics, downsample_curve, safe_float,
    DiagnosisServiceBase,
)

logger = logging.getLogger("bearing_service")

BEARING_CLASS_CN_MAP = {
    "N": "正常", "normal": "正常", "healthy": "正常",
    "OR": "轴承外圈故障", "outer_ring": "轴承外圈故障", "outer": "轴承外圈故障",
    "B": "滚动体故障", "ball": "滚动体故障", "rolling_element": "滚动体故障",
    "IR": "轴承内圈故障", "inner_ring": "轴承内圈故障", "inner": "轴承内圈故障",
}


# =============================================================================
# 轴承诊断服务
# =============================================================================

class BearingDiagnosisService(DiagnosisServiceBase):
    service_name = "bearing"
    default_port = 5001
    default_data_dir = BASE_DIR / "get" / "got" / "bearing"
    model_checkpoint_path = BASE_DIR / "get" / "best_model.pth"

    # =========================================================================
    # 模型加载
    # =========================================================================

    def load_model(self) -> None:
        if not self.model_checkpoint_path.exists():
            raise FileNotFoundError(f"Bearing model checkpoint not found: {self.model_checkpoint_path}")

        ckpt = safe_torch_load(self.model_checkpoint_path, map_location=DEVICE)

        if "state_dict" in ckpt:
            state_dict = _clean_state_dict(ckpt["state_dict"])
        else:
            state_dict = _clean_state_dict(ckpt)

        if "classes" in ckpt and ckpt["classes"]:
            class_names = [str(c) for c in ckpt["classes"]]
        else:
            for k, v in state_dict.items():
                if k.endswith("fc.weight"):
                    num_classes = int(v.shape[0])
                    class_names = [f"class_{i}" for i in range(num_classes)]
                    break
            else:
                class_names = ["N", "OR", "B"]

        num_classes = len(class_names)
        params = {
            "fs": float(ckpt.get("sample_rate", ckpt.get("fs", 16000.0))),
            "win_len": int(ckpt.get("window_size", ckpt.get("win_len", 4096))),
            "stride": int(ckpt.get("stride", 4096)),
            "batch_size": int(ckpt.get("batch_size", 64)),
            "signal_key": str(ckpt.get("signal_key", "DE_time")),
            "normalize": str(ckpt.get("normalize", "zscore")),
            "num_classes": num_classes,
        }

        self.model = ResNet1D18(num_classes=num_classes).to(DEVICE)
        self.model.load_state_dict(state_dict, strict=True)
        self.model.eval()
        self.model_params = params
        self.class_names = class_names

        logger.info("Bearing model loaded: %d classes %s on %s (window=%d, stride=%d, fs=%.0f)",
                    num_classes, class_names, DEVICE, params["win_len"], params["stride"], params["fs"])

    # =========================================================================
    # 诊断执行
    # =========================================================================

    @torch.no_grad()
    def diagnose(self, raw_signal: np.ndarray, source_name: str = "") -> Dict[str, Any]:
        sig = np.asarray(raw_signal, dtype=np.float64).reshape(-1)
        win_len = int(self.model_params["win_len"])
        stride = int(self.model_params["stride"])
        batch_size = int(self.model_params.get("batch_size", 64))

        if sig.size < win_len:
            n_win = 0
        else:
            n_win = (sig.size - win_len) // stride + 1

        if n_win == 0:
            raise ValueError(f"Signal too short ({sig.size} samples) for window size {win_len}")

        from numpy.lib.stride_tricks import sliding_window_view
        windows = sliding_window_view(sig, win_len)[::stride].copy()

        mu = windows.mean(axis=1, keepdims=True)
        sigma = windows.std(axis=1, keepdims=True)
        sigma[sigma < 1e-8] = 1.0
        windows = ((windows - mu) / sigma).astype(np.float32)

        probs_all: List[torch.Tensor] = []
        seg_preds: List[int] = []
        self.model.eval()

        for i in range(0, n_win, batch_size):
            xb = torch.from_numpy(windows[i:i + batch_size]).unsqueeze(1).to(DEVICE)
            logits, _feat = self.model(xb)
            probs = torch.softmax(logits, dim=1).cpu()
            probs_all.append(probs)
            seg_preds.extend(torch.argmax(probs, dim=1).tolist())

        probs_tensor = torch.cat(probs_all, dim=0)
        mean_probs = probs_tensor.mean(dim=0).numpy()
        final_idx = int(np.argmax(mean_probs))

        seg_preds_np = np.asarray(seg_preds, dtype=np.int64)
        segment_consistency = float((seg_preds_np == final_idx).mean()) if seg_preds_np.size else 0.0

        entropy_per_segment = -torch.sum(probs_tensor * torch.log(torch.clamp(probs_tensor, min=1e-12)), dim=1)
        mean_entropy = float(entropy_per_segment.mean().item())

        confidence = float(mean_probs[final_idx])
        if confidence >= 0.80 and segment_consistency >= 0.80:
            decision_reason = "bearing_high_confidence"
        elif confidence >= 0.65 and segment_consistency >= 0.60:
            decision_reason = "bearing_moderate_confidence"
        else:
            decision_reason = "bearing_low_confidence"

        return {
            "source_name": source_name,
            "prediction_index": final_idx,
            "prediction": self.class_names[final_idx] if final_idx < len(self.class_names) else "unknown",
            "confidence": confidence,
            "mean_probs": mean_probs,
            "segment_consistency": segment_consistency,
            "num_segments": int(n_win),
            "mean_entropy": mean_entropy,
            "decision_reason": decision_reason,
            "class_names": self.class_names,
        }

    # =========================================================================
    # 前端数据映射
    # =========================================================================

    def build_frontend_payload(
        self, bearing_result: Dict[str, Any], raw_signal: np.ndarray,
        source_name: str, sample_rate: float = 16000.0,
        extra: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        sig = np.asarray(raw_signal, dtype=np.float64).reshape(-1)
        n = sig.size
        time_axis_full = np.arange(n, dtype=np.float64) / sample_rate if sample_rate > 0 else np.arange(n, dtype=np.float64)
        time_axis, time_data = downsample_curve(time_axis_full, sig, max_points=DISPLAY_POINTS)
        spectrum, metrics = _compute_spectrum_and_metrics(sig, fs=sample_rate)

        class_names = bearing_result.get("class_names", self.class_names)
        prediction = str(bearing_result["prediction"])
        prediction_cn = BEARING_CLASS_CN_MAP.get(prediction, prediction)
        confidence = float(bearing_result["confidence"])
        confidence_pct = round(float(np.clip(confidence * 100.0, CONFIDENCE_MIN, CONFIDENCE_MAX)), 2)
        mean_entropy = float(bearing_result["mean_entropy"])
        segment_consistency = float(bearing_result["segment_consistency"])
        mean_probs = np.asarray(bearing_result["mean_probs"], dtype=np.float64)

        top_probs = [
            {"class": BEARING_CLASS_CN_MAP.get(cname, cname), "probability": round(float(prob) * 100.0, 2)}
            for cname, prob in zip(class_names, mean_probs)
        ]
        top_probs.sort(key=lambda x: x["probability"], reverse=True)

        is_normal = prediction.lower() in ("n", "normal", "healthy")
        rms_value = safe_float(metrics["rms"])
        peak_value = safe_float(metrics["peak"])

        if is_normal:
            base = confidence * 100.0
            if rms_value > 5.0:
                base *= 0.85
            health_score = max(5.0, min(100.0, base))
            risk_level = "低" if health_score >= 70 else "中"
            alarm_level = "normal" if health_score >= 70 else "attention"
        else:
            severity_penalty = confidence * 100.0 * 0.85
            vibration_penalty = min(15.0, (rms_value / 10.0) * 10.0 + (peak_value / 15.0) * 5.0)
            health_score = max(1.0, 100.0 - severity_penalty - vibration_penalty)
            if health_score <= 30:
                risk_level, alarm_level = "高", "alarm"
            elif health_score <= 55:
                risk_level, alarm_level = "中", "warning"
            else:
                risk_level, alarm_level = "中", "attention"

        evidence = [
            {"title": "模型类型", "desc": f"轴承诊断模型 (ResNet1D18, {len(class_names)}类)", "type": "info", "level": "信息"},
            {"title": "决策原因", "desc": str(bearing_result["decision_reason"]), "type": "info", "level": "信息"},
            {"title": "片段一致性", "desc": f"{segment_consistency:.4f}",
             "type": "success" if segment_consistency > 0.8 else "warning",
             "level": "高" if segment_consistency > 0.8 else "中"},
            {"title": "Average entropy", "desc": f"{mean_entropy:.4f}", "type": "info", "level": "信息"},
        ]

        data: Dict[str, Any] = {
            "label": prediction_cn, "diagnosisResult": prediction_cn, "diagnosisName": prediction_cn,
            "confidence": confidence_pct, "healthIndex": int(round(health_score)),
            "riskLevel": risk_level, "alarmLevel": alarm_level,
            "diagnosisDetail": f"轴承诊断: {bearing_result['decision_reason']} | 预测:{prediction}({prediction_cn}) conf={confidence:.4f}",
            "diagnosis_detail": f"轴承诊断: {bearing_result['decision_reason']}",
            "decision_reason": str(bearing_result["decision_reason"]),
            "closedPrediction": prediction,
            "unknownRatio": 0.0, "segmentConsistency": round(segment_consistency, 6),
            "meanMahalanobis": 0.0, "meanEntropy": round(mean_entropy, 6),
            "source_name": source_name, "sourceName": source_name,
            "topProbabilities": top_probs[:len(class_names)], "evidence": evidence,
            "time_axis": time_axis, "time_data": time_data, "waveform": time_data,
            "freq_axis": spectrum["freq_hz"], "frequencyAxis": spectrum["freq_hz"],
            "freq_data": spectrum["amplitude"], "spectrum": spectrum["amplitude"],
            "rms": round(safe_float(metrics["rms"]), 6), "latestRms": round(safe_float(metrics["rms"]), 6),
            "peak": round(safe_float(metrics["peak"]), 6), "latestPeak": round(safe_float(metrics["peak"]), 6),
            "sample_rate": sample_rate, "sampleRate": sample_rate,
            "count": len(time_data), "analysis_mode": "bearing_resnet18",
            "modelType": "bearing", "modelVersion": "best_model.pth (ResNet1D18)",
            "numSegments": int(bearing_result["num_segments"]),
        }
        if extra:
            data.update(extra)
        return data

    # =========================================================================
    # 健康状态
    # =========================================================================

    def build_health_payload(self) -> Dict[str, Any]:
        return {
            "status": "ok", "device": str(DEVICE),
            "model_loaded": self.model is not None,
            "bearing_model_loaded": self.model is not None,
            "model_path": str(self.model_checkpoint_path),
            "bearing_model_path": str(self.model_checkpoint_path),
            "version": "bearing_service",
            "classes": self.class_names, "bearing_classes": self.class_names,
            "bearing_classes_cn": [BEARING_CLASS_CN_MAP.get(c, c) for c in self.class_names],
            "win_len": self.model_params.get("win_len"),
            "stride": self.model_params.get("stride"),
            "fs": self.model_params.get("fs"),
            "bearing_params": self.model_params,
        }


# =============================================================================
# 入口
# =============================================================================
if __name__ == "__main__":
    BearingDiagnosisService().run()
