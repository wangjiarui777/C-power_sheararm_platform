<template>
  <div class="diagnosis-route-page">
    <measurement-point-overview v-if="!detailMode" />
    <div v-else class="app-container diagnosis-page">
    <!-- ===== 顶栏：状态指示 + 文件信息 + 操作按钮 ===== -->
    <div class="top-bar">
      <div class="top-left">
        <el-button class="overview-back" type="text" icon="el-icon-arrow-left" @click="backToOverview">测点总览</el-button>
        <span class="top-eyebrow">振动诊断</span>
        <span class="top-divider">|</span>
        <!-- 诊断状态标签：待机/分析中/推理失败/已完成，颜色随状态变化 -->
        <span class="top-status" :class="statusClass">{{ resultStateText || '待机' }}</span>
        <span class="top-time">{{ lastUpdateText }}</span>
      </div>
      <div class="top-right">
        <!-- 当前选中的文件名（溢出省略） -->
        <span class="top-file" :title="selectedFileLabel">{{ selectedFileLabel }}</span>
        <el-button size="mini" type="success" plain :disabled="!contextComplete" @click="handleRefresh">刷新状态</el-button>
        <el-button size="mini" type="primary" plain :disabled="!contextComplete || polling" @click="uploadDialogVisible = true">配置文件并诊断</el-button>
        <el-button size="mini" type="warning" plain icon="el-icon-download" @click="downloadDialogVisible = true">历史下载</el-button>
      </div>
    </div>

    <!-- 设备 → 测点 → 模型 → 版本，构成一次可追溯诊断的完整上下文。 -->
    <section class="context-rail" :class="{ 'is-ready': contextComplete, 'is-loading': optionsLoading }" aria-label="诊断上下文">
      <div class="context-heading">
        <span class="context-kicker">DIAG CONTEXT</span>
        <strong>诊断上下文</strong>
        <span class="context-state">
          <i class="context-state-dot" />{{ optionsLoading ? '正在装载选项' : (contextComplete ? '上下文就绪' : '等待完整选择') }}
        </span>
      </div>
      <div class="context-field">
        <label for="diagnosis-device">01 / 设备</label>
        <el-select id="diagnosis-device" v-model="selectedDeviceCode" filterable clearable placeholder="选择设备" popper-class="dark-select-dropdown" :loading="optionsLoading" :disabled="optionsLoading || uploading" @change="handleDeviceChange">
          <el-option v-for="item in deviceOptions" :key="item.deviceCode" :label="`${item.deviceName || '未命名设备'} · ${item.deviceCode}`" :value="item.deviceCode" />
        </el-select>
      </div>
      <div class="context-link" aria-hidden="true">›</div>
      <div class="context-field context-field-point">
        <label for="diagnosis-point">02 / 测点</label>
        <el-select id="diagnosis-point" v-model="selectedPointIds" multiple collapse-tags filterable clearable :multiple-limit="multiPointEnabled ? maxBatchPoints : 1" placeholder="选择振动测点" popper-class="dark-select-dropdown" :loading="optionsLoading" :disabled="optionsLoading || uploading" @change="handlePointChange">
          <el-option v-for="item in availablePointOptions" :key="item.id" :label="pointOptionLabel(item)" :value="String(item.id)" />
        </el-select>
      </div>
      <div class="context-link" aria-hidden="true">›</div>
      <div class="context-field">
        <label for="diagnosis-model">03 / 模型类型</label>
        <el-select id="diagnosis-model" v-model="selectedModelType" filterable clearable placeholder="选择模型" popper-class="dark-select-dropdown" :disabled="optionsLoading || uploading" @change="handleModelTypeChange">
          <el-option v-for="item in modelTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </div>
      <div class="context-link" aria-hidden="true">›</div>
      <div class="context-field context-field-version">
        <label for="diagnosis-version">04 / 模型版本</label>
        <el-select id="diagnosis-version" v-model="selectedModelVersion" filterable clearable placeholder="选择可执行版本" popper-class="dark-select-dropdown" :disabled="!selectedModelType || optionsLoading || uploading" @change="handleVersionChange">
          <el-option v-for="item in availableModelVersions" :key="`${item.modelType}-${item.semanticVersion}`" :label="versionOptionLabel(item)" :value="item.semanticVersion" :disabled="!item.available">
            <span>{{ item.semanticVersion }}</span><span class="version-option-status" :class="`status-${String(item.status).toLowerCase()}`">{{ item.status }}{{ item.available ? '' : ' · 不可用' }}</span>
          </el-option>
        </el-select>
      </div>
      <div v-if="contextNotice || retiredVersionSelected" class="context-notice" :class="{ 'is-warning': retiredVersionSelected, 'is-error': contextError }" role="status">
        <i :class="retiredVersionSelected ? 'el-icon-warning-outline' : (contextError ? 'el-icon-circle-close' : 'el-icon-info')" />
        <span>{{ retiredVersionSelected ? '该 RETIRED 版本仅用于回溯诊断，不产生正式告警。' : contextNotice }}</span>
        <el-button v-if="noAttachment" type="text" @click="uploadDialogVisible = true">选择诊断文件</el-button>
      </div>
    </section>

    <section v-if="diagnosisBatchId" class="point-matrix" aria-label="多测点诊断进度">
      <div class="point-matrix-head">
        <div><span class="context-kicker">POINT MATRIX</span><strong>测点诊断总览</strong></div>
        <div class="point-matrix-actions">
          <span class="batch-progress">{{ batchProgressText }}</span>
          <el-button v-if="batchHasFailures" type="warning" plain size="mini" :loading="polling" @click="retryFailedPoints">重试失败项</el-button>
        </div>
      </div>
      <div class="point-matrix-grid">
        <button
          v-for="item in diagnosisBatchItems"
          :key="item.pointId"
          type="button"
          class="point-cell"
          :class="[`status-${String(item.status || '').toLowerCase()}`, { 'is-active': String(activePointId) === String(item.pointId) }]"
          @click="selectBatchPoint(item)"
        >
          <span class="point-cell-code">CH {{ item.channelId == null ? '--' : item.channelId }}</span>
          <strong>{{ item.pointName || item.pointCode || `测点 ${item.pointId}` }}</strong>
          <span class="point-cell-status">{{ batchItemStatusText(item.status) }}</span>
          <span v-if="item.result" class="point-cell-metric">健康 {{ item.result.healthIndex == null ? '--' : item.result.healthIndex }} · 风险 {{ item.result.riskLevel || '--' }}</span>
          <span v-else-if="item.errorMessage" class="point-cell-error" :title="item.errorMessage">{{ item.errorMessage }}</span>
        </button>
      </div>
    </section>

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
    <el-dialog title="多测点文件映射" :visible.sync="uploadDialogVisible" width="920px" append-to-body custom-class="dark-dialog mapping-dialog" @open="handleUploadDialogOpen" @closed="handleUploadDialogClosed">
      <div class="upload-dialog-body">
        <el-alert title="每个测点需要一个 .mat 或 .npy 文件；本机文件将在开始诊断时上传" type="info" show-icon :closable="false" class="upload-tip" />
        <div class="upload-context-summary">{{ selectedDeviceCode }} / {{ selectedPointLabel }} / {{ selectedModelLabel }} / {{ selectedModelVersion }}</div>
        <el-table :data="mappingRows" size="mini" class="mapping-table" highlight-current-row @row-click="row => selectMappingPoint(row.id)">
          <el-table-column label="测点" min-width="180">
            <template slot-scope="scope"><strong>{{ scope.row.pointName || scope.row.pointCode }}</strong><small>CH {{ scope.row.channelId }}</small></template>
          </el-table-column>
          <el-table-column label="已映射文件" min-width="260" show-overflow-tooltip>
            <template slot-scope="scope">{{ scope.row.attachmentName || '尚未配置' }}</template>
          </el-table-column>
          <el-table-column label="来源" width="100">
            <template slot-scope="scope">{{ scope.row.localFile || scope.row.sourceType === 'BROWSER_UPLOAD' ? '本机上传' : (scope.row.attachmentId ? '服务器文件' : '--') }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="right">
            <template slot-scope="scope"><el-button type="text" @click.stop="selectMappingPoint(scope.row.id)">配置</el-button></template>
          </el-table-column>
        </el-table>
        <div v-if="activeMappingPointOption" class="mapping-editor-title">正在配置：{{ activeMappingPointOption.pointName || activeMappingPointOption.pointCode }} / CH {{ activeMappingPointOption.channelId }}</div>
        <el-tabs v-if="activeMappingPointOption" v-model="uploadSourceTab" stretch>
          <el-tab-pane label="本机上传" name="local">
            <div class="upload-dropzone" @click="$refs.nativeFileInput.click()" @dragover.prevent @drop.prevent="handleFileDrop">
              <input ref="nativeFileInput" type="file" accept=".mat,.npy" style="display:none" @change="handleNativeFileChange" />
              <i class="el-icon-upload" />
              <div class="el-upload__text">将文件拖到这里，或<em>点击选择文件</em></div>
              <div class="el-upload__tip">只接受 .mat / .npy 文件，最大 128MB</div>
            </div>
            <div v-if="pendingUploadFile" class="selected-local-file">
              <i class="el-icon-document" />
              <span>{{ pendingUploadFile.name }}</span>
              <small>{{ formatFileSize(pendingUploadFile.size) }}</small>
            </div>
          </el-tab-pane>
          <el-tab-pane label="服务器文件" name="server">
            <div class="server-file-toolbar">
              <span>仅显示已安全登记并绑定当前测点的文件</span>
              <el-button type="text" icon="el-icon-refresh" :loading="serverFileLoading" @click="refreshServerFiles">刷新</el-button>
            </div>
            <el-table
              v-if="matFileList.length"
              v-loading="serverFileLoading"
              :data="matFileList"
              max-height="250"
              highlight-current-row
              size="mini"
              @current-change="row => handleSelectedMatFileChange(row && row.id)"
            >
              <el-table-column width="48" align="center">
                <template slot-scope="scope"><el-radio v-model="selectedMatFile" :label="scope.row.id"><span /></el-radio></template>
              </el-table-column>
              <el-table-column prop="name" label="文件名" min-width="180" show-overflow-tooltip />
              <el-table-column label="来源" width="80"><template slot-scope="scope">{{ scope.row.sourceType === 'SERVER_DIRECTORY' ? '目录接入' : '本机上传' }}</template></el-table-column>
              <el-table-column label="大小" width="90"><template slot-scope="scope">{{ formatFileSize(scope.row.fileSize) }}</template></el-table-column>
              <el-table-column label="接入时间" width="150"><template slot-scope="scope">{{ parseTime(scope.row.createdAt, '{y}-{m}-{d} {h}:{i}') }}</template></el-table-column>
            </el-table>
            <el-empty v-else :image-size="64" description="当前设备暂无服务器文件">
              <div class="server-inbox-hint">请将文件放入：接入根目录 / {{ selectedDeviceCode }} / {{ activeMappingPointOption.pointCode }} /</div>
            </el-empty>
          </el-tab-pane>
        </el-tabs>
      </div>
      <span slot="footer" class="dialog-footer">
        <el-button @click="uploadDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading || polling" :disabled="!fileMappingComplete" @click="startBatchAnalysis">开始批量诊断</el-button>
      </span>
    </el-dialog>
    </div>
  </div>
</template>

<script src="./diagnosis-page.js"></script>

<style scoped lang="scss">
@import './diagnosis.scss';
</style>
