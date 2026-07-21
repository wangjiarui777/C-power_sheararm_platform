import torch
import torch.nn as nn


class GradReverse(torch.autograd.Function):
    @staticmethod
    def forward(ctx, x, lambd):
        ctx.lambd = lambd
        return x.view_as(x)

    @staticmethod
    def backward(ctx, grad_output):
        return grad_output.neg() * ctx.lambd, None


class WDCNNMechDG(nn.Module):
    """
    WDCNN + FFT mechanism-band + envelope-spectrum + component-decoupling + SupCon.

    Keys matched against best_model_classwise_maha.pth state dict.
    """

    def __init__(self, num_classes=4, num_domains=2, fs=5120.0, win_len=4096, feat_dim=128):
        super().__init__()
        n_fft_full = int(win_len)
        n_fft_half = n_fft_full // 2 + 1  # 2049 for win_len=4096

        self.fs = float(fs)
        self.win_len = int(win_len)

        # ---- time branch ----
        # net indices: 0(Conv),1(BN),2(ReLU),3(MaxPool),4(Conv)... stride=4
        time_branch = nn.Module()
        time_branch.net = nn.Sequential(
            nn.Conv1d(1, 16, kernel_size=64, padding=32, bias=False),
            nn.BatchNorm1d(16),
            nn.ReLU(inplace=True),
            nn.MaxPool1d(2),
            nn.Conv1d(16, 32, kernel_size=3, padding=1, bias=False),
            nn.BatchNorm1d(32),
            nn.ReLU(inplace=True),
            nn.MaxPool1d(2),
            nn.Conv1d(32, 64, kernel_size=3, padding=1, bias=False),
            nn.BatchNorm1d(64),
            nn.ReLU(inplace=True),
            nn.MaxPool1d(2),
            nn.Conv1d(64, 64, kernel_size=3, padding=1, bias=False),
            nn.BatchNorm1d(64),
            nn.ReLU(inplace=True),
            nn.MaxPool1d(2),
            nn.Conv1d(64, 128, kernel_size=3, padding=1, bias=False),
            nn.BatchNorm1d(128),
            nn.ReLU(inplace=True),
            nn.AdaptiveAvgPool1d(1),
        )
        time_branch.fc = nn.Sequential(
            nn.Flatten(),
            nn.Linear(128, 128),
            nn.BatchNorm1d(128),
        )
        self.time_branch = time_branch

        # ---- mechanism energy (62 band masks on half-spectrum) ----
        self.mech_energy = nn.Module()
        self.mech_energy.register_buffer("masks", torch.zeros(62, n_fft_half))
        self.mech_energy.register_buffer("hann", torch.hann_window(n_fft_full))

        # mech_branch indices: 0(Linear),1(BN),2(ReLU),3(Dropout),4(Linear),5(BN)
        self.mech_branch = nn.Sequential()
        self.mech_branch.net = nn.Sequential(
            nn.Linear(62, 128),
            nn.BatchNorm1d(128),
            nn.ReLU(inplace=True),
            nn.Dropout(0.2),
            nn.Linear(128, 64),
            nn.BatchNorm1d(64),
        )

        # ---- envelope energy ----
        self.envelope_energy = nn.Module()
        self.envelope_energy.register_buffer("masks", torch.zeros(62, n_fft_half))
        self.envelope_energy.register_buffer("hann", torch.hann_window(n_fft_full))
        self.envelope_energy.register_buffer("hilbert_filter", torch.zeros(n_fft_full))

        # envelope_branch indices: 0(Linear),1(BN),2(ReLU),3(Dropout),4(Linear),5(BN)
        self.envelope_branch = nn.Sequential()
        self.envelope_branch.net = nn.Sequential(
            nn.Linear(62, 128),
            nn.BatchNorm1d(128),
            nn.ReLU(inplace=True),
            nn.Dropout(0.2),
            nn.Linear(128, 64),
            nn.BatchNorm1d(64),
        )

        # ---- decouple branch ----
        decouple = nn.Module()
        decouple.register_buffer("group_masks", torch.zeros(5, n_fft_half))
        decouple.register_buffer("hann", torch.hann_window(n_fft_full))
        # component_mlps indices: 0(Linear),1(ReLU),2(Dropout),3(Linear),4(ReLU)
        decouple.component_mlps = nn.ModuleList([
            nn.Sequential(
                nn.Linear(n_fft_half, 128),
                nn.ReLU(inplace=True),
                nn.Dropout(0.2),
                nn.Linear(128, 32),
                nn.ReLU(inplace=True),
            )
            for _ in range(5)
        ])
        decouple.fusion = nn.Sequential(
            nn.Linear(5 * 32, 128),
            nn.BatchNorm1d(128),
        )
        self.decouple_branch = decouple

        # ---- fusion (128 time + 64 mech + 64 env + 128 decouple = 384) ----
        self.fusion = nn.Sequential(
            nn.Linear(128 + 64 + 64 + 128, feat_dim),
            nn.BatchNorm1d(feat_dim),
        )

        self.classifier = nn.Linear(feat_dim, num_classes)

        # domain_classifier indices: 0(Linear),1(ReLU),2(Dropout),3(Linear)
        self.domain_classifier = nn.Sequential(
            nn.Linear(feat_dim, 64),
            nn.ReLU(inplace=True),
            nn.Dropout(0.2),
            nn.Linear(64, num_domains),
        )

    def forward(self, x, grl_lambda=0.0):
        B, C, L = x.shape

        # ---- time branch ----
        time_feat = self.time_branch.net(x)
        time_feat = self.time_branch.fc(time_feat)

        # ---- FFT (shared) ----
        hann = self.mech_energy.hann.to(x.device).view(1, 1, L)
        xw = (x * hann).reshape(B, L)
        X = torch.fft.rfft(xw, dim=1)
        mag = torch.abs(X)

        # ---- mechanism branch: band energy -> MLP ----
        mech_energy = (mag.unsqueeze(1) * self.mech_energy.masks.unsqueeze(0)).sum(dim=2)
        mech_feat = self.mech_branch.net(mech_energy)

        # ---- envelope branch: Hilbert -> envelope spectrum -> band energy -> MLP ----
        hilbert_filt = self.envelope_energy.hilbert_filter.to(x.device)
        full_fft = torch.zeros(B, L, dtype=torch.complex64, device=x.device)
        full_fft[:, :X.shape[1]] = X * hilbert_filt[:X.shape[1]]
        analytic = torch.fft.ifft(full_fft, dim=1)
        envelope = torch.abs(analytic)
        env_X = torch.fft.rfft(envelope, dim=1)
        env_mag = torch.abs(env_X)
        env_energy = (env_mag.unsqueeze(1) * self.envelope_energy.masks.unsqueeze(0)).sum(dim=2)
        env_feat = self.envelope_branch.net(env_energy)

        # ---- decouple branch: 5 sub-band MLPs -> fusion ----
        masked = mag.unsqueeze(1) * self.decouple_branch.group_masks.unsqueeze(0)
        comp_feats = []
        for mlp in self.decouple_branch.component_mlps:
            comp_feats.append(mlp(masked[:, len(comp_feats), :]))
        decouple_feat = self.decouple_branch.fusion(torch.cat(comp_feats, dim=1))

        # ---- fusion ----
        feat = self.fusion(torch.cat([time_feat, mech_feat, env_feat, decouple_feat], dim=1))

        # ---- classifier ----
        logits = self.classifier(feat)

        # ---- domain classifier (GRL) ----
        if grl_lambda > 0:
            dom_in = GradReverse.apply(feat, grl_lambda)
            dom_logits = self.domain_classifier(dom_in)
        else:
            dom_logits = None

        return {
            "logits": logits,
            "feat": feat,
            "domain_logits": dom_logits,
        }
