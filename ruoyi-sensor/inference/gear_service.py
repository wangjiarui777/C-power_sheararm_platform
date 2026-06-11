"""
齿轮诊断推理服务 (port 5000)

继承 DiagnosisServiceBase，仅定义齿轮特有的模型加载、诊断逻辑和前端映射。
与 bearing_service.py 共享 inference_common.py 中的基类和工具函数。
"""
from __future__ import annotations

import importlib.util
import logging
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import torch
from fastapi import HTTPException

from models.wdcnn_mech_dg2 import WDCNNMechDG
from inference_common import (
    BASE_DIR, DEVICE,
    DISPLAY_POINTS,
    CONFIDENCE_MIN, CONFIDENCE_MAX,
    safe_torch_load, _ensure_json_serializable,
    _compute_spectrum_and_metrics,
    DiagnosisServiceBase,
)

# =============================================================================
# v6 诊断引擎动态导入（齿轮专用）
# =============================================================================
_v6_spec = importlib.util.spec_from_file_location(
    "diagnose_v6",
    str(BASE_DIR / "04.4_diagnose_unlabeled_target.py"),
)
v6 = importlib.util.module_from_spec(_v6_spec)
_v6_spec.loader.exec_module(v6)

logger = logging.getLogger("gear_service")


# =============================================================================
# 齿轮诊断服务
# =============================================================================

class GearDiagnosisService(DiagnosisServiceBase):
    service_name = "gear"
    default_port = 5000
    default_data_dir = BASE_DIR / "get" / "got" / "gear"
    model_checkpoint_path = BASE_DIR / "get" / "best_model_classwise_maha.pth"

    # ---- v6 阈值配置（齿轮专用） ----
    _class_file_unk_overrides: Dict[str, float] = {}
    _mean_maha_accept_overrides: Dict[str, float] = {}
    _known_fault_protect_classes: List[str] = []

    # =========================================================================
    # 模型加载
    # =========================================================================

    def load_model(self) -> None:
        if not self.model_checkpoint_path.exists():
            raise FileNotFoundError(f"Gear model checkpoint not found: {self.model_checkpoint_path}")

        ckpt = safe_torch_load(self.model_checkpoint_path, map_location=DEVICE)
        params = ckpt["params"]
        class_names = [str(x) for x in ckpt["classes"]]
        num_classes = len(class_names)
        classwise_cfg = ckpt["classwise_open_set"]

        self.model = WDCNNMechDG(
            num_classes=num_classes, num_domains=2,
            fs=float(params["fs"]), win_len=int(params["win_len"]), feat_dim=int(params["feat_dim"]),
        ).to(DEVICE)
        self.model.load_state_dict(ckpt["model_state"])
        self.model.eval()
        self.model_params = params
        self.class_names = class_names
        # 存储齿轮特有的 classwise_cfg
        self._classwise_cfg = classwise_cfg

        # 配置 v6 阈值
        self._class_file_unk_overrides = v6.parse_class_threshold_overrides(
            "healthy:0.70,single_pitting:0.85,multi_pitting:0.85,single_spalling:0.85")
        self._mean_maha_accept_overrides = v6.parse_maha_accept_overrides(
            "single_pitting:30,multi_pitting:25,single_spalling:20")
        self._known_fault_protect_classes = v6.parse_protect_classes(
            "single_pitting,multi_pitting,single_spalling")

        logger.info("Gear model loaded: %d classes %s on %s", num_classes, class_names, DEVICE)

    # =========================================================================
    # 诊断执行
    # =========================================================================

    def diagnose(self, raw_signal: np.ndarray, source_name: str = "") -> Dict[str, Any]:
        sig = np.asarray(raw_signal, dtype=np.float32).reshape(-1)
        win_len = int(self.model_params["win_len"])
        stride = int(self.model_params["stride"])
        if v6.count_windows(len(sig), win_len, stride) <= 0:
            raise ValueError(f"Signal too short ({sig.size} samples) for gear window size {win_len}")

        return v6.diagnose_signal_array(
            model=self.model, sig=sig, device=DEVICE,
            win_len=win_len, stride=stride,
            batch_size=int(self.model_params.get("batch_size", 128)),
            class_names=self.class_names, classwise_cfg=self._classwise_cfg,
            class_file_unk_overrides=self._class_file_unk_overrides,
            mean_maha_accept_overrides=self._mean_maha_accept_overrides,
            fs=float(self.model_params.get("fs", 5120.0)),
            unknown_vote_required=3,
            healthy_conf_protect=0.80, healthy_unknown_ratio_allow=1.01,
            known_fault_conf_protect=0.99, known_fault_unknown_ratio_allow=None,
            known_fault_mean_votes_allow=2.0,
            known_fault_protect_classes=self._known_fault_protect_classes,
            maha_accept_min_conf=0.85, maha_accept_mean_votes_allow=3.10,
            file_label=source_name,
        )

    # =========================================================================
    # 前端数据映射
    # =========================================================================

    def build_frontend_payload(
        self, v6_result: Dict[str, Any], raw_signal: np.ndarray,
        source_name: str, sample_rate: float = 5120.0,
        extra: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        final_pred = str(v6_result["final_prediction"])
        final_pred_cn = str(v6_result.get("final_result_cn", final_pred))
        closed_pred = str(v6_result["closed_prediction"])
        confidence = float(v6_result["closed_confidence"])
        health_score = float(v6_result["health_score"])
        alarm_level = str(v6_result["alarm_level"])
        decision_reason = str(v6_result["decision_reason"])
        decision_reason_cn = v6.translate_decision_reason(decision_reason)

        sig = np.asarray(raw_signal, dtype=np.float64).reshape(-1)
        n = sig.size
        time_axis_full = np.arange(n, dtype=np.float64) / sample_rate if sample_rate > 0 else np.arange(n, dtype=np.float64)
        time_axis, time_data = v6.downsample_curve(time_axis_full, sig, max_points=DISPLAY_POINTS)

        spectrum, metrics = _compute_spectrum_and_metrics(sig, fs=sample_rate)
        rms = v6.safe_float(metrics["rms"])
        peak = v6.safe_float(metrics["peak"])

        evidence = self._build_evidence_list(v6_result)
        top_probs = self._build_top_probabilities(v6_result)
        confidence_pct = round(float(np.clip(confidence * 100.0, CONFIDENCE_MIN, CONFIDENCE_MAX)), 2)
        alarm_to_risk = {"normal": "低", "attention": "中", "warning": "中", "alarm": "高"}

        data: Dict[str, Any] = {
            "label": final_pred_cn, "diagnosisResult": final_pred_cn, "diagnosisName": final_pred_cn,
            "confidence": confidence_pct, "healthIndex": int(round(health_score)),
            "riskLevel": alarm_to_risk.get(alarm_level, "中"), "alarmLevel": alarm_level,
            "diagnosisDetail": f"v6决策: {decision_reason} | 闭集:{closed_pred} conf={confidence:.4f} | unk_ratio={v6_result['unknown_ratio']:.4f}",
            "diagnosis_detail": f"v6决策: {decision_reason_cn}", "decision_reason": decision_reason,
            "closedPrediction": closed_pred,
            "unknownRatio": round(float(v6_result["unknown_ratio"]), 6),
            "segmentConsistency": round(float(v6_result["segment_consistency"]), 6),
            "meanMahalanobis": round(float(v6_result["mean_mahalanobis"]), 6),
            "meanEntropy": round(float(v6_result["mean_entropy"]), 6),
            "source_name": source_name, "sourceName": source_name,
            "topProbabilities": top_probs[:3], "evidence": evidence,
            "time_axis": time_axis, "time_data": time_data, "waveform": time_data,
            "freq_axis": spectrum["freq_hz"], "frequencyAxis": spectrum["freq_hz"],
            "freq_data": spectrum["amplitude"], "spectrum": spectrum["amplitude"],
            "rms": round(rms, 6), "latestRms": round(rms, 6),
            "peak": round(peak, 6), "latestPeak": round(peak, 6),
            "sample_rate": sample_rate, "sampleRate": sample_rate,
            "count": len(time_data), "analysis_mode": "v6_mahalanobis",
        }
        if extra:
            data.update(extra)
        return data

    def _build_evidence_list(self, v6_result: Dict[str, Any]) -> List[Dict[str, Any]]:
        closed_pred = str(v6_result["closed_prediction"])
        confidence = float(v6_result["closed_confidence"])
        unknown_ratio = float(v6_result["unknown_ratio"])
        seg_consistency = float(v6_result["segment_consistency"])
        mean_maha = float(v6_result["mean_mahalanobis"])
        mean_entropy = float(v6_result["mean_entropy"])
        class_file_unk_thr = v6_result.get("class_file_unknown_ratio_threshold", "N/A")
        decision_reason = str(v6_result["decision_reason"])

        evidence: List[Dict[str, Any]] = [
            {"title": "决策原因", "desc": decision_reason, "type": "info", "level": "信息"},
            {"title": "闭集预测", "desc": f"{closed_pred} (置信度 {confidence:.4f})", "type": "info", "level": "信息"},
            {"title": "Unknown比例", "desc": f"{unknown_ratio:.4f} / 阈值 {class_file_unk_thr}",
             "type": "warning" if unknown_ratio > 0.3 else "info",
             "level": "高" if unknown_ratio > 0.5 else "中" if unknown_ratio > 0.3 else "低"},
            {"title": "片段一致性", "desc": f"{seg_consistency:.4f}",
             "type": "success" if seg_consistency > 0.8 else "warning",
             "level": "高" if seg_consistency > 0.8 else "中"},
            {"title": "Mean Mahalanobis", "desc": f"{mean_maha:.4f}", "type": "info", "level": "信息"},
            {"title": "Average entropy", "desc": f"{mean_entropy:.4f}", "type": "info", "level": "信息"},
        ]

        ef = v6_result.get("evidence_frequencies", [])
        if ef:
            freq_strs = [f"{f:.1f} Hz" for f, _ in ef[:3]]
            evidence.append({"title": "特征频率", "desc": ", ".join(freq_strs) if freq_strs else "无",
                              "type": "info", "level": "信息"})
        return evidence

    def _build_top_probabilities(self, v6_result: Dict[str, Any]) -> List[Dict[str, Any]]:
        top_probs: List[Dict[str, Any]] = []
        for cname in self.class_names:
            key = f"prob_{cname}"
            if key in v6_result:
                top_probs.append({"class": cname, "probability": round(float(v6_result[key]) * 100.0, 2)})
        top_probs.sort(key=lambda x: x["probability"], reverse=True)
        return top_probs

    # =========================================================================
    # 健康状态
    # =========================================================================

    def build_health_payload(self) -> Dict[str, Any]:
        return {
            "status": "ok", "device": str(DEVICE),
            "model_loaded": self.model is not None,
            "gear_model_loaded": self.model is not None,
            "model_path": str(self.model_checkpoint_path),
            "gear_model_path": str(self.model_checkpoint_path),
            "version": "gear_service",
            "classes": self.class_names, "gear_classes": self.class_names,
            "win_len": self.model_params.get("win_len"),
            "stride": self.model_params.get("stride"),
            "fs": self.model_params.get("fs"),
            "gear_params": self.model_params,
        }

    # =========================================================================
    # 向后兼容：拒绝非 gear 的 model_type
    # =========================================================================

    def _validate_model_type(self, model_type: Optional[str]) -> None:
        if model_type is not None and str(model_type).strip().lower() != "gear":
            raise HTTPException(
                status_code=400,
                detail="This is the gear diagnosis service (port 5000). For bearing diagnosis, use port 5001.",
            )


# =============================================================================
# 入口
# =============================================================================
if __name__ == "__main__":
    GearDiagnosisService().run()
