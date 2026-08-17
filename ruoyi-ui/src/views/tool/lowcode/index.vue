<template>
  <div class="lc-shell">
    <header class="lc-command-bar">
      <div>
        <span class="lc-eyebrow">工业配置工作台</span>
        <h1>低代码工作台</h1>
        <p>把数据对象、页面规则和诊断动作封装为可审计的发布版本。</p>
      </div>
      <div class="lc-header-actions">
        <el-button v-hasPermi="['tool:lowcode:design']" icon="el-icon-plus" @click="openCreate">新建项目</el-button>
        <el-button v-hasPermi="['tool:lowcode:design']" type="primary" icon="el-icon-check" :disabled="!current.id" @click="save">保存草稿</el-button>
      </div>
    </header>

    <section class="release-rail" aria-label="发布状态">
      <div v-for="(step, index) in releaseSteps" :key="step.key" class="release-node" :class="step.state">
        <span class="release-index">{{ index + 1 }}</span>
        <div><b>{{ step.label }}</b><small>{{ step.caption }}</small></div>
      </div>
      <div class="release-actions">
        <el-button v-hasPermi="['tool:lowcode:validate']" size="small" :disabled="!current.id" @click="validateCurrent">完整校验</el-button>
        <el-button v-hasPermi="['tool:lowcode:design']" size="small" :disabled="!current.id" @click="showDiff">查看差异</el-button>
        <el-button v-hasPermi="['tool:lowcode:publish']" size="small" type="success" :disabled="!validation.valid" @click="publishCurrent">发布版本</el-button>
      </div>
    </section>

    <main class="lc-grid">
      <aside class="project-rail">
        <div class="rail-title"><span>项目</span><em>{{ projects.length }}</em></div>
        <button v-for="item in projects" :key="item.id" class="project-card" :class="{ active: item.id === current.id }" @click="selectProject(item.id)">
          <span class="project-code">{{ item.appCode }}</span>
          <strong>{{ item.projectName }}</strong>
          <small>草稿 v{{ item.draftVersionNo || '-' }} · 已发布 v{{ item.activeVersionNo || '-' }}</small>
        </button>
        <div v-if="!projects.length" class="rail-empty">先创建一个通用 CRUD 或测点诊断项目。</div>
      </aside>

      <section class="designer-stage">
        <el-tabs v-if="current.id" v-model="activeWorkspace" class="workspace-tabs">
          <el-tab-pane v-if="isPipeline" label="工业诊断管道" name="pipeline">
            <div class="pipeline-bus"><span class="bus-dot" /> 测点接入 <i class="el-icon-right" /> IoTDB / 文件 <i class="el-icon-right" /> 振动分析 <i class="el-icon-right" /> 固定模型诊断 <i class="el-icon-right" /> 结果告警</div>
            <div class="stage-toolbar"><div><h2>五阶段信号链</h2><p>每个绑定固定设备、测点、通道和已验证模型版本。</p></div><div><el-button v-hasPermi="['tool:lowcode:test']" size="small" @click="runPipelineTest">试运行</el-button><el-button v-hasPermi="['tool:lowcode:activate']" size="small" type="success" @click="activatePipelineNow">启用管道</el-button><el-button v-hasPermi="['tool:lowcode:activate']" size="small" @click="deactivatePipelineNow">停用</el-button></div></div>
            <el-steps :active="pipelineStage" finish-status="success" simple class="pipeline-steps"><el-step title="测点接入"/><el-step title="IoTDB 映射"/><el-step title="模型服务"/><el-step title="触发策略"/><el-step title="试运行与发布"/></el-steps>
            <div class="pipeline-grid"><article class="config-card"><h3>绑定测点</h3><div v-for="(binding,index) in metadata.pipeline.bindings" :key="index" class="binding-row"><el-input v-model="binding.deviceId" placeholder="设备 ID"/><el-input v-model="binding.pointId" placeholder="测点 ID"/><el-input v-model="binding.acquisitionChannelId" placeholder="通道 ID"/><el-select v-model="binding.modelType"><el-option label="齿轮" value="gear"/><el-option label="轴承" value="bearing"/></el-select><el-input v-model="binding.modelReleaseId" placeholder="模型发布 ID"/><el-input v-model="binding.modelVersion" placeholder="固定版本"/></div><el-button size="small" icon="el-icon-plus" @click="addBinding">增加绑定</el-button></article><article class="config-card"><h3>IoTDB 与质量策略</h3><el-form label-position="top" class="pipeline-form"><el-form-item label="库 / 表"><el-input v-model="metadata.pipeline.iotdb.database"/><el-input v-model="metadata.pipeline.iotdb.table"/></el-form-item><el-form-item label="最大帧龄（秒）"><el-input-number v-model="metadata.pipeline.iotdb.maxFrameAgeSeconds" :min="1" :max="86400"/></el-form-item><el-form-item label="调度 Cron"><el-input v-model="metadata.pipeline.trigger.schedule.cron" placeholder="0 0/15 * * * ?"/></el-form-item></el-form><div class="quality-chip"><i class="el-icon-success"/> 只读探测 · 不执行 DDL</div></article></div>
            <el-table :data="pipelineRuns" size="small" class="pipeline-runs"><el-table-column prop="trigger_type" label="触发方式" width="110"><template slot-scope="scope">{{ eventText(scope.row.trigger_type) }}</template></el-table-column><el-table-column prop="status" label="运行状态" width="110"><template slot-scope="scope">{{ pipelineStatusText(scope.row.status) }}</template></el-table-column><el-table-column prop="detail" label="执行详情"/><el-table-column prop="started_at" label="开始时间" width="180"/></el-table>
          </el-tab-pane>
          <el-tab-pane v-if="!isPipeline" label="对象建模" name="model">
            <div class="stage-toolbar">
              <div><h2>数据对象</h2><p>仅绑定已声明字段，运行时不会接受任意 SQL。</p></div>
              <div><el-button size="small" @click="inspect">导入数据库表</el-button><el-button size="small" @click="ddlPreview">安全 DDL 预览</el-button></div>
            </div>
            <el-form label-position="top" class="model-basics">
              <el-form-item label="业务表"><el-input v-model.trim="metadata.model.table" placeholder="例如 phm_measure_point" /></el-form-item>
              <el-form-item label="主键字段"><el-select v-model="metadata.model.primaryKey" filterable><el-option v-for="f in metadata.model.fields" :key="f.name" :label="f.label || f.name" :value="f.name" /></el-select></el-form-item>
              <el-form-item label="数据源"><el-input value="MySQL（写入）" disabled /></el-form-item>
            </el-form>
            <draggable v-model="metadata.model.fields" handle=".field-grip" class="field-board">
              <article v-for="(field, index) in metadata.model.fields" :key="field._key || field.name || index" class="field-row">
                <i class="el-icon-rank field-grip" />
                <el-input v-model.trim="field.name" placeholder="字段名" />
                <el-input v-model="field.label" placeholder="显示名称" />
                <el-select v-model="field.type"><el-option v-for="type in fieldTypes" :key="type.value" :label="type.label" :value="type.value" /></el-select>
                <div class="field-flags"><el-checkbox v-model="field.list">列表</el-checkbox><el-checkbox v-model="field.query">查询</el-checkbox><el-checkbox v-model="field.required">必填</el-checkbox><el-checkbox v-model="field.readOnly">只读</el-checkbox></div>
                <el-button type="text" class="danger-link" @click="removeField(index)">移除</el-button>
              </article>
            </draggable>
            <el-button class="add-field" icon="el-icon-plus" @click="addField">增加字段</el-button>
          </el-tab-pane>

          <el-tab-pane v-if="!isPipeline" label="页面编排" name="pages">
            <div class="stage-toolbar"><div><h2>结构化页面</h2><p>用业务区域组织页面，保持响应式和源码可维护性。</p></div></div>
            <div class="region-board">
              <label v-for="region in regions" :key="region.value" class="region-card" :class="{ enabled: pageRegions.includes(region.value) }">
                <el-checkbox :value="pageRegions.includes(region.value)" @change="toggleRegion(region.value)" />
                <i :class="region.icon" /><strong>{{ region.label }}</strong><span>{{ region.help }}</span>
              </label>
            </div>
          </el-tab-pane>

          <el-tab-pane v-if="!isPipeline" label="规则" name="rules">
            <div class="stage-toolbar"><div><h2>无脚本规则</h2><p>支持校验、显隐、只读和派生字段；表达式使用安全 JSON DSL。</p></div><el-button size="small" @click="addRule">增加规则</el-button></div>
            <article v-for="(rule, index) in metadata.rules" :key="index" class="config-card">
              <div class="config-head"><el-input v-model="rule.code" placeholder="规则编码" /><el-select v-model="rule.effect"><el-option label="校验" value="VALIDATE" /><el-option label="显隐" value="VISIBLE" /><el-option label="只读" value="READ_ONLY" /><el-option label="派生" value="COMPUTE" /></el-select><el-button type="text" @click="metadata.rules.splice(index, 1)">删除</el-button></div>
              <el-input v-model="rule.message" placeholder="触发后的提示" />
              <el-input :value="pretty(rule.condition)" type="textarea" :rows="4" @change="value => setJson(rule, 'condition', value)" />
            </article>
          </el-tab-pane>

          <el-tab-pane v-if="!isPipeline" label="动作" name="actions">
            <div class="stage-toolbar"><div><h2>受控动作</h2><p>动作只调用已注册处理器或白名单连接器，不执行自由脚本。</p></div><el-button size="small" @click="addAction">增加动作</el-button></div>
            <article v-for="(action, index) in metadata.actions" :key="index" class="config-card action-card">
              <el-input v-model="action.code" placeholder="动作编码" />
              <el-select v-model="action.event"><el-option v-for="event in actionEvents" :key="event" :label="eventText(event)" :value="event" /></el-select>
              <el-select v-model="action.handler"><el-option label="HTTP 白名单连接器" value="connector.http" /><el-option label="IoTDB 趋势只读" value="iotdb.telemetry.trend" /><el-option label="传感器诊断" value="sensor.diagnosis.run" /></el-select>
              <el-input v-if="action.handler === 'connector.http'" v-model="action.connectorCode" placeholder="连接器编码" />
              <el-input v-if="action.handler === 'connector.http'" v-model="action.path" placeholder="精确白名单路径" />
              <el-button type="text" @click="metadata.actions.splice(index, 1)">删除</el-button>
            </article>
          </el-tab-pane>

          <el-tab-pane v-if="!isPipeline" label="发布" name="release">
            <div class="stage-toolbar"><div><h2>发布与回滚</h2><p>发布版本不可变；回滚只切换活动元数据，不反向执行 DDL。</p></div><a v-if="current.id" :href="exportUrl(current.id)" class="el-button el-button--default el-button--small">导出版本快照</a></div>
            <div v-if="validation.errors && validation.errors.length" class="validation-list">
              <article v-for="error in validation.errors" :key="error.code + error.path"><b>{{ error.code }}</b><span>{{ error.message }}</span><code>{{ error.path }}</code></article>
            </div>
            <el-table :data="current.versions || []"><el-table-column prop="versionNo" label="版本" width="90" /><el-table-column prop="versionState" label="状态" width="120"><template slot-scope="scope">{{ modelStatusText(scope.row.versionState) }}</template></el-table-column><el-table-column prop="checksum" label="校验和" show-overflow-tooltip /><el-table-column prop="publishBy" label="发布人" width="120" /><el-table-column label="操作" width="100"><template slot-scope="scope"><el-button v-hasPermi="['tool:lowcode:rollback']" v-if="scope.row.versionState === 'PUBLISHED' && scope.row.id !== current.activeVersionId" type="text" @click="rollback(scope.row.id)">回滚</el-button></template></el-table-column></el-table>
            <el-collapse class="advanced-json"><el-collapse-item title="高级：查看并编辑统一元数据 JSON"><el-input v-model="metadataText" type="textarea" :rows="18" @change="applyMetadataText" /></el-collapse-item></el-collapse>
          </el-tab-pane>
        </el-tabs>
        <div v-else class="stage-empty"><div class="sensor-mark"><i /><i /><i /></div><h2>配置从对象开始</h2><p>选择现有项目，或从通用 CRUD / 测点诊断预置创建项目。</p></div>
      </section>
    </main>

    <el-dialog title="创建低代码项目" :visible.sync="createVisible" width="520px">
      <el-form label-position="top"><el-form-item label="项目名称"><el-input v-model="createForm.projectName" /></el-form-item><el-form-item label="应用编码"><el-input v-model="createForm.appCode" placeholder="例如 sensor-point-config" /></el-form-item><el-form-item label="业务预置"><el-radio-group v-model="createForm.preset"><el-radio label="generic-crud">通用 CRUD</el-radio><el-radio label="sensor-diagnosis">测点诊断配置</el-radio><el-radio label="sensor-diagnosis-pipeline">工业诊断管道</el-radio></el-radio-group></el-form-item></el-form>
      <span slot="footer"><el-button @click="createVisible = false">取消</el-button><el-button v-hasPermi="['tool:lowcode:design']" type="primary" @click="create">创建草稿</el-button></span>
    </el-dialog>
    <el-dialog title="安全 DDL 预览" :visible.sync="ddlVisible" width="760px"><pre class="ddl-output">{{ ddlStatements.join('\n\n') || '当前结构不需要新增 DDL。' }}</pre><span slot="footer"><el-button @click="ddlVisible = false">关闭</el-button></span></el-dialog>
  </div>
</template>

<script>
import draggable from 'vuedraggable'
import { listProjects, getProject, createProject, saveDraft, validateProject, diffProject, publishProject, rollbackProject, inspectDatabase, previewDdl, exportUrl, testPipeline, activatePipeline, deactivatePipeline, listPipelineRuns } from '@/api/tool/lowcode'
import { modelStatusText } from '@/utils/industrialLabels'

const emptyMetadata = () => ({ schemaVersion: 2, appType: 'DATA_APP', preset: 'generic-crud', dataSource: 'mysql', model: { table: '', primaryKey: 'id', fields: [], relations: [] }, pages: [{ code: 'index', type: 'crud', regions: ['query', 'list', 'form', 'detail'] }], rules: [], actions: [], pipeline: { bindings: [], iotdb: { database: 'monitoring', table: 'vibration_frame', maxFrameAgeSeconds: 300 }, trigger: { schedule: { cron: '0 0/15 * * * ?' } } } })

export default {
  name: 'LowCodeWorkbench', components: { draggable },
  data() { return { projects: [], current: {}, metadata: emptyMetadata(), pipelineRuns: [], metadataText: '', activeWorkspace: 'model', validation: {}, createVisible: false, ddlVisible: false, ddlStatements: [], createForm: { projectName: '', appCode: '', preset: 'generic-crud' }, fieldTypes: [{ label: '文本', value: 'text' }, { label: '多行文本', value: 'textarea' }, { label: '数字', value: 'number' }, { label: '开关', value: 'switch' }, { label: '日期时间', value: 'datetime' }, { label: '字典', value: 'dict' }, { label: '实体关联', value: 'entity' }, { label: '远程选项', value: 'remote' }, { label: '文件', value: 'file' }, { label: '图片', value: 'image' }, { label: 'JSON', value: 'json' }, { label: '派生字段', value: 'computed' }], regions: [{ label: '查询区', value: 'query', icon: 'el-icon-search', help: '组合过滤条件' }, { label: '数据列表', value: 'list', icon: 'el-icon-s-grid', help: '分页、排序和批量操作' }, { label: '编辑表单', value: 'form', icon: 'el-icon-edit-outline', help: '新增与修改记录' }, { label: '详情', value: 'detail', icon: 'el-icon-document', help: '只读业务详情' }, { label: '统计卡', value: 'stats', icon: 'el-icon-data-analysis', help: '关键指标摘要' }, { label: '趋势图', value: 'trend', icon: 'el-icon-data-line', help: '绑定 IoTDB 只读连接器' }, { label: '诊断结果', value: 'diagnosis', icon: 'el-icon-cpu', help: '任务状态、模型与证据' }], actionEvents: ['FORM_CHANGE', 'BEFORE_SAVE', 'AFTER_SAVE', 'MANUAL'] } },
  computed: {
    isPipeline() { return this.metadata.appType === 'SENSOR_DIAGNOSIS_PIPELINE' },
    pipelineStage() { return this.metadata.pipeline ? Math.min(this.metadata.pipeline.bindings.length ? 3 : 1, 4) : 0 },
    pageRegions() { return this.metadata.pages && this.metadata.pages[0] ? this.metadata.pages[0].regions : [] },
    releaseSteps() { return [{ key: 'draft', label: '草稿', caption: this.current.draft ? `v${this.current.draft.versionNo}` : '未选择', state: this.current.id ? 'done' : '' }, { key: 'validation', label: '校验', caption: this.validation.valid ? '结构与数据库一致' : '等待完整校验', state: this.validation.valid ? 'done' : this.validation.errors ? 'error' : '' }, { key: 'publish', label: '发布', caption: this.current.active ? `线上 v${this.current.active.versionNo}` : '尚未发布', state: this.current.active ? 'done' : '' }] }
  },
  created() { this.refresh() },
  methods: {
    exportUrl,
    async refresh() { const res = await listProjects(); this.projects = res.data || [] },
    async selectProject(id) { const res = await getProject(id); this.current = res.data; this.metadata = Object.assign(emptyMetadata(), JSON.parse(this.current.draft.metadataJson)); if (this.isPipeline) { this.activeWorkspace = 'pipeline'; const run = await listPipelineRuns(id); this.pipelineRuns = run.data || [] } else this.activeWorkspace = 'model'; this.syncText(); this.validation = this.current.draft.validationJson ? JSON.parse(this.current.draft.validationJson) : {} },
    openCreate() { this.createForm = { projectName: '', appCode: '', preset: 'generic-crud' }; this.createVisible = true },
    async create() {
      if (this._creating) return
      if (!this.createForm.projectName.trim() || !/^[A-Za-z][A-Za-z0-9_-]{1,63}$/.test(this.createForm.appCode)) { this.$modal.msgError('请填写合法的项目名称和应用编码'); return }
      this._creating = true
      try { const res = await createProject(this.createForm); this.createVisible = false; await this.refresh(); await this.selectProject(res.data.id) } finally { this._creating = false }
    },
    async save() { if (this._saving) return; this._saving = true; try { await saveDraft(this.current.id, this.metadata); this.$modal.msgSuccess('草稿已保存'); await this.selectProject(this.current.id) } finally { this._saving = false } },
    async validateCurrent() { if (this._validating) return; this._validating = true; try { await this.save(); const res = await validateProject(this.current.id); this.validation = res.data; this.activeWorkspace = 'release'; if (this.validation.valid) this.$modal.msgSuccess('完整校验通过'); else this.$modal.msgError(`发现 ${this.validation.errors.length} 个问题`) } finally { this._validating = false } },
    async showDiff() { const res = await diffProject(this.current.id); this.$alert(`<pre style="max-height:420px;overflow:auto">${this.escape(JSON.stringify(res.data, null, 2))}</pre>`, '版本差异', { dangerouslyUseHTMLString: true }) },
    async publishCurrent() { if (this._publishing) return; this._publishing = true; try { await publishProject(this.current.id); this.$modal.msgSuccess('已发布；新的可编辑草稿已创建'); await this.refresh(); await this.selectProject(this.current.id) } finally { this._publishing = false } },
    addBinding() { this.metadata.pipeline.bindings.push({ deviceId: '', pointId: '', acquisitionChannelId: '', modelType: 'bearing', modelReleaseId: '', modelVersion: '', windowSize: 4096 }) },
    async runPipelineTest() { await this.save(); await testPipeline(this.current.id, {}); this.$modal.msgSuccess('试运行成功，已满足启用门禁'); await this.selectProject(this.current.id) },
    async activatePipelineNow() { await activatePipeline(this.current.id); this.$modal.msgSuccess('管道已启用') },
    async deactivatePipelineNow() { await deactivatePipeline(this.current.id); this.$modal.msgSuccess('管道已停用') },
    async rollback(versionId) { await this.$modal.confirm('回滚只切换活动元数据版本，不会反向修改表结构。确认继续？'); await rollbackProject(this.current.id, versionId); this.$modal.msgSuccess('已回滚'); await this.selectProject(this.current.id) },
    addField() { this.metadata.model.fields.push({ _key: Date.now(), name: '', label: '', type: 'text', list: true, query: false, required: false, readOnly: false, insert: true, edit: true }) },
    removeField(index) { this.metadata.model.fields.splice(index, 1) },
    toggleRegion(value) { const regions = this.pageRegions; const index = regions.indexOf(value); if (index >= 0) regions.splice(index, 1); else regions.push(value) },
    addRule() { this.metadata.rules.push({ code: `rule${this.metadata.rules.length + 1}`, effect: 'VALIDATE', condition: { op: 'eq', args: [{ field: '' }, { value: '' }] }, message: '数据不符合规则' }) },
    addAction() { this.metadata.actions.push({ code: `action${this.metadata.actions.length + 1}`, event: 'MANUAL', handler: 'connector.http' }) },
    pretty(value) { return JSON.stringify(value || {}, null, 2) },
    setJson(target, key, value) { try { this.$set(target, key, JSON.parse(value)) } catch (e) { this.$modal.msgError('规则 JSON 格式错误') } },
    syncText() { this.metadataText = JSON.stringify(this.metadata, null, 2) },
    applyMetadataText(value) { try { this.metadata = JSON.parse(value); this.$modal.msgSuccess('元数据 JSON 已应用到草稿') } catch (e) { this.$modal.msgError('元数据 JSON 格式错误') } },
    async inspect() { const res = await inspectDatabase(this.current.id); const options = (res.data || []).map(item => ({ value: item.table, label: `${item.table} · ${item.columns.length} 字段`, columns: item.columns })); this.$prompt('输入要导入的数据库表名', '导入数据库表', { inputType: 'text' }).then(({ value }) => { const selected = options.find(item => item.value === value); if (!selected) return this.$modal.msgError('没有找到该表'); this.metadata.model.table = selected.value; this.metadata.model.fields = selected.columns.map(col => ({ name: col.name, label: col.name, type: this.mapDbType(col.databaseType), list: true, query: false, required: !col.nullable, readOnly: col.name === 'id', insert: col.name !== 'id', edit: col.name !== 'id' })); this.metadata.model.primaryKey = this.metadata.model.fields.some(f => f.name === 'id') ? 'id' : this.metadata.model.fields[0].name }) },
    async ddlPreview() { const res = await previewDdl(this.current.id, this.metadata); this.ddlStatements = res.data.statements || []; this.ddlVisible = true },
    mapDbType(type) { const text = String(type).toLowerCase(); if (text.includes('int') || text.includes('decimal') || text.includes('double')) return 'number'; if (text.includes('date') || text.includes('time')) return 'datetime'; if (text.includes('text') || text.includes('json')) return 'textarea'; return 'text' },
    modelStatusText(status) { return modelStatusText(status) },
    eventText(value) { return { FORM_CHANGE: '表单变更', BEFORE_SAVE: '保存前', AFTER_SAVE: '保存后', MANUAL: '手动触发' }[value] || String(value || '未标注') },
    pipelineStatusText(value) { return { PENDING: '排队中', RUNNING: '执行中', SUCCEEDED: '已完成', FAILED: '执行失败', SUCCESS: '已完成' }[String(value || '').toUpperCase()] || String(value || '未标注') },
    escape(text) { return text.replace(/[&<>]/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[char])) }
  }
}
</script>

<style lang="scss" scoped>
$graphite: #18212b; $steel: #334155; $work: #f5f7fa; $signal: #2f80ed; $amber: #d99024; $success: #2e8b57;
.pipeline-bus{display:flex;align-items:center;gap:12px;padding:14px 18px;margin-bottom:20px;background:#18212b;color:#dbeafe;border-radius:6px;font-family:Bahnschrift,Consolas,monospace;font-size:12px;letter-spacing:.04em}.pipeline-bus i{color:#83b6f4}.bus-dot{width:9px;height:9px;border-radius:50%;background:#52c788;box-shadow:0 0 0 5px rgba(82,199,136,.15)}.pipeline-steps{margin-bottom:20px}.pipeline-grid{display:grid;grid-template-columns:1.3fr 1fr;gap:14px}.binding-row{display:grid;grid-template-columns:repeat(3,1fr);gap:7px;margin-bottom:8px}.pipeline-form .el-form-item{margin-bottom:10px}.pipeline-form .el-input{margin-right:6px}.quality-chip{display:inline-flex;gap:6px;align-items:center;color:$success;font-size:12px}.pipeline-runs{margin-top:16px}
.lc-shell { min-height: calc(100vh - 84px); margin: -20px; background: $work; color: $graphite; font-family: "Microsoft YaHei", sans-serif; }
.lc-command-bar { display:flex; justify-content:space-between; align-items:flex-end; padding:28px 34px 24px; background:$graphite; color:#fff; border-bottom:4px solid $signal; h1{margin:4px 0 5px;font-size:30px;letter-spacing:.02em} p{margin:0;color:#bac5d1} }
.lc-eyebrow,.project-code,.release-index,code { font-family:Bahnschrift,Consolas,monospace; letter-spacing:.08em; }.lc-eyebrow{font-size:11px;color:#83b6f4}.lc-header-actions{display:flex;gap:8px}
.release-rail{position:sticky;top:0;z-index:8;display:flex;align-items:center;gap:28px;padding:13px 34px;background:#fff;border-bottom:1px solid #dbe1e8;box-shadow:0 5px 18px rgba(24,33,43,.06)}
.release-node{position:relative;display:flex;align-items:center;gap:10px;min-width:150px;&:not(:last-of-type):after{content:"";position:absolute;right:-20px;width:12px;height:1px;background:#c5ced8}.release-index{display:grid;place-items:center;width:28px;height:28px;border-radius:50%;background:#e8edf3;color:#667585}.done .release-index{background:$success;color:#fff}.error .release-index{background:#c84b4b;color:#fff} b,small{display:block}small{margin-top:2px;color:#7a8795;font-size:11px}}.release-actions{margin-left:auto;display:flex;gap:7px}
.lc-grid{display:grid;grid-template-columns:238px minmax(0,1fr);min-height:calc(100vh - 222px)}.project-rail{padding:22px 14px;background:$steel;color:#fff}.rail-title{display:flex;justify-content:space-between;padding:0 8px 12px;font-weight:700;em{font-style:normal;color:#9fb1c5}}.project-card{width:100%;padding:14px;margin-bottom:8px;text-align:left;color:#d8e0e8;background:transparent;border:1px solid transparent;border-radius:6px;cursor:pointer;transition:.16s}.project-card strong,.project-card small{display:block}.project-code{font-size:10px;color:#8da3b8}.project-card strong{margin:5px 0;font-size:14px}.project-card small{color:#9fb1c5}.project-card:hover,.project-card.active{background:#263647;border-color:#4b6680}.project-card.active{box-shadow:inset 3px 0 $signal}.rail-empty{padding:24px 10px;color:#aebbc8;line-height:1.7}
.designer-stage{padding:24px 28px;overflow:hidden}.workspace-tabs::v-deep .el-tabs__header{margin-bottom:20px}.workspace-tabs::v-deep .el-tabs__item{font-weight:700}.stage-toolbar{display:flex;justify-content:space-between;align-items:center;margin-bottom:18px;h2{margin:0 0 4px;font-size:21px}p{margin:0;color:#718092}}.model-basics{display:grid;grid-template-columns:2fr 1fr 1fr;gap:14px}.field-board{display:flex;flex-direction:column;gap:8px}.field-row{display:grid;grid-template-columns:24px 1.1fr 1.2fr 150px 2fr 48px;gap:10px;align-items:center;padding:12px;background:#fff;border:1px solid #dce3ea;border-radius:6px}.field-grip{color:#8b9aaa;cursor:move}.field-flags{display:flex;flex-wrap:wrap;gap:0 10px}.danger-link{color:#c45656}.add-field{width:100%;margin-top:10px;border-style:dashed}
.region-board{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:13px}.region-card{position:relative;display:grid;grid-template-columns:26px 42px 1fr;grid-template-rows:auto auto;align-items:center;padding:18px;background:#fff;border:1px solid #dce3ea;border-radius:7px;cursor:pointer;&.enabled{border-color:$signal;box-shadow:inset 0 0 0 1px $signal}.el-checkbox{grid-row:1/3}.region-card>i{grid-row:1/3;font-size:25px;color:$signal}.region-card span{font-size:12px;color:#748292}}
.config-card{padding:16px;margin-bottom:12px;background:#fff;border-left:3px solid $amber;box-shadow:0 5px 15px rgba(24,33,43,.05)}.config-head,.action-card{display:grid;grid-template-columns:1fr 180px 60px;gap:10px;margin-bottom:10px}.action-card{grid-template-columns:1fr 170px 220px 1fr 1fr 50px;border-left-color:$signal}.validation-list article{display:grid;grid-template-columns:170px 1fr 220px;gap:10px;padding:10px 12px;margin-bottom:7px;background:#fff3f2;border-left:3px solid #c84b4b}.advanced-json{margin-top:18px}.ddl-output{padding:18px;background:#101820;color:#d8e7f5;white-space:pre-wrap;max-height:500px;overflow:auto}.stage-empty{display:grid;place-items:center;align-content:center;min-height:480px;color:#657586;text-align:center}.sensor-mark{display:flex;gap:8px;margin-bottom:20px;i{display:block;width:10px;height:46px;background:$signal;border-radius:10px;animation:pulse 1.8s infinite ease-in-out}i:nth-child(2){height:70px;background:$amber;animation-delay:.18s}i:nth-child(3){height:34px;background:$success;animation-delay:.36s}}@keyframes pulse{50%{transform:scaleY(.72);opacity:.72}}
@media(max-width:1100px){.lc-grid{grid-template-columns:200px minmax(0,1fr)}.field-row{grid-template-columns:24px 1fr 1fr 130px}.field-flags{grid-column:2/5}.region-board{grid-template-columns:repeat(2,1fr)}.release-node{min-width:auto}.release-node small{display:none}}
@media(prefers-reduced-motion:reduce){.sensor-mark i{animation:none}}

/* RuoYi shell alignment: keep the industrial signal colors as accents, while
   following the project's app-container/page-head/table density. */
.lc-shell{min-height:calc(100vh - 84px);margin:0;padding:20px;background:#f5f7fa;color:#303133;font-family:"Microsoft YaHei",Arial,sans-serif}
.lc-command-bar{display:flex;justify-content:space-between;align-items:center;padding:0 0 18px;background:transparent;color:#303133;border-bottom:1px solid #ebeef5}
.lc-command-bar h1{margin:0 0 7px;font-size:22px;font-weight:600;letter-spacing:0}.lc-command-bar p{margin:0;color:#909399;font-size:13px}.lc-eyebrow{display:block;margin-bottom:5px;color:#409eff;font-size:11px;font-weight:600;letter-spacing:.08em}.lc-header-actions{gap:8px}
.release-rail{position:static;display:flex;align-items:center;gap:24px;margin:16px 0;padding:12px 16px;background:#fff;border:1px solid #ebeef5;border-radius:4px;box-shadow:none}.release-node{min-width:140px}.release-node:not(:last-of-type):after{right:-16px;background:#dcdfe6}.release-index{width:24px;height:24px;font-size:12px}.release-node b{font-size:13px;color:#606266}.release-node small{color:#909399}.release-actions{margin-left:auto}
.lc-grid{grid-template-columns:220px minmax(0,1fr);min-height:calc(100vh - 250px);gap:16px}.project-rail{padding:16px 12px;background:#fff;color:#606266;border:1px solid #ebeef5;border-radius:4px}.rail-title{padding:0 8px 12px;border-bottom:1px solid #f0f2f5;color:#303133}.rail-title em{color:#909399}.project-card{padding:12px;margin:8px 0 0;color:#606266;border:1px solid transparent;border-radius:3px}.project-card strong{font-weight:500;color:#303133}.project-card small{color:#909399}.project-card:hover,.project-card.active{background:#ecf5ff;border-color:#b3d8ff}.project-card.active{box-shadow:inset 3px 0 #409eff}.project-code{color:#409eff}.rail-empty{padding:20px 8px;color:#909399}
.designer-stage{padding:0;overflow:hidden}.workspace-tabs{background:#fff;border:1px solid #ebeef5;border-radius:4px;padding:0 18px 18px}.workspace-tabs::v-deep .el-tabs__header{margin-bottom:18px}.workspace-tabs::v-deep .el-tabs__item{font-weight:400}.workspace-tabs::v-deep .el-tabs__item.is-active{font-weight:600}.stage-toolbar{min-height:42px;margin-bottom:16px}.stage-toolbar h2{font-size:18px;font-weight:600;color:#303133}.stage-toolbar p{font-size:12px;color:#909399}.config-card,.field-row,.region-card{box-shadow:none;border:1px solid #ebeef5;border-radius:3px}.config-card{border-left:3px solid #e6a23c}.action-card{border-left-color:#409eff}.pipeline-bus{border-radius:3px;background:#f4f8ff;color:#409eff;border:1px solid #d9ecff}.pipeline-bus i{color:#9cc8f5}.quality-chip{padding:6px 10px;background:#f0f9eb;border-radius:3px}.pipeline-runs{border:1px solid #ebeef5}
.stage-empty{min-height:420px}.sensor-mark i{border-radius:2px}
@media(max-width:1100px){.lc-shell{padding:16px}.lc-grid{grid-template-columns:190px minmax(0,1fr)}.release-rail{gap:12px}.release-actions{gap:4px}}

/* Low-code control-room skin: a dense, signal-led workspace aligned with PHM. */
.lc-shell {
  position: relative;
  min-height: calc(100vh - 84px);
  margin: -20px;
  padding: 24px;
  overflow: hidden;
  color: var(--color-text);
  font-family: var(--font-ui);
  background:
    linear-gradient(rgba(34, 211, 238, .025) 1px, transparent 1px),
    linear-gradient(90deg, rgba(34, 211, 238, .025) 1px, transparent 1px),
    var(--color-canvas);
  background-size: 48px 48px;
}
.lc-shell::after {
  position: absolute;
  top: 0;
  right: 12%;
  width: 360px;
  height: 160px;
  pointer-events: none;
  content: "";
  background: radial-gradient(ellipse, rgba(34, 211, 238, .08), transparent 70%);
}
.lc-command-bar {
  position: relative;
  z-index: 1;
  align-items: center;
  min-height: 98px;
  padding: 22px 24px 20px 28px;
  overflow: hidden;
  color: var(--color-heading);
  background: rgba(17, 28, 48, .9);
  border: 1px solid var(--color-border);
  border-left: 3px solid var(--color-accent);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-panel);
}
.lc-command-bar::before {
  position: absolute;
  top: 0;
  right: 0;
  width: 42%;
  height: 1px;
  content: "";
  background: linear-gradient(90deg, transparent, var(--color-accent));
  opacity: .8;
}
.lc-command-bar h1 { margin: 5px 0 7px; color: var(--color-heading); font-size: 26px; font-weight: 700; letter-spacing: .02em; }
.lc-command-bar p { margin: 0; color: var(--color-muted); font-size: 13px; }
.lc-eyebrow { color: var(--color-accent); font-family: var(--font-data); font-size: 10px; letter-spacing: .12em; }
.lc-header-actions { display: flex; gap: 8px; }
.lc-header-actions .el-button { min-width: 112px; }
.release-rail {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 24px;
  margin: 14px 0;
  padding: 13px 16px;
  color: var(--color-text);
  background: rgba(15, 23, 42, .88);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-panel);
}
.release-node { min-width: 145px; }
.release-node:not(:last-of-type)::after { background: var(--color-border-strong); }
.release-index { width: 26px; height: 26px; color: var(--color-muted); background: var(--color-surface-raised); border: 1px solid var(--color-border-strong); font-family: var(--font-data); }
.release-node.done .release-index { color: var(--color-on-accent); background: var(--color-accent); border-color: var(--color-accent); box-shadow: 0 0 16px rgba(34, 211, 238, .22); }
.release-node.error .release-index { color: #fff; background: var(--color-danger); border-color: var(--color-danger); }
.release-node b { color: var(--color-heading); font-size: 13px; }
.release-node small { color: var(--color-muted); }
.release-actions { gap: 7px; }
.lc-grid { position: relative; z-index: 1; display: grid; grid-template-columns: 250px minmax(0, 1fr); gap: 14px; min-height: calc(100vh - 250px); }
.project-rail {
  align-self: start;
  min-height: 520px;
  padding: 18px 12px;
  color: var(--color-text);
  background: rgba(15, 23, 42, .9);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-panel);
}
.rail-title { align-items: center; padding: 0 9px 14px; color: var(--color-heading); border-bottom-color: var(--color-border); }
.rail-title span::before { display: inline-block; width: 6px; height: 6px; margin-right: 8px; vertical-align: 2px; content: ""; background: var(--color-accent); border-radius: 50%; box-shadow: 0 0 10px var(--color-accent); }
.rail-title em { padding: 2px 7px; color: var(--color-accent); background: var(--color-accent-soft); border-radius: 999px; font-family: var(--font-data); font-size: 11px; }
.project-card { margin: 8px 0 0; padding: 13px 12px; color: var(--color-text); background: transparent; border: 1px solid transparent; border-radius: var(--radius-sm); }
.project-card strong { color: var(--color-heading); font-weight: 600; }
.project-card small { color: var(--color-muted); }
.project-card:hover { background: var(--color-accent-soft); border-color: var(--color-border-strong); }
.project-card.active { background: linear-gradient(90deg, rgba(34, 211, 238, .14), rgba(34, 211, 238, .035)); border-color: var(--color-accent-strong); box-shadow: inset 3px 0 var(--color-accent), 0 8px 20px rgba(2, 8, 20, .18); }
.project-code { color: var(--color-accent); font-family: var(--font-data); font-size: 10px; }
.rail-empty { color: var(--color-muted); }
.designer-stage { min-width: 0; padding: 0; }
.workspace-tabs { padding: 0 20px 20px; overflow: hidden; background: rgba(17, 28, 48, .9); border: 1px solid var(--color-border); border-radius: var(--radius-md); box-shadow: var(--shadow-panel); }
.workspace-tabs::v-deep .el-tabs__header { margin: 0 -20px 22px; padding: 0 20px; background: rgba(15, 23, 42, .72); border-bottom: 1px solid var(--color-border); }
.workspace-tabs::v-deep .el-tabs__item { height: 48px; color: var(--color-muted); font-size: 13px; }
.workspace-tabs::v-deep .el-tabs__item.is-active, .workspace-tabs::v-deep .el-tabs__item:hover { color: var(--color-accent); }
.workspace-tabs::v-deep .el-tabs__active-bar { height: 2px; background: var(--color-accent); box-shadow: 0 0 10px rgba(34, 211, 238, .5); }
.stage-toolbar h2 { color: var(--color-heading); font-size: 19px; }
.stage-toolbar p { color: var(--color-muted); }
.pipeline-bus { padding: 13px 16px; color: var(--color-accent); background: rgba(34, 211, 238, .065); border: 1px solid var(--color-accent-strong); border-radius: var(--radius-sm); font-family: var(--font-data); }
.pipeline-bus i { color: var(--color-muted); }
.pipeline-grid { gap: 14px; }
.config-card, .field-row, .region-card { color: var(--color-text); background: var(--color-surface-soft); border-color: var(--color-border); border-radius: var(--radius-sm); box-shadow: none; }
.config-card { border-left: 3px solid var(--color-warning); }
.action-card { border-left-color: var(--color-accent); }
.field-row { padding: 11px 12px; }
.field-grip { color: var(--color-muted); }
.region-card { background: rgba(15, 23, 42, .76); }
.region-card.enabled { border-color: var(--color-accent-strong); background: var(--color-accent-soft); box-shadow: inset 3px 0 var(--color-accent); }
.region-card > i { color: var(--color-accent); }
.region-card span { color: var(--color-muted); }
.validation-list article { color: var(--color-text); background: rgba(239, 68, 68, .08); border-left-color: var(--color-danger); }
.ddl-output { color: #8fffe9; background: var(--color-canvas-deep); border: 1px solid var(--color-border); border-radius: var(--radius-sm); font-family: var(--font-data); }
.stage-empty { min-height: 460px; color: var(--color-muted); }
.stage-empty h2 { color: var(--color-heading); }
.sensor-mark i { border-radius: 3px; box-shadow: 0 0 18px currentColor; }
.sensor-mark i:first-child { color: var(--color-accent); background: var(--color-accent); }
.sensor-mark i:nth-child(2) { color: var(--color-warning); background: var(--color-warning); }
.sensor-mark i:nth-child(3) { color: var(--color-success); background: var(--color-success); }
.pipeline-runs { border-color: var(--color-border); }
.pipeline-runs::v-deep th, .pipeline-runs::v-deep td { background: transparent; border-bottom-color: var(--color-border); }
.advanced-json { border-color: var(--color-border); }
.model-basics { gap: 14px; }
@media (max-width: 1100px) { .lc-shell { padding: 16px; } .lc-grid { grid-template-columns: 190px minmax(0, 1fr); } .release-rail { gap: 12px; } .release-actions { gap: 4px; } }
@media (max-width: 760px) { .lc-command-bar, .release-rail { align-items: flex-start; flex-direction: column; } .lc-command-bar { gap: 16px; } .lc-header-actions, .release-actions { width: 100%; flex-wrap: wrap; } .lc-grid { grid-template-columns: 1fr; } .project-rail { min-height: auto; } .release-node { min-width: 0; } .release-node:not(:last-of-type)::after { display: none; } .model-basics, .pipeline-grid { grid-template-columns: 1fr; } .field-row { grid-template-columns: 24px 1fr 1fr; } .field-row .field-flags { grid-column: 2 / 4; } .action-card, .config-head { grid-template-columns: 1fr; } }
</style>
