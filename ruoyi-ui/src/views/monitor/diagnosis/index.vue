<template>
  <div class="app-container diagnosis-page">
    <!-- ===== 顶栏：状态指示 + 文件信息 + 操作按钮 ===== -->
    <div class="top-bar">
      <div class="top-left">
        <span class="top-eyebrow">振动诊断</span>
        <span class="top-divider">|</span>
        <!-- 诊断状态标签：待机/分析中/推理失败/已完成，颜色随状态变化 -->
        <span class="top-status" :class="statusClass">{{ resultStateText || '待机' }}</span>
        <span class="top-time">{{ lastUpdateText }}</span>
      </div>
      <div class="top-right">
        <div class="model-picker">
          <span class="model-picker-label">诊断模型</span>
          <el-select
            v-model="selectedModelType"
            size="mini"
            class="model-select"
            popper-class="dark-select-dropdown"
            :disabled="polling || uploading"
            @change="handleModelTypeChange"
          >
            <el-option label="齿轮诊断模型" value="gear" />
            <el-option label="轴承诊断模型" value="bearing" />
          </el-select>
        </div>
        <!-- 当前选中的文件名（溢出省略） -->
        <span class="top-file" :title="selectedFileLabel">{{ selectedFileLabel }}</span>
        <el-button size="mini" type="success" plain @click="handleRefresh">刷新</el-button>
        <el-button size="mini" type="primary" plain @click="uploadDialogVisible = true">上传</el-button>
        <el-button size="mini" type="warning" plain icon="el-icon-download" @click="downloadDialogVisible = true">历史下载</el-button>
      </div>
    </div>

    <!-- ===== 三栏主体：左图表 | 中诊断核心 | 右辅助信息 ===== -->
    <div class="main-area">
      <!-- 左栏：时域图 + 频域图 -->
      <div class="left-column">
        <el-card shadow="hover" class="panel-card chart-card">
          <div slot="header" class="card-header">
            <span class="card-title">时域波形</span>
            <span class="card-unit">位移 / mm</span>
          </div>
          <div ref="timeChartRef" class="chart-box"></div>
          <div v-if="!hasTimeData" class="empty-overlay">暂无数据</div>
        </el-card>
        <el-card shadow="hover" class="panel-card chart-card">
          <div slot="header" class="card-header">
            <span class="card-title">频域频谱</span>
            <span class="card-unit">归一化幅值</span>
          </div>
          <div ref="freqChartRef" class="chart-box"></div>
          <div v-if="!hasFreqData" class="empty-overlay">暂无数据</div>
        </el-card>
      </div>

      <!-- 中栏：诊断核心信息 -->
      <div class="center-column">
        <!-- 健康指数 + 诊断结果 同一行 -->
        <div class="center-hero">
          <!-- 左：健康指数环形仪表 -->
          <div class="health-gauge">
            <div class="gauge-ring" :class="healthBarClass">
              <svg viewBox="0 0 100 100">
                <circle class="g-track" cx="50" cy="50" r="40" />
                <circle class="g-fill" cx="50" cy="50" r="40"
                  :stroke-dasharray="healthDashArray" :class="healthBarClass" />
              </svg>
              <div class="g-inner">
                <span class="g-num">{{ healthIndex > 0 ? healthIndex : '--' }}</span>
              </div>
            </div>
            <span class="gauge-label">健康指数</span>
          </div>

          <!-- 右：诊断结果 + 标签同行 + 元信息 -->
          <div class="hero-right">
            <div class="diag-row">
              <span class="diag-label" :class="resultToneClass">{{ diagnosisName || '--' }}</span>
              <span class="diag-tag" :class="'dt-' + riskBadgeClass">
                <span class="dt-dot"></span>风险 {{ riskLevel || '--' }}
              </span>
              <span class="diag-tag dt-alarm">
                <span class="dt-dot"></span>告警 {{ alarmLevelText }}
              </span>
              <span class="diag-tag dt-model">
                <span class="dt-dot"></span>{{ selectedModelLabel }}
              </span>
            </div>
            <div class="hero-meta">
              <span>模型版本 {{ modelVersion || '--' }}</span>
              <span>推理状态 {{ resultStateText || '待机' }}</span>
            </div>
            <div class="health-bar-wrap">
              <div class="health-bar" :style="{ width: healthBarPercent + '%' }" :class="healthBarClass"></div>
            </div>
          </div>
        </div>

        <!-- 置信度条带 -->
        <div class="confidence-strip">
          <div class="conf-label">
            <span>置信度</span>
            <strong>{{ confidenceText }}</strong>
          </div>
          <div class="conf-bar-wrap">
            <div class="conf-bar" :style="{ width: Math.max(Number(confidence) || 0, 1) + '%' }" :class="confidenceRingClass"></div>
          </div>
        </div>

        <!-- 关键指标网格 -->
        <div class="center-metrics">
          <div class="cm-cell">
            <span class="cm-label">有效值 (RMS)</span>
            <span class="cm-val">{{ displayMetric(latestRms, 4) }}</span>
            <span class="cm-unit">mm/s</span>
          </div>
          <div class="cm-cell">
            <span class="cm-label">峰值 (Peak)</span>
            <span class="cm-val">{{ displayMetric(latestPeak, 4) }}</span>
            <span class="cm-unit">mm/s</span>
          </div>
          <div class="cm-cell">
            <span class="cm-label">未知类别占比</span>
            <span class="cm-val" :class="unknownRatio > 0.5 ? 'val-danger' : unknownRatio > 0.3 ? 'val-warn' : ''">{{ displayMetric(unknownRatio, 4) }}</span>
          </div>
          <div class="cm-cell">
            <span class="cm-label">片段一致性</span>
            <span class="cm-val" :class="segmentConsistency > 0.8 ? 'val-ok' : segmentConsistency > 0.5 ? 'val-warn' : ''">{{ displayMetric(segmentConsistency, 4) }}</span>
          </div>
          <div class="cm-cell">
            <span class="cm-label">平均马氏距离</span>
            <span class="cm-val">{{ displayMetric(meanMahalanobis, 2) }}</span>
          </div>
          <div class="cm-cell">
            <span class="cm-label">平均熵值</span>
            <span class="cm-val">{{ displayMetric(meanEntropy, 4) }}</span>
          </div>
          <div class="cm-cell">
            <span class="cm-label">闭集预测</span>
            <span class="cm-val cm-val-sm">{{ closedPredictionText }}</span>
          </div>
          <div class="cm-cell">
            <span class="cm-label">采样率</span>
            <span class="cm-val cm-val-sm">{{ sampleRate > 0 ? sampleRate + ' Hz' : '--' }}</span>
          </div>
        </div>

        <!-- 健康趋势图 -->
        <div class="health-trend-card">
          <div class="trend-header">
            <span class="trend-title">健康趋势</span>
            <span class="trend-sub">近 7 天日均健康指数</span>
          </div>
          <div ref="healthTrendRef" class="trend-chart"></div>
        </div>
      </div>

      <!-- 右栏：辅助面板 -->
      <div class="right-column">
        <el-card shadow="hover" class="panel-card prob-card">
          <div slot="header" class="card-header">
            <span class="card-title">各类别概率</span>
          </div>
          <div v-if="topProbabilities.length" class="prob-list">
            <div v-for="(item, i) in topProbabilities" :key="i" class="prob-row" v-show="i < 6">
              <span class="prob-class">{{ item.class }}</span>
              <div class="prob-track">
                <div class="prob-fill" :class="getProbBarClass(item.probability)" :style="{ width: Math.max(item.probability, 1) + '%' }"></div>
              </div>
              <span class="prob-pct">{{ formatProbability(item.probability) }}</span>
            </div>
          </div>
          <div v-else class="field-empty">概率字段为空</div>
        </el-card>

        <el-card shadow="hover" class="panel-card evidence-card">
          <div slot="header" class="card-header">
            <span class="card-title">证据链</span>
            <span class="card-badge">{{ evidence.length }}</span>
          </div>
          <div v-if="evidence.length" class="evidence-scroll">
            <div v-for="(item, i) in evidence" :key="i" class="evidence-row" v-show="i < 5">
              <span class="ev-dot" :class="'dot-' + (item.type || 'info')"></span>
              <div class="ev-body">
                <span class="ev-title">{{ item.title }}</span>
                <span class="ev-desc">{{ item.desc }}</span>
              </div>
            </div>
          </div>
          <div v-else class="field-empty">证据字段为空</div>
        </el-card>

        <el-card shadow="hover" class="panel-card reason-card">
          <div slot="header" class="card-header">
            <span class="card-title">决策原因</span>
          </div>
          <div class="reason-text" :class="{ 'is-empty': !decisionReason }">{{ decisionReason || '决策原因字段为空' }}</div>
        </el-card>
      </div>
    </div>

    <!-- ===== 底部：历史记录（紧凑） ===== -->
    <el-card v-if="historyList.length" shadow="hover" class="panel-card history-card">
      <div slot="header" class="card-header">
        <span class="card-title">历史记录</span>
        <span class="card-badge">{{ historyList.length }}</span>
      </div>
      <el-table :data="historyList.slice(0, 3)" size="mini" class="compact-table">
        <el-table-column label="时间" width="160">
          <template slot-scope="scope">{{ parseTime(scope.row.sampleTime) }}</template>
        </el-table-column>
        <el-table-column prop="diagnosisResult" label="诊断结果" min-width="140" />
        <el-table-column prop="confidence" label="置信度" width="70" />
        <el-table-column prop="healthIndex" label="健康" width="60" />
        <el-table-column prop="riskLevel" label="风险" width="60">
          <template slot-scope="scope">
            <span class="history-risk-badge" :class="'history-risk-' + riskTagType(scope.row.riskLevel)">{{ scope.row.riskLevel || '--' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- ===== 历史数据下载弹窗 ===== -->
    <el-dialog title="历史数据下载" :visible.sync="downloadDialogVisible" width="480px" append-to-body custom-class="dark-dialog" @closed="downloadDateRange = []">
      <div class="download-dialog-body">
        <el-alert title="选择时间范围，下载该时段内的诊断记录为 CSV 文件" type="info" show-icon :closable="false" class="download-tip" />
        <el-date-picker
          v-model="downloadDateRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="yyyy-MM-dd HH:mm:ss"
          :default-time="['00:00:00', '23:59:59']"
          popper-class="dark-date-picker"
          style="width: 100%; margin-top: 12px"
        />
        <div class="download-device-row">
          <el-input v-model="downloadDeviceCode" placeholder="设备编码（可选，留空查询全部）" clearable />
        </div>
      </div>
      <span slot="footer" class="dialog-footer">
        <el-button @click="downloadDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="downloading" :disabled="!downloadDateRange || downloadDateRange.length !== 2" @click="handleDownloadHistory">下载 CSV</el-button>
      </span>
    </el-dialog>

    <!-- ===== 上传弹窗 ===== -->
    <el-dialog title="上传推理文件" :visible.sync="uploadDialogVisible" width="480px" append-to-body custom-class="dark-dialog">
      <div class="upload-dialog-body">
        <el-alert title="支持 .mat 和 .npy 文件" type="info" show-icon :closable="false" class="upload-tip" />
        <!-- 文件选择区域：native input 绕过 el-upload 兼容问题 -->
        <div class="upload-dropzone" @click="$refs.nativeFileInput.click()" @dragover.prevent @drop.prevent="handleFileDrop">
          <input
            ref="nativeFileInput"
            type="file"
            accept=".mat,.npy"
            style="display:none"
            @change="handleNativeFileChange"
          />
          <i class="el-icon-upload" />
          <div class="el-upload__text">将文件拖到这里，或<em>点击选择文件</em></div>
          <div class="el-upload__tip">只接受 .mat / .npy 文件</div>
        </div>
        <!-- 本地路径输入行：手动输入文件路径后点击提交 -->
        <div class="path-upload-row">
          <el-input v-model="localFilePath" placeholder="或直接输入本地文件路径" clearable />
          <el-button type="primary" :loading="uploading" @click="uploadByPath(localFilePath)">提交分析</el-button>
        </div>
        <!-- 后端文件下拉选择：列出 DATA_DIR 中的 .mat/.npy 文件 -->
        <div class="mat-file-row">
          <el-select v-model="selectedMatFile" filterable clearable placeholder="自动选择后端最新文件" popper-class="dark-select-dropdown" style="width: 100%" :disabled="polling || uploading" @change="handleSelectedMatFileChange">
            <el-option
              v-for="item in matFileList"
              :key="item.source_name || item.name"
              :label="(item.label || item.name) + (item.modelType ? ' / ' + (item.modelType === 'gear' ? '齿轮' : '轴承') : '')"
              :value="item.source_name || item.name"
            />
          </el-select>
        </div>
      </div>
      <span slot="footer" class="dialog-footer">
        <el-button @click="uploadDialogVisible = false">取消</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script src="./diagnosis-page.js"></script>

<style scoped lang="scss">
@import './diagnosis.scss';
</style>
