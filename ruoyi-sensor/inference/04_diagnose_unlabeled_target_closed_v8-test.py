# -*- coding: utf-8 -*-
"""
04_diagnose_unlabeled_target_closed.py

v8 闭集无标签目标域诊断。

不输出 unknown。
不启用 Mahalanobis。
不计算开集指标。

输出：
    prediction
    direct_prob_healthy / outer_ring / rolling_element
    binary_prob_healthy / binary_prob_fault
    decision_reason
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Dict, Optional

import numpy as np
import pandas as pd
from tqdm import tqdm

import torch
from sklearn.metrics import accuracy_score, f1_score, classification_report, confusion_matrix

from utils_signal8 import (
    load_signal,
    zscore_1d,
    count_windows,
    get_window,
    list_files_by_exts,
    parse_rpm_from_text,
    top_fft_peaks,
)
from models.wdcnn_v8_bearing_dg import WDCNNV8DG
from decision_v8 import decide_v8


def safe_torch_load(path, map_location):
    try:
        return torch.load(path, map_location=map_location, weights_only=False)
    except TypeError:
        return torch.load(path, map_location=map_location)


def parse_csv_list(s: str):
    return [x.strip() for x in str(s).split(",") if x.strip()]


def parse_folder_class_map(s: str) -> Dict[str, str]:
    out = {}
    for pair in parse_csv_list(s):
        if ":" not in pair:
            raise ValueError(f"folder_class_map 格式错误: {pair}")
        k, v = pair.split(":", 1)
        out[k.strip()] = v.strip()
    return out


def infer_true_label_from_path(path: Path, folder_class_map: Dict[str, str]) -> Optional[str]:
    for p in reversed(path.parts[:-1]):
        if p in folder_class_map:
            return folder_class_map[p]
    return None


@torch.no_grad()
def predict_file_v8(
    model,
    path,
    rpm,
    device,
    win_len,
    stride,
    signal_key,
    column,
    batch_size,
    class_names,
    fs,
    decision_kwargs,
):
    sig = load_signal(path, signal_key=signal_key, column=column)
    n_win = count_windows(len(sig), win_len, stride)

    direct_all = []
    binary_all = []
    seg_preds = []

    model.eval()

    for i in range(0, n_win, batch_size):
        xs, rpms = [], []
        for j in range(i, min(i + batch_size, n_win)):
            x = get_window(sig, j * stride, win_len)
            x = zscore_1d(x)
            xs.append(x)
            rpms.append(float(rpm))

        xb = torch.from_numpy(np.stack(xs)).float().unsqueeze(1).to(device)
        rb = torch.tensor(rpms, dtype=torch.float32, device=device)

        out = model(xb, rb, grl_lambda=0.0)

        direct = torch.softmax(out["logits"], dim=1).cpu()
        binary = torch.softmax(out["binary_logits"], dim=1).cpu()

        direct_all.append(direct)
        binary_all.append(binary)

        for k in range(direct.shape[0]):
            p, _ = decide_v8(
                direct[k].numpy(),
                binary[k].numpy(),
                **decision_kwargs,
            )
            seg_preds.append(p)

    direct_all = torch.cat(direct_all, dim=0)
    binary_all = torch.cat(binary_all, dim=0)

    direct_mean = direct_all.mean(dim=0).numpy()
    binary_mean = binary_all.mean(dim=0).numpy()

    file_pred, file_reason = decide_v8(
        direct_mean,
        binary_mean,
        **decision_kwargs,
    )

    seg_preds_np = np.asarray(seg_preds, dtype=np.int64)
    values, counts = np.unique(seg_preds_np, return_counts=True)
    vote_pred = int(values[np.argmax(counts)])
    vote_ratio = float(np.max(counts) / len(seg_preds_np))

    if vote_ratio >= 0.80 and vote_pred != file_pred and file_reason.startswith("gray"):
        final_pred = vote_pred
        decision_reason = "segment_vote_override_" + file_reason
    else:
        final_pred = int(file_pred)
        decision_reason = file_reason

    direct_pred = int(np.argmax(direct_mean))
    binary_pred = int(np.argmax(binary_mean))

    row = {
        "file": str(Path(path).resolve()),
        "rpm": float(rpm),
        "prediction": class_names[final_pred],
        "confidence": float(direct_mean[final_pred]),
        "segment_consistency": float((seg_preds_np == final_pred).mean()),
        "num_segments": int(n_win),
        "decision_reason": decision_reason,

        "direct_prediction": class_names[direct_pred],
        "binary_prediction": "fault" if binary_pred == 1 else "healthy",

        "vote_prediction": class_names[vote_pred],
        "vote_ratio": vote_ratio,

        "binary_prob_healthy": float(binary_mean[0]),
        "binary_prob_fault": float(binary_mean[1]),

        "top_fft_peaks": top_fft_peaks(sig, fs=fs, n_peaks=8, f_min=1.0),
    }

    for i, c in enumerate(class_names):
        row[f"direct_prob_{c}"] = float(direct_mean[i])

    return row


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--target_root", type=str, default="D:\Dr_treasurechest\\05_code\\01-data\self_bearing\\unlabel\\2400rpm")
    parser.add_argument("--ckpt", type=str, default="D:\Dr_treasurechest\\05_code\\02-cpower_code\\bearing_closed_dg_mat_selfbearing_v2\\bearing_closed_dg_code\\runs_v8\\bearing_dg\\best_model.pth")
    parser.add_argument("--out_csv", type=str, default="D:\Dr_treasurechest\\05_code\\02-cpower_code\\bearing_closed_dg_mat_selfbearing_v2\\bearing_closed_dg_code\\runs_v8-test\\results\\unlabel\\2400rpm.csv")
    parser.add_argument("--signal_key", type=str, default="DE_time", help="为空时使用 checkpoint 中保存的 signal_key")
    parser.add_argument("--column", type=int, default=None)
    parser.add_argument("--file_exts", type=str, default=".mat")
    parser.add_argument("--fs", type=float, default=None)
    parser.add_argument("--rpm", type=float, default=None)
    parser.add_argument("--win_len", type=int, default=None)
    parser.add_argument("--stride", type=int, default=None)
    parser.add_argument("--batch_size", type=int, default=128)
    parser.add_argument("--device", type=str, default="cuda")

    parser.add_argument("--folder_class_map", type=str, default="N:healthy,OR:outer_ring,B:rolling_element")
    parser.add_argument("--eval_if_label_in_path", action="store_true")

    # 诊断阈值可不重新训练微调
    parser.add_argument("--healthy_accept_thr", type=float, default=None)
    parser.add_argument("--fault_accept_thr", type=float, default=None)
    parser.add_argument("--gray_direct_fault_thr", type=float, default=None)
    parser.add_argument("--min_fault_gap", type=float, default=None)
    parser.add_argument("--or_tie_delta", type=float, default=None)
    parser.add_argument("--or_min_prob", type=float, default=None)

    args = parser.parse_args()

    target_root = Path(args.target_root)
    out_csv = Path(args.out_csv)
    out_csv.parent.mkdir(parents=True, exist_ok=True)

    device = torch.device(args.device if torch.cuda.is_available() and args.device.startswith("cuda") else "cpu")
    ckpt = safe_torch_load(args.ckpt, map_location=device)

    class_names = ckpt.get("classes", ["healthy", "outer_ring", "rolling_element"])
    fs = float(args.fs if args.fs is not None else ckpt.get("fs", 7500.0))
    win_len = int(args.win_len if args.win_len is not None else ckpt.get("win_len", 4096))
    stride = int(args.stride if args.stride is not None else ckpt.get("stride", 1024))
    signal_key = args.signal_key if args.signal_key is not None else ckpt.get("signal_key", "DE_time")
    column = args.column if args.column is not None else ckpt.get("column", None)

    decision_kwargs = dict(ckpt.get("decision_v8", {}))
    default_decision = {
        "healthy_accept_thr": 0.78,
        "fault_accept_thr": 0.55,
        "gray_direct_fault_thr": 0.38,
        "min_fault_gap": 0.03,
        "or_tie_delta": 0.06,
        "or_min_prob": 0.22,
    }
    for k, v in default_decision.items():
        decision_kwargs.setdefault(k, v)

    for key in [
        "healthy_accept_thr",
        "fault_accept_thr",
        "gray_direct_fault_thr",
        "min_fault_gap",
        "or_tie_delta",
        "or_min_prob",
    ]:
        val = getattr(args, key)
        if val is not None:
            decision_kwargs[key] = float(val)

    model = WDCNNV8DG(
        num_classes=len(class_names),
        num_domains=int(ckpt.get("num_domains", 2)),
        fs=fs,
        win_len=win_len,
        feat_dim=256,
    ).to(device)
    model.load_state_dict(ckpt["model_state"], strict=True)
    model.eval()

    files = list_files_by_exts(target_root, parse_csv_list(args.file_exts))
    if not files:
        raise FileNotFoundError(f"未在 {target_root} 下找到 {args.file_exts} 文件")

    rpm = args.rpm
    if rpm is None:
        rpm = parse_rpm_from_text(str(target_root), default=None)
    if rpm is None:
        rpm = 2400.0
        print(f"[警告] 未能从路径解析 rpm，使用默认 rpm={rpm}")

    folder_class_map = parse_folder_class_map(args.folder_class_map)

    print("=" * 100)
    print("v8 闭集目标域诊断")
    print("=" * 100)
    print(f"target_root = {target_root}")
    print(f"files       = {len(files)}")
    print(f"classes     = {class_names}")
    print(f"rpm         = {rpm}")
    print(f"fs          = {fs}")
    print(f"signal_key  = {signal_key}")
    print(f"win/stride  = {win_len}/{stride}")
    print(f"decision    = {decision_kwargs}")
    print("=" * 100)

    rows = []
    for p in tqdm(files, desc="Diagnose files", ncols=100):
        row = predict_file_v8(
            model=model,
            path=p,
            rpm=rpm,
            device=device,
            win_len=win_len,
            stride=stride,
            signal_key=signal_key,
            column=column,
            batch_size=args.batch_size,
            class_names=class_names,
            fs=fs,
            decision_kwargs=decision_kwargs,
        )

        if args.eval_if_label_in_path:
            row["true_label"] = infer_true_label_from_path(p, folder_class_map) or ""

        rows.append(row)

    df = pd.DataFrame(rows)
    df.to_csv(out_csv, index=False, encoding="utf-8-sig")
    print(f"[保存] {out_csv}")

    counts = df["prediction"].value_counts().rename_axis("prediction").reset_index(name="count")
    counts_path = out_csv.with_name(out_csv.stem + "_prediction_counts.csv")
    counts.to_csv(counts_path, index=False, encoding="utf-8-sig")
    print(f"[保存] {counts_path}")

    if args.eval_if_label_in_path and "true_label" in df.columns:
        eval_df = df[df["true_label"].astype(str).isin(class_names)].copy()
        if len(eval_df) > 0:
            y_true = [class_names.index(x) for x in eval_df["true_label"].astype(str)]
            y_pred = [class_names.index(x) for x in eval_df["prediction"].astype(str)]

            acc = accuracy_score(y_true, y_pred)
            mf1 = f1_score(y_true, y_pred, average="macro", zero_division=0)

            print("=" * 100)
            print(f"ACC      = {acc:.4f}")
            print(f"Macro-F1 = {mf1:.4f}")
            print("=" * 100)
            print(classification_report(
                y_true,
                y_pred,
                labels=list(range(len(class_names))),
                target_names=class_names,
                zero_division=0,
            ))

            cm = pd.DataFrame(
                confusion_matrix(y_true, y_pred, labels=list(range(len(class_names)))),
                index=class_names,
                columns=class_names,
            )
            cm_path = out_csv.with_name(out_csv.stem + "_confusion_matrix.csv")
            cm.to_csv(cm_path, encoding="utf-8-sig")

            report_path = out_csv.with_name(out_csv.stem + "_classification_report.txt")
            with open(report_path, "w", encoding="utf-8") as f:
                f.write(classification_report(
                    y_true,
                    y_pred,
                    labels=list(range(len(class_names))),
                    target_names=class_names,
                    zero_division=0,
                ))

            summary_path = out_csv.with_name(out_csv.stem + "_summary.json")
            with open(summary_path, "w", encoding="utf-8") as f:
                json.dump({"acc": float(acc), "macro_f1": float(mf1)}, f, ensure_ascii=False, indent=2)

            print(f"[保存] {cm_path}")
            print(f"[保存] {report_path}")
            print(f"[保存] {summary_path}")


if __name__ == "__main__":
    main()
