# 04_diagnose_unlabeled_target.py
# -*- coding: utf-8 -*-

"""
无标签目标测点诊断脚本 v6

核心功能：
1. 使用类别级开集阈值；
2. 使用 Mahalanobis 距离替代普通 prototype 欧氏距离；
3. 支持类别级文件 unknown_ratio 阈值手动覆盖；
4. 保留 healthy protection；
5. 保留 known fault high-confidence protection；
6. 新增：文件级 mean_mahalanobis 接收机制；
7. 适用于新模型：
   WDCNN + FFT机理频带分支 + 包络谱分支 + 部件解耦分支 + SupCon。

推荐 mean_mahalanobis 接收阈值：
single_pitting:30,multi_pitting:25,single_spalling:15

使用前提：
必须先运行 05_calibrate_classwise_mahalanobis.py，
生成 best_model_classwise_maha.pth。
"""

import argparse
import json
import hashlib
from pathlib import Path

import numpy as np
import pandas as pd
from tqdm import tqdm

import torch

from utils_signal import (
    load_npy_signal,
    zscore_1d,
    count_windows,
    get_window,
    top_fft_peaks,
)

from models.wdcnn_mech_dg2 import WDCNNMechDG


def safe_torch_load(path, map_location):
    """
    兼容不同 PyTorch 版本的 torch.load。
    """
    try:
        checkpoint = torch.load(path, map_location=map_location, weights_only=True)
    except TypeError as exc:
        raise RuntimeError("PyTorch with weights_only=True support is required") from exc
    if not isinstance(checkpoint, dict):
        raise ValueError("model artifact must be a tensor state dictionary checkpoint")
    return checkpoint


def parse_protect_classes(s):
    """
    解析需要保护的已知故障类别。

    示例：
    "single_pitting,multi_pitting,single_spalling"
    """
    if s is None:
        return []

    s = s.strip()

    if s == "":
        return []

    return [x.strip() for x in s.split(",") if x.strip()]


def parse_class_threshold_overrides(s):
    """
    解析类别级阈值覆盖参数。

    示例：
    "healthy:0.70,single_pitting:0.85,multi_pitting:0.70,single_spalling:0.85"
    """
    overrides = {}

    if s is None:
        return overrides

    s = s.strip()

    if s == "":
        return overrides

    pairs = s.split(",")

    for pair in pairs:
        pair = pair.strip()

        if pair == "":
            continue

        if ":" not in pair:
            raise ValueError(
                f"类别阈值覆盖格式错误: {pair}\n"
                f"正确格式示例: healthy:0.70,single_pitting:0.85"
            )

        name, value = pair.split(":", 1)
        name = name.strip()
        value = float(value.strip())

        if value < 0 or value > 1.5:
            raise ValueError(f"阈值超出合理范围: {name}:{value}")

        overrides[name] = value

    return overrides


def parse_maha_accept_overrides(s):
    """
    解析文件级 mean_mahalanobis 接收阈值。

    示例：
    "single_pitting:30,multi_pitting:25,single_spalling:15"

    含义：
    如果 closed_prediction = single_pitting，
    且文件 mean_mahalanobis <= 30，
    则可以优先接受 single_pitting。
    """
    overrides = {}

    if s is None:
        return overrides

    s = s.strip()

    if s == "":
        return overrides

    pairs = s.split(",")

    for pair in pairs:
        pair = pair.strip()

        if pair == "":
            continue

        if ":" not in pair:
            raise ValueError(
                f"Mahalanobis 接收阈值格式错误: {pair}\n"
                f"正确格式示例: single_pitting:30,multi_pitting:25,single_spalling:15"
            )

        name, value = pair.split(":", 1)
        name = name.strip()
        value = float(value.strip())

        if value <= 0:
            raise ValueError(f"Mahalanobis 阈值必须大于 0: {name}:{value}")

        overrides[name] = value

    return overrides


def l2_normalize_torch(x, eps=1e-12):
    return x / (torch.norm(x, dim=1, keepdim=True) + eps)


def mahalanobis_distance_torch(feats, mean, inv_cov):
    """
    计算 Mahalanobis 距离。

    feats: [N, D]
    mean: [D]
    inv_cov: [D, D]

    return:
    dist: [N]
    """
    diff = feats - mean.unsqueeze(0)
    d2 = torch.einsum("nd,dd,nd->n", diff, inv_cov, diff)
    d2 = torch.clamp(d2, min=0.0)
    dist = torch.sqrt(d2 + 1e-12)
    return dist


def get_class_file_unknown_threshold(
    class_name,
    class_id,
    classwise_cfg,
    class_file_unk_overrides,
):
    """
    获取某个闭集预测类别对应的文件级 unknown_ratio 阈值。

    优先级：
    1. 如果命令行指定了覆盖阈值，则使用覆盖阈值；
    2. 否则使用 checkpoint 中保存的类别级阈值。
    """
    if class_name in class_file_unk_overrides:
        return float(class_file_unk_overrides[class_name]), "manual_override"

    file_unknown_ratio_thresholds = classwise_cfg["file_unknown_ratio_thresholds"]
    return float(file_unknown_ratio_thresholds[class_id]), "calibrated"


def get_mean_maha_accept_threshold(
    class_name,
    mean_maha_accept_overrides,
):
    """
    获取某个类别的文件级 mean_mahalanobis 接收阈值。

    如果没有设置，则返回 None。
    """
    if class_name in mean_maha_accept_overrides:
        return float(mean_maha_accept_overrides[class_name])

    return None


def segment_unknown_mask_classwise_maha(
    probs_all,
    feats_all,
    closed_class_id,
    classwise_cfg,
    device,
    unknown_vote_required=None,
):
    """
    类别级 Mahalanobis 切片 unknown 判断。

    对当前文件：
    1. 先得到 closed_class_id；
    2. 使用 closed_class_id 对应类别的阈值；
    3. 每个切片计算三个异常条件：
       - max_prob < class_conf_threshold
       - entropy > class_entropy_threshold
       - mahalanobis > class_maha_threshold
    4. 至少满足 unknown_vote_required 个条件才判为 unknown。
    """
    if unknown_vote_required is None:
        unknown_vote_required = int(classwise_cfg.get("unknown_vote_required", 2))

    feature_l2_normalize = bool(classwise_cfg.get("feature_l2_normalize", True))

    class_means = torch.tensor(
        classwise_cfg["class_means"],
        dtype=torch.float32,
        device=device,
    )

    class_inv_covs = torch.tensor(
        classwise_cfg["class_inv_covs"],
        dtype=torch.float32,
        device=device,
    )

    conf_thresholds = classwise_cfg["conf_thresholds"]
    entropy_thresholds = classwise_cfg["entropy_thresholds"]
    maha_thresholds = classwise_cfg["maha_thresholds"]

    probs_all = probs_all.to(device)
    feats_all = feats_all.to(device)

    if feature_l2_normalize:
        feats_all = l2_normalize_torch(feats_all)

    max_prob = probs_all.max(dim=1).values
    entropy = -(probs_all * torch.log(probs_all + 1e-8)).sum(dim=1)

    mean_c = class_means[closed_class_id]
    inv_cov_c = class_inv_covs[closed_class_id]

    maha = mahalanobis_distance_torch(
        feats=feats_all,
        mean=mean_c,
        inv_cov=inv_cov_c,
    )

    conf_thr = float(conf_thresholds[closed_class_id])
    entropy_thr = float(entropy_thresholds[closed_class_id])
    maha_thr = float(maha_thresholds[closed_class_id])

    cond_low_conf = max_prob < conf_thr
    cond_high_entropy = entropy > entropy_thr
    cond_far_maha = maha > maha_thr

    unknown_votes = (
        cond_low_conf.long()
        + cond_high_entropy.long()
        + cond_far_maha.long()
    )

    unknown_mask = unknown_votes >= int(unknown_vote_required)

    info = {
        "max_prob": max_prob.detach().cpu(),
        "entropy": entropy.detach().cpu(),
        "maha": maha.detach().cpu(),

        "cond_low_conf": cond_low_conf.detach().cpu(),
        "cond_high_entropy": cond_high_entropy.detach().cpu(),
        "cond_far_maha": cond_far_maha.detach().cpu(),

        "unknown_votes": unknown_votes.detach().cpu(),

        "class_conf_threshold": conf_thr,
        "class_entropy_threshold": entropy_thr,
        "class_maha_threshold": maha_thr,
        "unknown_vote_required": int(unknown_vote_required),
    }

    return unknown_mask.detach().cpu(), info


def decide_file_prediction_classwise_maha_v6(
    mean_prob,
    probs_all,
    unknown_mask,
    unk_info,
    class_names,
    classwise_cfg,
    closed_class_id,
    class_file_unk_overrides,
    mean_maha_accept_overrides,
    healthy_conf_protect=0.80,
    healthy_unknown_ratio_allow=1.01,
    known_fault_conf_protect=0.99,
    known_fault_unknown_ratio_allow=None,
    known_fault_mean_votes_allow=2.0,
    known_fault_protect_classes=None,
    maha_accept_min_conf=0.90,
    maha_accept_mean_votes_allow=3.10,
):
    """
    文件级最终决策 v6。

    决策顺序：

    1. healthy protection
       降低健康样本误报警。

    2. mean_mahalanobis_accept
       新增规则：
       如果 closed_prediction 是已知故障，
       且 closed_confidence 较高，
       且 mean_mahalanobis 小于该类别文件级阈值，
       则优先接受该已知故障。
       该规则用于解决：
       新模型下 single_pitting / multi_pitting / single_spalling
       被 unknown_ratio 过度拒识的问题。

    3. known_fault_high_confidence_protection
       旧版高置信已知故障保护。

    4. classwise_high_unknown_ratio
       类别级 unknown 拒识。

    5. classwise_threshold_accept
       未触发拒识，则接受闭集预测。
    """
    if known_fault_protect_classes is None:
        known_fault_protect_classes = [
            "single_pitting",
            "multi_pitting",
            "single_spalling",
        ]

    closed_pred_name = class_names[closed_class_id]
    closed_confidence = float(mean_prob.max())

    class_file_unk_thr, threshold_source = get_class_file_unknown_threshold(
        class_name=closed_pred_name,
        class_id=closed_class_id,
        classwise_cfg=classwise_cfg,
        class_file_unk_overrides=class_file_unk_overrides,
    )

    mean_maha = float(unk_info["maha"].mean().item())
    mean_maha_accept_thr = get_mean_maha_accept_threshold(
        class_name=closed_pred_name,
        mean_maha_accept_overrides=mean_maha_accept_overrides,
    )

    seg_pred = probs_all.argmax(dim=1)

    unknown_ratio = float(unknown_mask.float().mean().item())

    mean_unknown_votes = float(
        unk_info["unknown_votes"].float().mean().item()
    )

    all_segment_consistency = float(
        (seg_pred == closed_class_id).float().mean().item()
    )

    # ============================================================
    # 1. healthy 保护
    # ============================================================
    if (
        closed_pred_name == "healthy"
        and closed_confidence >= healthy_conf_protect
        and unknown_ratio < healthy_unknown_ratio_allow
    ):
        return {
            "closed_pred_id": closed_class_id,
            "closed_pred_name": closed_pred_name,
            "closed_confidence": closed_confidence,

            "final_pred_id": closed_class_id,
            "final_pred_name": "healthy",
            "component": "normal",

            "unknown_ratio": unknown_ratio,
            "segment_consistency": all_segment_consistency,
            "decision_reason": "healthy_protection",

            "class_file_unknown_ratio_threshold": class_file_unk_thr,
            "class_file_unknown_ratio_threshold_source": threshold_source,

            "mean_maha_accept_threshold": mean_maha_accept_thr,
            "mean_maha_accept_used": False,
        }

    # ============================================================
    # 2. 新增：文件级 mean_mahalanobis 接收规则
    # ============================================================
    if (
        closed_pred_name in known_fault_protect_classes
        and mean_maha_accept_thr is not None
        and closed_confidence >= maha_accept_min_conf
        and mean_maha <= mean_maha_accept_thr
        and mean_unknown_votes <= maha_accept_mean_votes_allow
    ):
        return {
            "closed_pred_id": closed_class_id,
            "closed_pred_name": closed_pred_name,
            "closed_confidence": closed_confidence,

            "final_pred_id": closed_class_id,
            "final_pred_name": closed_pred_name,
            "component": "first_fixed_gear",

            "unknown_ratio": unknown_ratio,
            "segment_consistency": all_segment_consistency,
            "decision_reason": "mean_mahalanobis_accept",

            "class_file_unknown_ratio_threshold": class_file_unk_thr,
            "class_file_unknown_ratio_threshold_source": threshold_source,

            "mean_maha_accept_threshold": mean_maha_accept_thr,
            "mean_maha_accept_used": True,
        }

    # ============================================================
    # 3. 已知故障高置信保护
    # ============================================================
    if known_fault_unknown_ratio_allow is None:
        protect_unk_thr = class_file_unk_thr
    else:
        protect_unk_thr = min(
            float(known_fault_unknown_ratio_allow),
            float(class_file_unk_thr),
        )

    if (
        closed_pred_name in known_fault_protect_classes
        and closed_confidence >= known_fault_conf_protect
        and unknown_ratio < protect_unk_thr
        and mean_unknown_votes < known_fault_mean_votes_allow
    ):
        return {
            "closed_pred_id": closed_class_id,
            "closed_pred_name": closed_pred_name,
            "closed_confidence": closed_confidence,

            "final_pred_id": closed_class_id,
            "final_pred_name": closed_pred_name,
            "component": "first_fixed_gear",

            "unknown_ratio": unknown_ratio,
            "segment_consistency": all_segment_consistency,
            "decision_reason": "known_fault_high_confidence_protection",

            "class_file_unknown_ratio_threshold": class_file_unk_thr,
            "class_file_unknown_ratio_threshold_source": threshold_source,

            "mean_maha_accept_threshold": mean_maha_accept_thr,
            "mean_maha_accept_used": False,
        }

    # ============================================================
    # 4. 类别级 unknown 拒识
    # ============================================================
    if unknown_ratio >= class_file_unk_thr:
        return {
            "closed_pred_id": closed_class_id,
            "closed_pred_name": closed_pred_name,
            "closed_confidence": closed_confidence,

            "final_pred_id": len(class_names),
            "final_pred_name": "unknown",
            "component": "unknown_or_other_component",

            "unknown_ratio": unknown_ratio,
            "segment_consistency": 0.0,
            "decision_reason": "classwise_high_unknown_ratio",

            "class_file_unknown_ratio_threshold": class_file_unk_thr,
            "class_file_unknown_ratio_threshold_source": threshold_source,

            "mean_maha_accept_threshold": mean_maha_accept_thr,
            "mean_maha_accept_used": False,
        }

    # ============================================================
    # 5. 未触发拒识，则接受闭集预测
    # ============================================================
    if closed_pred_name == "healthy":
        component = "normal"
    else:
        component = "first_fixed_gear"

    return {
        "closed_pred_id": closed_class_id,
        "closed_pred_name": closed_pred_name,
        "closed_confidence": closed_confidence,

        "final_pred_id": closed_class_id,
        "final_pred_name": closed_pred_name,
        "component": component,

        "unknown_ratio": unknown_ratio,
        "segment_consistency": all_segment_consistency,
        "decision_reason": "classwise_threshold_accept",

        "class_file_unknown_ratio_threshold": class_file_unk_thr,
        "class_file_unknown_ratio_threshold_source": threshold_source,

        "mean_maha_accept_threshold": mean_maha_accept_thr,
        "mean_maha_accept_used": False,
    }


def compute_health_score_v6(
    final_pred_name,
    closed_pred_name,
    closed_confidence,
    unknown_ratio,
    segment_consistency,
):
    """
    工业健康评分。
    """
    if final_pred_name == "healthy":
        score = 85.0 + 15.0 * closed_confidence
        score -= 5.0 * min(unknown_ratio, 1.0)
        score = np.clip(score, 80.0, 100.0)

    elif final_pred_name == "single_pitting":
        score = 68.0
        score -= 10.0 * closed_confidence
        score -= 8.0 * min(unknown_ratio, 1.0)
        score += 4.0 * (1.0 - segment_consistency)
        score = np.clip(score, 50.0, 75.0)

    elif final_pred_name == "multi_pitting":
        score = 48.0
        score -= 12.0 * closed_confidence
        score -= 8.0 * min(unknown_ratio, 1.0)
        score += 4.0 * (1.0 - segment_consistency)
        score = np.clip(score, 30.0, 60.0)

    elif final_pred_name == "single_spalling":
        score = 42.0
        score -= 12.0 * closed_confidence
        score -= 8.0 * min(unknown_ratio, 1.0)
        score += 4.0 * (1.0 - segment_consistency)
        score = np.clip(score, 25.0, 55.0)

    elif final_pred_name == "unknown":
        score = 70.0 * (1.0 - min(unknown_ratio, 1.0))

        if closed_pred_name != "healthy":
            score -= 10.0 * closed_confidence

        score = np.clip(score, 0.0, 60.0)

    else:
        score = 50.0

    return float(score)


def alarm_level_from_score_v6(score, final_pred_name):
    """
    根据健康评分生成报警等级。
    """
    if final_pred_name == "unknown":
        if score >= 50:
            return "warning"
        elif score >= 30:
            return "warning"
        else:
            return "alarm"

    if score >= 80:
        return "normal"
    elif score >= 60:
        return "attention"
    elif score >= 40:
        return "warning"
    else:
        return "alarm"


def translate_decision_reason(reason):
    """
    将代码内部的 decision_reason 转换成平台界面可读的中文说明。

    说明：
    - decision_reason 是算法内部规则名，适合保存和复现实验；
    - decision_reason_cn 是平台展示文本，适合给诊断人员阅读。
    """
    mapping = {
        "healthy_protection": "健康类保护：模型认为该文件与健康状态高度一致，优先接受健康判断。",
        "mean_mahalanobis_accept": "Mahalanobis接收：闭集类别置信度较高，且特征距离处于该类可接受范围内。",
        "known_fault_high_confidence_protection": "已知故障高置信保护：模型对某一已知故障类别具有很高置信度，优先接受该已知故障。",
        "classwise_high_unknown_ratio": "未知类拒识：大量切片触发低置信度、高熵或Mahalanobis距离过远条件，最终判为未知故障。",
        "classwise_threshold_accept": "类别阈值接收：未触发未知拒识规则，接受闭集最相似类别作为最终结果。",
    }
    return mapping.get(str(reason), str(reason))


def build_final_result_display(final_pred_name):
    """
    构造平台主显示字段。

    返回：
        final_result_display: 平台英文/代码式显示，如 Unknown 或 single_spalling
        final_result_cn: 平台中文显示，如 未知故障
        final_result_type: known / unknown
    """
    final_pred_name = str(final_pred_name)

    if final_pred_name == "unknown":
        return "Unknown", "未知故障", "unknown"

    cn_map = {
        "healthy": "健康",
        "single_pitting": "单点蚀",
        "multi_pitting": "多点蚀",
        "single_spalling": "单剥落",
    }
    return final_pred_name, cn_map.get(final_pred_name, final_pred_name), "known"


# ============================================================
# 平台展示数据导出工具
# ============================================================

def to_builtin_number(x):
    """
    将 numpy / torch 数值转为 Python 原生类型，便于 JSON 序列化。
    """
    try:
        if hasattr(x, "item"):
            return x.item()
    except Exception:
        pass

    if isinstance(x, (np.integer,)):
        return int(x)
    if isinstance(x, (np.floating,)):
        return float(x)

    return x


def safe_float(x, default=0.0):
    """
    安全转 float，避免 NaN / inf 进入前端。
    """
    try:
        v = float(to_builtin_number(x))
    except Exception:
        return float(default)

    if not np.isfinite(v):
        return float(default)

    return v


def downsample_curve(x, y, max_points=4000):
    """
    前端显示曲线不建议一次传输几十万点。
    这里采用等间隔抽样，保留整体趋势。
    """
    x = np.asarray(x, dtype=np.float64).reshape(-1)
    y = np.asarray(y, dtype=np.float64).reshape(-1)

    n = min(len(x), len(y))
    x = x[:n]
    y = y[:n]

    if n == 0:
        return [], []

    max_points = int(max_points)
    if max_points <= 0 or n <= max_points:
        return x.tolist(), y.tolist()

    idx = np.linspace(0, n - 1, max_points).astype(np.int64)
    return x[idx].tolist(), y[idx].tolist()


def compute_fft_full_curve(sig, fs, max_points=4000, f_min=0.0):
    """
    计算频谱曲线，用于诊断分析平台中的频谱图。

    返回：
        freq_hz: 频率轴
        amplitude: 单边幅值谱
        frequency_resolution_hz: 频率分辨率
    """
    x = np.asarray(sig, dtype=np.float64).reshape(-1)
    x = np.nan_to_num(x, nan=0.0, posinf=0.0, neginf=0.0)

    if len(x) < 2:
        return {
            "freq_hz": [],
            "amplitude": [],
            "frequency_resolution_hz": None,
        }

    x = x - np.mean(x)
    n = len(x)
    win = np.hanning(n)
    xw = x * win

    spec = np.abs(np.fft.rfft(xw))

    # 幅值修正：Hanning 窗 coherent gain 约为 mean(win)
    coherent_gain = np.mean(win) + 1e-12
    amp = spec / (n * coherent_gain)
    if len(amp) > 2:
        amp[1:-1] *= 2.0

    freqs = np.fft.rfftfreq(n, d=1.0 / float(fs))

    if f_min is not None:
        mask = freqs >= float(f_min)
        freqs = freqs[mask]
        amp = amp[mask]

    freq_ds, amp_ds = downsample_curve(freqs, amp, max_points=max_points)

    return {
        "freq_hz": freq_ds,
        "amplitude": amp_ds,
        "frequency_resolution_hz": float(fs) / float(n),
    }


def compute_top_fft_peaks_table(sig, fs, n_peaks=5, f_min=5.0):
    """
    计算 Top N 频率峰值，用于平台中的“主要频率成分”表格。
    """
    x = np.asarray(sig, dtype=np.float64).reshape(-1)
    x = np.nan_to_num(x, nan=0.0, posinf=0.0, neginf=0.0)

    if len(x) < 16:
        return []

    x = x - np.mean(x)
    n = len(x)
    win = np.hanning(n)
    xw = x * win

    spec = np.abs(np.fft.rfft(xw))
    coherent_gain = np.mean(win) + 1e-12
    amp = spec / (n * coherent_gain)
    if len(amp) > 2:
        amp[1:-1] *= 2.0

    freqs = np.fft.rfftfreq(n, d=1.0 / float(fs))

    mask = freqs >= float(f_min)
    freqs = freqs[mask]
    amp = amp[mask]

    if len(amp) == 0:
        return []

    n_peaks = max(1, int(n_peaks))
    idx = np.argsort(amp)[-n_peaks:][::-1]

    peaks = []
    for rank, i in enumerate(idx, start=1):
        peaks.append({
            "rank": int(rank),
            "freq_hz": float(freqs[i]),
            "amplitude": float(amp[i]),
        })

    return peaks


def compute_industrial_metrics(sig, fs):
    """
    计算工业诊断界面常用评价指标。

    注意：这些指标用于趋势监测、状态解释和报警辅助，
    阈值应结合设备历史基线、安装方式、传感器量程和现场工况确定。
    """
    x = np.asarray(sig, dtype=np.float64).reshape(-1)
    x = np.nan_to_num(x, nan=0.0, posinf=0.0, neginf=0.0)

    eps = 1e-12

    if len(x) == 0:
        return {
            "mean": 0.0,
            "std": 0.0,
            "rms": 0.0,
            "peak": 0.0,
            "peak_to_peak": 0.0,
            "skewness": 0.0,
            "kurtosis": 0.0,
            "crest_factor": 0.0,
            "shape_factor": 0.0,
            "impulse_factor": 0.0,
            "clearance_factor": 0.0,
            "frequency_center": 0.0,
            "frequency_rms": 0.0,
            "frequency_std": 0.0,
        }

    mean = float(np.mean(x))
    std = float(np.std(x) + eps)

    abs_x = np.abs(x)
    mean_abs = float(np.mean(abs_x) + eps)
    rms = float(np.sqrt(np.mean(x ** 2)) + eps)
    peak = float(np.max(abs_x))
    peak_to_peak = float(np.max(x) - np.min(x))

    centered = x - mean
    skewness = float(np.mean(centered ** 3) / (std ** 3 + eps))
    kurtosis = float(np.mean(centered ** 4) / (std ** 4 + eps))

    crest_factor = float(peak / (rms + eps))
    shape_factor = float(rms / (mean_abs + eps))
    impulse_factor = float(peak / (mean_abs + eps))
    clearance_factor = float(
        peak / ((np.mean(np.sqrt(abs_x + eps)) ** 2) + eps)
    )

    # 频域评价指标
    x2 = x - np.mean(x)
    if len(x2) >= 2:
        win = np.hanning(len(x2))
        spec = np.abs(np.fft.rfft(x2 * win))
        freqs = np.fft.rfftfreq(len(x2), d=1.0 / float(fs))

        power = spec ** 2
        power_sum = float(np.sum(power) + eps)

        frequency_center = float(np.sum(freqs * power) / power_sum)
        mean_square_frequency = float(np.sum((freqs ** 2) * power) / power_sum)
        frequency_rms = float(np.sqrt(mean_square_frequency))
        frequency_std = float(
            np.sqrt(np.sum(((freqs - frequency_center) ** 2) * power) / power_sum)
        )
    else:
        frequency_center = 0.0
        frequency_rms = 0.0
        frequency_std = 0.0

    return {
        "mean": safe_float(mean),
        "std": safe_float(std),
        "rms": safe_float(rms),
        "peak": safe_float(peak),
        "peak_to_peak": safe_float(peak_to_peak),
        "skewness": safe_float(skewness),
        "kurtosis": safe_float(kurtosis),
        "crest_factor": safe_float(crest_factor),
        "shape_factor": safe_float(shape_factor),
        "impulse_factor": safe_float(impulse_factor),
        "clearance_factor": safe_float(clearance_factor),
        "frequency_center": safe_float(frequency_center),
        "frequency_rms": safe_float(frequency_rms),
        "frequency_std": safe_float(frequency_std),
    }


def build_industrial_metric_table(metrics):
    """
    生成前端表格形式的工业评价指标。
    这里不强行给出固定报警阈值，避免不同设备/测点/工况下误判。
    """
    specs = [
        ("rms", "RMS（均方根）", "g", "反映整体振动能量"),
        ("peak", "峰值", "g", "反映最大瞬时冲击"),
        ("peak_to_peak", "峰峰值", "g", "反映振动幅值范围"),
        ("crest_factor", "峰值因子", "-", "对冲击类故障敏感"),
        ("kurtosis", "峭度", "-", "对脉冲和非高斯特征敏感"),
        ("skewness", "偏度", "-", "反映波形不对称程度"),
        ("shape_factor", "波形因子", "-", "反映波形形态变化"),
        ("impulse_factor", "脉冲因子", "-", "对早期冲击增强敏感"),
        ("clearance_factor", "裕度因子", "-", "常用于滚动轴承/冲击类评价"),
        ("frequency_center", "频率中心", "Hz", "反映频谱能量重心"),
        ("frequency_rms", "频率均方根", "Hz", "反映频谱能量分散程度"),
        ("frequency_std", "频率标准差", "Hz", "反映频谱带宽变化"),
    ]

    rows = []
    for key, name, unit, meaning in specs:
        value = safe_float(metrics.get(key, 0.0))
        rows.append({
            "key": key,
            "name": name,
            "value": value,
            "unit": unit,
            "evaluation": "reference",
            "meaning": meaning,
        })

    return rows


def make_dashboard_json_name(path):
    """
    使用文件名 + 路径哈希，避免不同文件夹下同名 npy 覆盖。
    """
    p = Path(path)
    h = hashlib.md5(str(p.resolve()).encode("utf-8", errors="ignore")).hexdigest()[:8]
    return f"{p.stem}_{h}_dashboard.json"


def build_dashboard_payload(
    path,
    sig,
    fs,
    diagnosis_result,
    class_names,
    max_points=4000,
    spectrum_f_min=0.0,
    top_n_peaks=5,
):
    """
    为诊断分析平台构造单文件展示数据。

    平台可直接使用：
    1. file_info 显示当前文件信息；
    2. diagnosis 显示诊断结论、置信度、健康评分和开集指标；
    3. class_probabilities / probability_table 显示分类概率；
    4. waveform 画时域波形图；
    5. spectrum 画频谱图并显示 Top 频率峰值；
    6. industrial_metrics / industrial_metric_table 显示工业评价指标。
    """
    p = Path(path)
    x = np.asarray(sig, dtype=np.float32).reshape(-1)
    x = np.nan_to_num(x, nan=0.0, posinf=0.0, neginf=0.0)

    fs = float(fs)
    signal_length = int(len(x))
    duration_sec = float(signal_length / fs) if fs > 0 else 0.0

    time_axis = np.arange(signal_length, dtype=np.float64) / fs if fs > 0 else np.arange(signal_length)
    time_ds, amp_ds = downsample_curve(
        time_axis,
        x,
        max_points=max_points,
    )

    spectrum = compute_fft_full_curve(
        sig=x,
        fs=fs,
        max_points=max_points,
        f_min=spectrum_f_min,
    )

    top_peaks = compute_top_fft_peaks_table(
        sig=x,
        fs=fs,
        n_peaks=top_n_peaks,
        f_min=5.0,
    )

    industrial_metrics = compute_industrial_metrics(
        sig=x,
        fs=fs,
    )

    class_probs = {}
    probability_table = []
    for cname in class_names:
        key = f"prob_{cname}"
        prob = safe_float(diagnosis_result.get(key, 0.0))
        class_probs[cname] = prob
        probability_table.append({
            "class_name": cname,
            "probability": prob,
            "label_type": "known",
        })

    # 注意：unknown_ratio 是开集拒识比例，不是 softmax 概率。
    probability_table.append({
        "class_name": "unknown",
        "probability": safe_float(diagnosis_result.get("unknown_ratio", 0.0)),
        "label_type": "open_set_unknown_ratio",
    })

    file_size_mb = None
    if p.exists():
        file_size_mb = float(p.stat().st_size / 1024.0 / 1024.0)

    payload = {
        "file_info": {
            "file": str(p),
            "file_name": p.name,
            "file_size_mb": file_size_mb,
            "fs": fs,
            "signal_length": signal_length,
            "duration_sec": duration_sec,
            "num_segments": int(diagnosis_result.get("num_segments", 0)),
        },
        "diagnosis": {
            "final_prediction": diagnosis_result.get("final_prediction"),
            "final_result_display": diagnosis_result.get("final_result_display"),
            "final_result_cn": diagnosis_result.get("final_result_cn"),
            "final_result_type": diagnosis_result.get("final_result_type"),
            "component": diagnosis_result.get("component"),
            "closed_prediction": diagnosis_result.get("closed_prediction"),
            "closed_most_similar_class": diagnosis_result.get(
                "closed_most_similar_class",
                diagnosis_result.get("closed_prediction"),
            ),
            "closed_confidence": safe_float(diagnosis_result.get("closed_confidence", 0.0)),
            "unknown_ratio": safe_float(diagnosis_result.get("unknown_ratio", 0.0)),
            "unknown_ratio_percent": safe_float(diagnosis_result.get("unknown_ratio_percent", 0.0)),
            "segment_consistency": safe_float(diagnosis_result.get("segment_consistency", 0.0)),
            "decision_reason": diagnosis_result.get("decision_reason"),
            "decision_reason_cn": diagnosis_result.get(
                "decision_reason_cn",
                translate_decision_reason(diagnosis_result.get("decision_reason")),
            ),
            "health_score": safe_float(diagnosis_result.get("health_score", 0.0)),
            "alarm_level": diagnosis_result.get("alarm_level"),
            "mean_mahalanobis": safe_float(diagnosis_result.get("mean_mahalanobis", 0.0)),
            "min_mahalanobis": safe_float(diagnosis_result.get("min_mahalanobis", 0.0)),
            "max_mahalanobis": safe_float(diagnosis_result.get("max_mahalanobis", 0.0)),
            "mean_entropy": safe_float(diagnosis_result.get("mean_entropy", 0.0)),
            "mean_max_prob": safe_float(diagnosis_result.get("mean_max_prob", 0.0)),
            "ratio_low_conf": safe_float(diagnosis_result.get("ratio_low_conf", 0.0)),
            "ratio_high_entropy": safe_float(diagnosis_result.get("ratio_high_entropy", 0.0)),
            "ratio_far_maha": safe_float(diagnosis_result.get("ratio_far_maha", 0.0)),
            "mean_unknown_votes": safe_float(diagnosis_result.get("mean_unknown_votes", 0.0)),
        },
        "open_set_analysis": {
            "final_result": diagnosis_result.get(
                "final_result_display",
                diagnosis_result.get("final_prediction"),
            ),
            "final_result_cn": diagnosis_result.get(
                "final_result_cn",
                diagnosis_result.get("final_prediction"),
            ),
            "final_result_type": diagnosis_result.get("final_result_type", "known"),

            "closed_most_similar_class": diagnosis_result.get(
                "closed_most_similar_class",
                diagnosis_result.get("closed_prediction"),
            ),
            "closed_confidence": safe_float(diagnosis_result.get("closed_confidence", 0.0)),

            "unknown_ratio": safe_float(diagnosis_result.get("unknown_ratio", 0.0)),
            "unknown_ratio_percent": safe_float(diagnosis_result.get("unknown_ratio_percent", 0.0)),
            "class_file_unknown_ratio_threshold": safe_float(
                diagnosis_result.get("class_file_unknown_ratio_threshold", 0.0)
            ),
            "class_file_unknown_ratio_threshold_source": diagnosis_result.get(
                "class_file_unknown_ratio_threshold_source"
            ),

            "mean_mahalanobis": safe_float(diagnosis_result.get("mean_mahalanobis", 0.0)),
            "min_mahalanobis": safe_float(diagnosis_result.get("min_mahalanobis", 0.0)),
            "max_mahalanobis": safe_float(diagnosis_result.get("max_mahalanobis", 0.0)),
            "class_maha_threshold": safe_float(diagnosis_result.get("class_maha_threshold", 0.0)),
            "mean_maha_accept_threshold": safe_float(
                diagnosis_result.get("mean_maha_accept_threshold", 0.0)
            ),
            "mean_maha_accept_used": bool(diagnosis_result.get("mean_maha_accept_used", False)),

            "mean_entropy": safe_float(diagnosis_result.get("mean_entropy", 0.0)),
            "mean_max_prob": safe_float(diagnosis_result.get("mean_max_prob", 0.0)),
            "segment_consistency": safe_float(diagnosis_result.get("segment_consistency", 0.0)),
            "mean_unknown_votes": safe_float(diagnosis_result.get("mean_unknown_votes", 0.0)),
            "unknown_vote_required": int(diagnosis_result.get("unknown_vote_required", 0)),

            "decision_reason": diagnosis_result.get("decision_reason"),
            "decision_reason_cn": diagnosis_result.get(
                "decision_reason_cn",
                translate_decision_reason(diagnosis_result.get("decision_reason")),
            ),
        },
        "class_probabilities": class_probs,
        "probability_table": probability_table,
        "industrial_metrics": industrial_metrics,
        "industrial_metric_table": build_industrial_metric_table(industrial_metrics),
        "waveform": {
            "x_name": "Time",
            "x_unit": "s",
            "y_name": "Amplitude",
            "y_unit": "g",
            "time_sec": time_ds,
            "amplitude": amp_ds,
            "display_points": len(time_ds),
            "raw_points": signal_length,
        },
        "spectrum": {
            "x_name": "Frequency",
            "x_unit": "Hz",
            "y_name": "Amplitude",
            "y_unit": "g",
            "freq_hz": spectrum["freq_hz"],
            "amplitude": spectrum["amplitude"],
            "display_points": len(spectrum["freq_hz"]),
            "frequency_resolution_hz": spectrum["frequency_resolution_hz"],
            "top_peaks": top_peaks,
        },
        "ui_hint": {
            "suggested_title": "齿轮箱故障诊断分析平台",
            "suggested_page": "诊断分析 / 单文件诊断",
            "curve_downsampled": signal_length > int(max_points),
            "unknown_ratio_note": "unknown_ratio 为切片级开集拒识比例，不等同于 softmax 概率。",
            "open_set_analysis_note": "open_set_analysis 字段专门用于平台显示最终结果、闭集最相似类别、未知比例、Mahalanobis距离和决策原因。",
            "metric_threshold_note": "工业评价指标建议结合设备历史基线和现场工况设置报警阈值。",
        },
    }

    return payload


def save_dashboard_payload(out_json, payload):
    """
    保存平台展示 JSON。
    """
    out_json = Path(out_json)
    out_json.parent.mkdir(parents=True, exist_ok=True)

    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

    return out_json


@torch.no_grad()
def diagnose_signal_array(
    model,
    sig,
    device,
    win_len,
    stride,
    batch_size,
    class_names,
    classwise_cfg,
    class_file_unk_overrides,
    mean_maha_accept_overrides,
    fs=5120.0,
    unknown_vote_required=None,
    healthy_conf_protect=0.80,
    healthy_unknown_ratio_allow=1.01,
    known_fault_conf_protect=0.99,
    known_fault_unknown_ratio_allow=None,
    known_fault_mean_votes_allow=2.0,
    known_fault_protect_classes=None,
    maha_accept_min_conf=0.90,
    maha_accept_mean_votes_allow=3.10,
    file_label="",
):
    """
    诊断内存中的信号数组（不需要文件路径）。

    与 diagnose_one_file 逻辑完全一致，但直接接收 numpy 数组，
    避免不必要的文件 I/O 和序列化往返。
    """
    model.eval()

    sig = np.asarray(sig, dtype=np.float32).reshape(-1)
    n_win = count_windows(len(sig), win_len, stride)

    probs_all = []
    feats_all = []

    for i in range(0, n_win, batch_size):
        xs = []

        for j in range(i, min(i + batch_size, n_win)):
            start = j * stride
            x = get_window(sig, start, win_len)
            x = zscore_1d(x)
            xs.append(x)

        xs = torch.from_numpy(np.stack(xs)).float().unsqueeze(1).to(device)

        out = model(xs, grl_lambda=0.0)

        probs = torch.softmax(out["logits"], dim=1)
        feat = out["feat"]

        probs_all.append(probs.detach().cpu())
        feats_all.append(feat.detach().cpu())

    probs_all = torch.cat(probs_all, dim=0)
    feats_all = torch.cat(feats_all, dim=0)

    mean_prob = probs_all.mean(dim=0)
    closed_class_id = int(mean_prob.argmax())

    unknown_mask, unk_info = segment_unknown_mask_classwise_maha(
        probs_all=probs_all,
        feats_all=feats_all,
        closed_class_id=closed_class_id,
        classwise_cfg=classwise_cfg,
        device=device,
        unknown_vote_required=unknown_vote_required,
    )

    decision = decide_file_prediction_classwise_maha_v6(
        mean_prob=mean_prob,
        probs_all=probs_all,
        unknown_mask=unknown_mask,
        unk_info=unk_info,
        class_names=class_names,
        classwise_cfg=classwise_cfg,
        closed_class_id=closed_class_id,
        class_file_unk_overrides=class_file_unk_overrides,
        mean_maha_accept_overrides=mean_maha_accept_overrides,
        healthy_conf_protect=healthy_conf_protect,
        healthy_unknown_ratio_allow=healthy_unknown_ratio_allow,
        known_fault_conf_protect=known_fault_conf_protect,
        known_fault_unknown_ratio_allow=known_fault_unknown_ratio_allow,
        known_fault_mean_votes_allow=known_fault_mean_votes_allow,
        known_fault_protect_classes=known_fault_protect_classes,
        maha_accept_min_conf=maha_accept_min_conf,
        maha_accept_mean_votes_allow=maha_accept_mean_votes_allow,
    )

    health_score = compute_health_score_v6(
        final_pred_name=decision["final_pred_name"],
        closed_pred_name=decision["closed_pred_name"],
        closed_confidence=decision["closed_confidence"],
        unknown_ratio=decision["unknown_ratio"],
        segment_consistency=decision["segment_consistency"],
    )

    alarm_level = alarm_level_from_score_v6(
        score=health_score,
        final_pred_name=decision["final_pred_name"],
    )

    evidence_freqs = top_fft_peaks(sig, fs=fs, n_peaks=6)

    final_result_display, final_result_cn, final_result_type = build_final_result_display(
        decision["final_pred_name"]
    )
    decision_reason_cn = translate_decision_reason(decision["decision_reason"])

    result = {
        "file": str(file_label),

        # 平台主显示字段
        "final_prediction": decision["final_pred_name"],
        "final_result_display": final_result_display,
        "final_result_cn": final_result_cn,
        "final_result_type": final_result_type,
        "component": decision["component"],

        # 闭集最相似类别
        "closed_prediction": decision["closed_pred_name"],
        "closed_most_similar_class": decision["closed_pred_name"],
        "closed_confidence": decision["closed_confidence"],

        # 开集拒识核心指标
        "unknown_ratio": decision["unknown_ratio"],
        "unknown_ratio_percent": float(decision["unknown_ratio"]) * 100.0,
        "class_file_unknown_ratio_threshold": decision["class_file_unknown_ratio_threshold"],
        "class_file_unknown_ratio_threshold_source": decision["class_file_unknown_ratio_threshold_source"],

        "segment_consistency": decision["segment_consistency"],
        "decision_reason": decision["decision_reason"],
        "decision_reason_cn": decision_reason_cn,

        "mean_maha_accept_threshold": decision["mean_maha_accept_threshold"],
        "mean_maha_accept_used": decision["mean_maha_accept_used"],

        "health_score": health_score,
        "alarm_level": alarm_level,

        "mean_mahalanobis": float(unk_info["maha"].mean().item()),
        "min_mahalanobis": float(unk_info["maha"].min().item()),
        "max_mahalanobis": float(unk_info["maha"].max().item()),

        "mean_entropy": float(unk_info["entropy"].mean().item()),
        "mean_max_prob": float(unk_info["max_prob"].mean().item()),

        "class_conf_threshold": unk_info["class_conf_threshold"],
        "class_entropy_threshold": unk_info["class_entropy_threshold"],
        "class_maha_threshold": unk_info["class_maha_threshold"],

        "ratio_low_conf": float(unk_info["cond_low_conf"].float().mean().item()),
        "ratio_high_entropy": float(unk_info["cond_high_entropy"].float().mean().item()),
        "ratio_far_maha": float(unk_info["cond_far_maha"].float().mean().item()),

        "mean_unknown_votes": float(unk_info["unknown_votes"].float().mean().item()),
        "unknown_vote_required": int(unk_info["unknown_vote_required"]),

        "evidence_frequencies": evidence_freqs,
        "num_segments": int(n_win),
    }

    for i, cname in enumerate(class_names):
        result[f"prob_{cname}"] = float(mean_prob[i].item())

    return result


@torch.no_grad()
def diagnose_one_file(
    model,
    path,
    device,
    win_len,
    stride,
    signal_key,
    batch_size,
    class_names,
    classwise_cfg,
    class_file_unk_overrides,
    mean_maha_accept_overrides,
    fs=5120.0,
    unknown_vote_required=None,
    healthy_conf_protect=0.80,
    healthy_unknown_ratio_allow=1.01,
    known_fault_conf_protect=0.99,
    known_fault_unknown_ratio_allow=None,
    known_fault_mean_votes_allow=2.0,
    known_fault_protect_classes=None,
    maha_accept_min_conf=0.90,
    maha_accept_mean_votes_allow=3.10,
):
    """
    诊断单个 npy 文件。加载信号后委托给 diagnose_signal_array。
    """
    sig = load_npy_signal(path, signal_key=signal_key)
    return diagnose_signal_array(
        model=model,
        sig=sig,
        device=device,
        win_len=win_len,
        stride=stride,
        batch_size=batch_size,
        class_names=class_names,
        classwise_cfg=classwise_cfg,
        class_file_unk_overrides=class_file_unk_overrides,
        mean_maha_accept_overrides=mean_maha_accept_overrides,
        fs=fs,
        unknown_vote_required=unknown_vote_required,
        healthy_conf_protect=healthy_conf_protect,
        healthy_unknown_ratio_allow=healthy_unknown_ratio_allow,
        known_fault_conf_protect=known_fault_conf_protect,
        known_fault_unknown_ratio_allow=known_fault_unknown_ratio_allow,
        known_fault_mean_votes_allow=known_fault_mean_votes_allow,
        known_fault_protect_classes=known_fault_protect_classes,
        maha_accept_min_conf=maha_accept_min_conf,
        maha_accept_mean_votes_allow=maha_accept_mean_votes_allow,
        file_label=str(path),
    )


def main():
    parser = argparse.ArgumentParser()

    parser.add_argument(
        "--target_root",
        type=str,
        # default="D:\Dr_treasurechest\\05_code\\01-data\CP\ORIGIN\cpower_data\\f5.12khz\\rocker\\healthy",
        # default="D:\Dr_treasurechest\\05_code\\01-data\CP\ORIGIN\cpower_data\\f5.12khz\\rocker\\single_pitting",
        # default="D:\Dr_treasurechest\\05_code\\01-data\CP\ORIGIN\cpower_data\\f5.12khz\\rocker\\multi_pitting",
        # default="D:\Dr_treasurechest\\05_code\\01-data\CP\ORIGIN\cpower_data\\f5.12khz\\rocker\\single_spalling",
        default=r"D:\Dr_treasurechest\05_code\01-data\CP\ORIGIN\cpower_data\f5.12khz\rocker\multi_spalling",
        help="其他位置无标签数据根目录"
    )

    parser.add_argument(
        "--ckpt",
        type=str,
        default=r"D:\Dr_treasurechest\05_code\02-cpower_code\version3\runs\exp_wdcnn_mech_dg_v2\best_model_classwise_maha.pth",
        help="包含 classwise_open_set 的 checkpoint，例如 best_model_classwise_maha.pth"
    )

    parser.add_argument(
        "--out_csv",
        type=str,
        default=r"D:\Dr_treasurechest\05_code\02-cpower_code\version3\runs\unlabeled_target_diagnosis_v4\mspl.csv"
    )

    parser.add_argument(
        "--signal_key",
        type=str,
        default="sig_acc_5120"
    )

    parser.add_argument(
        "--batch_size",
        type=int,
        default=128
    )

    parser.add_argument(
        "--device",
        type=str,
        default="cuda"
    )

    parser.add_argument(
        "--unknown_vote_required",
        type=int,
        default=None,
        help="如果不设置，则使用校准 checkpoint 中的 unknown_vote_required"
    )

    parser.add_argument(
        "--class_file_unk_overrides",
        type=str,
        default="healthy:0.70,single_pitting:0.85,multi_pitting:0.70,single_spalling:0.85",
        help=(
            "类别级文件 unknown_ratio 阈值覆盖。"
            "示例: healthy:0.70,single_pitting:0.85,multi_pitting:0.70,single_spalling:0.85"
        )
    )

    parser.add_argument(
        "--mean_maha_accept_overrides",
        type=str,
        default="single_pitting:30,multi_pitting:25,single_spalling:15",
        help=(
            "文件级 mean_mahalanobis 接收阈值。"
            "示例: single_pitting:30,multi_pitting:25,single_spalling:15"
        )
    )

    parser.add_argument(
        "--maha_accept_min_conf",
        type=float,
        default=0.90,
        help="触发 mean_mahalanobis 接收规则所需的最低闭集置信度"
    )

    parser.add_argument(
        "--maha_accept_mean_votes_allow",
        type=float,
        default=3.10,
        help="触发 mean_mahalanobis 接收规则允许的最大 mean_unknown_votes"
    )

    parser.add_argument(
        "--healthy_conf_protect",
        type=float,
        default=0.80
    )

    parser.add_argument(
        "--healthy_unknown_ratio_allow",
        type=float,
        default=1.01
    )

    parser.add_argument(
        "--known_fault_conf_protect",
        type=float,
        default=0.99
    )

    parser.add_argument(
        "--known_fault_unknown_ratio_allow",
        type=float,
        default=None,
        help="默认使用类别级文件阈值；如果设置，则与类别级阈值取更严格者"
    )

    parser.add_argument(
        "--known_fault_mean_votes_allow",
        type=float,
        default=2.0
    )

    parser.add_argument(
        "--known_fault_protect_classes",
        type=str,
        default="single_pitting,multi_pitting,single_spalling"
    )


    parser.add_argument(
        "--dashboard_json_dir",
        type=str,
        default=None,
        help="平台展示 JSON 输出目录。默认保存在 out_csv 同级目录下的 dashboard_json 文件夹"
    )

    parser.add_argument(
        "--dashboard_max_points",
        type=int,
        default=4000,
        help="时域和频谱曲线传给前端的最大点数，避免 JSON 过大"
    )

    parser.add_argument(
        "--dashboard_top_peaks",
        type=int,
        default=5,
        help="频谱 Top N 峰值数量"
    )

    parser.add_argument(
        "--dashboard_spectrum_f_min",
        type=float,
        default=0.0,
        help="平台频谱曲线显示的最低频率，默认 0 Hz"
    )

    parser.add_argument(
        "--no_dashboard_json",
        action="store_true",
        help="如果只想输出原始诊断 CSV，可加该参数关闭平台 JSON 导出"
    )

    args = parser.parse_args()

    device = torch.device(args.device if torch.cuda.is_available() else "cpu")

    ckpt = safe_torch_load(args.ckpt, map_location=device)

    if "classwise_open_set" not in ckpt:
        raise RuntimeError(
            "当前 checkpoint 中找不到 classwise_open_set。\n"
            "请先运行 05_calibrate_classwise_mahalanobis.py 生成 "
            "best_model_classwise_maha.pth。"
        )

    params = ckpt["params"]
    class_names = ckpt["classes"]
    num_classes = len(class_names)
    classwise_cfg = ckpt["classwise_open_set"]

    class_file_unk_overrides = parse_class_threshold_overrides(
        args.class_file_unk_overrides
    )

    mean_maha_accept_overrides = parse_maha_accept_overrides(
        args.mean_maha_accept_overrides
    )

    known_fault_protect_classes = parse_protect_classes(
        args.known_fault_protect_classes
    )

    model = WDCNNMechDG(
        num_classes=num_classes,
        num_domains=2,
        fs=params["fs"],
        win_len=params["win_len"],
        feat_dim=params["feat_dim"],
    ).to(device)

    model.load_state_dict(ckpt["model_state"])
    model.eval()

    target_root = Path(args.target_root)
    files = sorted(target_root.rglob("*.npy"))

    if len(files) == 0:
        raise FileNotFoundError(f"在 {target_root} 下没有找到 npy 文件")

    print("=" * 100)
    print("Classwise Mahalanobis diagnosis v6 + mean Mahalanobis accept")
    print(f"target_root: {target_root}")
    print(f"ckpt       : {args.ckpt}")
    print(f"classes    : {class_names}")
    print("=" * 100)

    print("Checkpoint calibrated file_unknown_ratio_thresholds:")
    for cname, thr in zip(class_names, classwise_cfg["file_unknown_ratio_thresholds"]):
        print(f"  {cname:16s}: {float(thr):.6f}")

    print("-" * 100)
    print("Manual class_file_unk_overrides:")
    if len(class_file_unk_overrides) == 0:
        print("  None")
    else:
        for cname in class_names:
            if cname in class_file_unk_overrides:
                print(f"  {cname:16s}: {class_file_unk_overrides[cname]:.6f}")
            else:
                print(f"  {cname:16s}: not overridden")

    print("-" * 100)
    print("Manual mean_maha_accept_overrides:")
    if len(mean_maha_accept_overrides) == 0:
        print("  None")
    else:
        for cname in class_names:
            if cname in mean_maha_accept_overrides:
                print(f"  {cname:16s}: {mean_maha_accept_overrides[cname]:.6f}")
            else:
                print(f"  {cname:16s}: not overridden")

    print("-" * 100)
    print(f"known_fault_protect_classes: {known_fault_protect_classes}")
    print(f"maha_accept_min_conf: {args.maha_accept_min_conf}")
    print(f"maha_accept_mean_votes_allow: {args.maha_accept_mean_votes_allow}")
    print(f"healthy_conf_protect: {args.healthy_conf_protect}")
    print(f"healthy_unknown_ratio_allow: {args.healthy_unknown_ratio_allow}")
    print(f"known_fault_conf_protect: {args.known_fault_conf_protect}")
    print(f"known_fault_unknown_ratio_allow: {args.known_fault_unknown_ratio_allow}")
    print(f"known_fault_mean_votes_allow: {args.known_fault_mean_votes_allow}")
    print("=" * 100)

    rows = []

    for path in tqdm(files, desc="Diagnose target by classwise Mahalanobis v6", ncols=100):
        r = diagnose_one_file(
            model=model,
            path=path,
            device=device,
            win_len=int(params["win_len"]),
            stride=int(params["stride"]),
            signal_key=args.signal_key,
            batch_size=args.batch_size,
            class_names=class_names,
            classwise_cfg=classwise_cfg,
            class_file_unk_overrides=class_file_unk_overrides,
            mean_maha_accept_overrides=mean_maha_accept_overrides,
            fs=float(params["fs"]),
            unknown_vote_required=args.unknown_vote_required,
            healthy_conf_protect=args.healthy_conf_protect,
            healthy_unknown_ratio_allow=args.healthy_unknown_ratio_allow,
            known_fault_conf_protect=args.known_fault_conf_protect,
            known_fault_unknown_ratio_allow=args.known_fault_unknown_ratio_allow,
            known_fault_mean_votes_allow=args.known_fault_mean_votes_allow,
            known_fault_protect_classes=known_fault_protect_classes,
            maha_accept_min_conf=args.maha_accept_min_conf,
            maha_accept_mean_votes_allow=args.maha_accept_mean_votes_allow,
        )

        if not args.no_dashboard_json:
            sig_for_dashboard = load_npy_signal(path, signal_key=args.signal_key)

            payload = build_dashboard_payload(
                path=path,
                sig=sig_for_dashboard,
                fs=float(params["fs"]),
                diagnosis_result=r,
                class_names=class_names,
                max_points=args.dashboard_max_points,
                spectrum_f_min=args.dashboard_spectrum_f_min,
                top_n_peaks=args.dashboard_top_peaks,
            )

            if args.dashboard_json_dir is None:
                dashboard_json_dir = Path(args.out_csv).parent / "dashboard_json"
            else:
                dashboard_json_dir = Path(args.dashboard_json_dir)

            dashboard_json_path = dashboard_json_dir / make_dashboard_json_name(path)
            save_dashboard_payload(dashboard_json_path, payload)

            r["dashboard_json"] = str(dashboard_json_path)
            r["file_size_mb"] = payload["file_info"]["file_size_mb"]
            r["fs"] = payload["file_info"]["fs"]
            r["signal_length"] = payload["file_info"]["signal_length"]
            r["duration_sec"] = payload["file_info"]["duration_sec"]
            r["spectrum_frequency_resolution_hz"] = payload["spectrum"]["frequency_resolution_hz"]
            r["top_peaks_json"] = json.dumps(
                payload["spectrum"]["top_peaks"],
                ensure_ascii=False,
            )

            for metric_key, metric_value in payload["industrial_metrics"].items():
                r[f"industrial_{metric_key}"] = metric_value
        else:
            r["dashboard_json"] = ""

        rows.append(r)

    out_csv = Path(args.out_csv)
    out_csv.parent.mkdir(parents=True, exist_ok=True)

    df = pd.DataFrame(rows)
    df.to_csv(out_csv, index=False, encoding="utf-8-sig")

    print("=" * 100)
    print(f"诊断完成，共处理文件数: {len(df)}")
    print(f"结果保存到: {out_csv}")
    print("=" * 100)

    print("final_prediction 统计:")
    print(df["final_prediction"].value_counts())

    if "final_result_type" in df.columns:
        print("=" * 100)
        print("final_result_type 统计:")
        print(df["final_result_type"].value_counts())

    print("=" * 100)
    print("closed_prediction 统计:")
    print(df["closed_prediction"].value_counts())

    print("=" * 100)
    print("decision_reason 统计:")
    print(df["decision_reason"].value_counts())

    print("=" * 100)
    print("alarm_level 统计:")
    print(df["alarm_level"].value_counts())

    print("=" * 100)
    print("class_file_unknown_ratio_threshold_source 统计:")
    print(df["class_file_unknown_ratio_threshold_source"].value_counts())

    print("=" * 100)
    print("mean_maha_accept_used 统计:")
    print(df["mean_maha_accept_used"].value_counts())

    print("=" * 100)
    print("关键指标均值:")
    useful_cols = [
        "closed_confidence",
        "unknown_ratio",
        "class_file_unknown_ratio_threshold",
        "segment_consistency",
        "health_score",
        "mean_mahalanobis",
        "mean_maha_accept_threshold",
        "mean_entropy",
        "mean_max_prob",
        "ratio_low_conf",
        "ratio_high_entropy",
        "ratio_far_maha",
        "mean_unknown_votes",
    ]

    for c in useful_cols:
        if c in df.columns:
            values = pd.to_numeric(df[c], errors="coerce")
            print(
                f"{c}: "
                f"mean={values.mean():.6f}, "
                f"min={values.min():.6f}, "
                f"max={values.max():.6f}"
            )

    print("=" * 100)


if __name__ == "__main__":
    main()
