"""
ResNet-18 adapted for 1-D vibration signals.

Architecture matches best_model.pth checkpoint:
  stem: Conv1d(1,64,k7,s2,p3) → BN → ReLU → MaxPool1d(k3,s2,p1)
  layer1: 2× BasicBlock(64→64, stride=1)
  layer2: 2× BasicBlock(64→128, stride=2, downsample)
  layer3: 2× BasicBlock(128→256, stride=2, downsample)
  layer4: 2× BasicBlock(256→512, stride=2, downsample)
  AdaptiveAvgPool1d(1) → FC(512, num_classes)
"""

from typing import Optional, Tuple

import torch
import torch.nn as nn


class BasicBlock1d(nn.Module):
    """ResNet basic block for 1-D signals."""

    expansion: int = 1

    def __init__(
        self,
        in_channels: int,
        out_channels: int,
        stride: int = 1,
        downsample: Optional[nn.Module] = None,
    ) -> None:
        super().__init__()
        self.conv1 = nn.Conv1d(
            in_channels,
            out_channels,
            kernel_size=3,
            stride=stride,
            padding=1,
            bias=False,
        )
        self.bn1 = nn.BatchNorm1d(out_channels)
        self.relu = nn.ReLU(inplace=True)
        self.conv2 = nn.Conv1d(
            out_channels,
            out_channels,
            kernel_size=3,
            stride=1,
            padding=1,
            bias=False,
        )
        self.bn2 = nn.BatchNorm1d(out_channels)
        self.downsample = downsample

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        identity = x

        out = self.conv1(x)
        out = self.bn1(out)
        out = self.relu(out)

        out = self.conv2(out)
        out = self.bn2(out)

        if self.downsample is not None:
            identity = self.downsample(x)

        out += identity
        out = self.relu(out)
        return out


class ResNet1D18(nn.Module):
    """
    ResNet-18 for 1-D vibration signal classification.

    Matches the checkpoint saved by the CCDG bearing training pipeline:
        stem.0.weight  → [64, 1, 7]
        layer1.0.conv1  → [64, 64, 3]
        layer2.0.conv1  → [128, 64, 3]  (stride=2, downsample present)
        layer3.0.conv1  → [256, 128, 3] (stride=2, downsample present)
        layer4.0.conv1  → [512, 256, 3] (stride=2, downsample present)
        fc.weight       → [num_classes, 512]
    """

    def __init__(self, num_classes: int = 3) -> None:
        super().__init__()
        self.in_channels = 64

        # ---- stem ----
        self.stem = nn.Sequential(
            nn.Conv1d(1, 64, kernel_size=7, stride=2, padding=3, bias=False),
            nn.BatchNorm1d(64),
            nn.ReLU(inplace=True),
            nn.MaxPool1d(kernel_size=3, stride=2, padding=1),
        )

        # ---- residual layers ----
        self.layer1 = self._make_layer(64, 2, stride=1)
        self.layer2 = self._make_layer(128, 2, stride=2)
        self.layer3 = self._make_layer(256, 2, stride=2)
        self.layer4 = self._make_layer(512, 2, stride=2)

        # ---- head ----
        self.gap = nn.AdaptiveAvgPool1d(1)
        self.fc = nn.Linear(512, num_classes)

        # ---- init ----
        self._init_weights()

    def _make_layer(
        self, out_channels: int, blocks: int, stride: int
    ) -> nn.Sequential:
        downsample: Optional[nn.Module] = None
        if stride != 1 or self.in_channels != out_channels * BasicBlock1d.expansion:
            downsample = nn.Sequential(
                nn.Conv1d(
                    self.in_channels,
                    out_channels * BasicBlock1d.expansion,
                    kernel_size=1,
                    stride=stride,
                    bias=False,
                ),
                nn.BatchNorm1d(out_channels * BasicBlock1d.expansion),
            )

        layers: list[nn.Module] = []
        layers.append(
            BasicBlock1d(self.in_channels, out_channels, stride=stride, downsample=downsample)
        )
        self.in_channels = out_channels * BasicBlock1d.expansion
        for _ in range(1, blocks):
            layers.append(BasicBlock1d(self.in_channels, out_channels, stride=1))

        return nn.Sequential(*layers)

    def _init_weights(self) -> None:
        for m in self.modules():
            if isinstance(m, nn.Conv1d):
                nn.init.kaiming_normal_(m.weight, mode="fan_out", nonlinearity="relu")
            elif isinstance(m, nn.BatchNorm1d):
                nn.init.constant_(m.weight, 1.0)
                nn.init.constant_(m.bias, 0.0)

    def forward(self, x: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor]:
        """
        Args:
            x: [B, 1, L]  (batch, channel=1, signal_length)

        Returns:
            logits: [B, num_classes]
            feat:   [B, 512]
        """
        x = self.stem(x)
        x = self.layer1(x)
        x = self.layer2(x)
        x = self.layer3(x)
        x = self.layer4(x)
        feat = self.gap(x).flatten(1)
        logits = self.fc(feat)
        return logits, feat
