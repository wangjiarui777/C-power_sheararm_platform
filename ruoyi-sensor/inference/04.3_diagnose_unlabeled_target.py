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
single_pitting:30,multi_pitting:25,single_spalling:20

使用前提：
必须先运行 05_calibrate_classwise_mahalanobis.py，
生成 best_model_classwise_maha.pth。
"""

import argparse
from pathlib import Path

import numpy as np
import pandas as pd
from tqdm import tqdm

import torch

from utils_signal import (
    load_signal,
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
        return torch.load(path, map_location=map_location, weights_only=False)
    except TypeError:
        return torch.load(path, map_location=map_location)


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

    result = {
        "file": str(file_label),

        "final_prediction": decision["final_pred_name"],
        "component": decision["component"],

        "closed_prediction": decision["closed_pred_name"],
        "closed_confidence": decision["closed_confidence"],

        "unknown_ratio": decision["unknown_ratio"],
        "class_file_unknown_ratio_threshold": decision["class_file_unknown_ratio_threshold"],
        "class_file_unknown_ratio_threshold_source": decision["class_file_unknown_ratio_threshold_source"],

        "segment_consistency": decision["segment_consistency"],
        "decision_reason": decision["decision_reason"],

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
    诊断单个 npy/mat 文件。加载信号后委托给 diagnose_signal_array。
    """
    sig = load_signal(path, signal_key=signal_key)
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
        default="get/got",
        help="无标签数据根目录"
    )

    parser.add_argument(
        "--ckpt",
        type=str,
        default="get/best_model_classwise_maha.pth",
        help="包含 classwise_open_set 的 checkpoint，例如 best_model_classwise_maha.pth"
    )

    parser.add_argument(
        "--out_csv",
        type=str,
        default="diagnosis_result.csv"
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
        default="healthy:0.70,single_pitting:0.85,multi_pitting:0.85,single_spalling:0.85",
        help=(
            "类别级文件 unknown_ratio 阈值覆盖。"
            "multi_pitting 的 per-segment maha=5.17 (strict), "
            "因此 file-level 阈值提高至 0.85 补偿"
        )
    )

    parser.add_argument(
        "--mean_maha_accept_overrides",
        type=str,
        default="single_pitting:30,multi_pitting:25,single_spalling:20",
        help=(
            "文件级 mean_mahalanobis 接收阈值。"
            "single_spalling per-segment maha=7.43, threshold=20 提供 ~2.7x ratio"
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
    mat_files = sorted(target_root.rglob("*.mat"))
    npy_files = sorted(target_root.rglob("*.npy"))
    files = mat_files + npy_files

    if len(files) == 0:
        raise FileNotFoundError(f"在 {target_root} 下没有找到 .mat 或 .npy 文件")

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