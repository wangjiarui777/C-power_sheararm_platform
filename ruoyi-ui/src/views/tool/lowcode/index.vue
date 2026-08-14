<template>
  <div class="lc-shell">
    <header class="lc-command-bar">
      <div>
        <span class="lc-eyebrow">INDUSTRIAL CONFIGURATION / V2</span>
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
          <el-tab-pane label="对象建模" name="model">
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

          <el-tab-pane label="页面编排" name="pages">
            <div class="stage-toolbar"><div><h2>结构化页面</h2><p>用业务区域组织页面，保持响应式和源码可维护性。</p></div></div>
            <div class="region-board">
              <label v-for="region in regions" :key="region.value" class="region-card" :class="{ enabled: pageRegions.includes(region.value) }">
                <el-checkbox :value="pageRegions.includes(region.value)" @change="toggleRegion(region.value)" />
                <i :class="region.icon" /><strong>{{ region.label }}</strong><span>{{ region.help }}</span>
              </label>
            </div>
          </el-tab-pane>

          <el-tab-pane label="规则" name="rules">
            <div class="stage-toolbar"><div><h2>无脚本规则</h2><p>支持校验、显隐、只读和派生字段；表达式使用安全 JSON DSL。</p></div><el-button size="small" @click="addRule">增加规则</el-button></div>
            <article v-for="(rule, index) in metadata.rules" :key="index" class="config-card">
              <div class="config-head"><el-input v-model="rule.code" placeholder="规则编码" /><el-select v-model="rule.effect"><el-option label="校验" value="VALIDATE" /><el-option label="显隐" value="VISIBLE" /><el-option label="只读" value="READ_ONLY" /><el-option label="派生" value="COMPUTE" /></el-select><el-button type="text" @click="metadata.rules.splice(index, 1)">删除</el-button></div>
              <el-input v-model="rule.message" placeholder="触发后的提示" />
              <el-input :value="pretty(rule.condition)" type="textarea" :rows="4" @change="value => setJson(rule, 'condition', value)" />
            </article>
          </el-tab-pane>

          <el-tab-pane label="动作" name="actions">
            <div class="stage-toolbar"><div><h2>受控动作</h2><p>动作只调用已注册处理器或白名单连接器，不执行自由脚本。</p></div><el-button size="small" @click="addAction">增加动作</el-button></div>
            <article v-for="(action, index) in metadata.actions" :key="index" class="config-card action-card">
              <el-input v-model="action.code" placeholder="动作编码" />
              <el-select v-model="action.event"><el-option v-for="event in actionEvents" :key="event" :value="event" /></el-select>
              <el-select v-model="action.handler"><el-option label="HTTP 白名单连接器" value="connector.http" /><el-option label="IoTDB 趋势只读" value="iotdb.telemetry.trend" /><el-option label="传感器诊断" value="sensor.diagnosis.run" /></el-select>
              <el-input v-if="action.handler === 'connector.http'" v-model="action.connectorCode" placeholder="连接器编码" />
              <el-input v-if="action.handler === 'connector.http'" v-model="action.path" placeholder="精确白名单路径" />
              <el-button type="text" @click="metadata.actions.splice(index, 1)">删除</el-button>
            </article>
          </el-tab-pane>

          <el-tab-pane label="权限" name="permissions">
            <div class="stage-toolbar"><div><h2>数据与操作权限</h2><p>权限在服务端执行，页面隐藏不是安全边界。</p></div></div>
            <el-form label-position="top" class="permission-form">
              <el-form-item label="数据范围"><el-radio-group v-model="metadata.permissions.dataScope"><el-radio-button label="NONE">无附加范围</el-radio-button><el-radio-button label="DEPT">本部门</el-radio-button><el-radio-button label="DEPT_AND_CHILD">部门及下级</el-radio-button><el-radio-button label="SELF">本人</el-radio-button></el-radio-group></el-form-item>
              <el-form-item v-if="metadata.permissions.dataScope === 'DEPT' || metadata.permissions.dataScope === 'DEPT_AND_CHILD'" label="部门字段"><el-select v-model="metadata.permissions.deptField"><el-option v-for="f in metadata.model.fields" :key="f.name" :value="f.name" /></el-select></el-form-item>
              <el-form-item v-if="metadata.permissions.dataScope === 'SELF'" label="用户字段"><el-select v-model="metadata.permissions.userField"><el-option v-for="f in metadata.model.fields" :key="f.name" :value="f.name" /></el-select></el-form-item>
            </el-form>
          </el-tab-pane>

          <el-tab-pane label="发布" name="release">
            <div class="stage-toolbar"><div><h2>发布与回滚</h2><p>发布版本不可变；回滚只切换活动元数据，不反向执行 DDL。</p></div><a v-if="current.id" :href="exportUrl(current.id)" class="el-button el-button--default el-button--small">导出版本快照</a></div>
            <div v-if="validation.errors && validation.errors.length" class="validation-list">
              <article v-for="error in validation.errors" :key="error.code + error.path"><b>{{ error.code }}</b><span>{{ error.message }}</span><code>{{ error.path }}</code></article>
            </div>
            <el-table :data="current.versions || []"><el-table-column prop="versionNo" label="版本" width="90" /><el-table-column prop="versionState" label="状态" width="120" /><el-table-column prop="checksum" label="校验和" show-overflow-tooltip /><el-table-column prop="publishBy" label="发布人" width="120" /><el-table-column label="操作" width="100"><template slot-scope="scope"><el-button v-hasPermi="['tool:lowcode:rollback']" v-if="scope.row.versionState === 'PUBLISHED' && scope.row.id !== current.activeVersionId" type="text" @click="rollback(scope.row.id)">回滚</el-button></template></el-table-column></el-table>
            <el-collapse class="advanced-json"><el-collapse-item title="高级：查看并编辑统一元数据 JSON"><el-input v-model="metadataText" type="textarea" :rows="18" @change="applyMetadataText" /></el-collapse-item></el-collapse>
          </el-tab-pane>
        </el-tabs>
        <div v-else class="stage-empty"><div class="sensor-mark"><i /><i /><i /></div><h2>配置从对象开始</h2><p>选择现有项目，或从通用 CRUD / 测点诊断预置创建项目。</p></div>
      </section>
    </main>

    <el-dialog title="创建低代码项目" :visible.sync="createVisible" width="520px">
      <el-form label-position="top"><el-form-item label="项目名称"><el-input v-model="createForm.projectName" /></el-form-item><el-form-item label="应用编码"><el-input v-model="createForm.appCode" placeholder="例如 sensor-point-config" /></el-form-item><el-form-item label="业务预置"><el-radio-group v-model="createForm.preset"><el-radio label="generic-crud">通用 CRUD</el-radio><el-radio label="sensor-diagnosis">测点诊断配置</el-radio></el-radio-group></el-form-item></el-form>
      <span slot="footer"><el-button @click="createVisible = false">取消</el-button><el-button v-hasPermi="['tool:lowcode:design']" type="primary" @click="create">创建草稿</el-button></span>
    </el-dialog>
    <el-dialog title="安全 DDL 预览" :visible.sync="ddlVisible" width="760px"><pre class="ddl-output">{{ ddlStatements.join('\n\n') || '当前结构不需要新增 DDL。' }}</pre><span slot="footer"><el-button @click="ddlVisible = false">关闭</el-button></span></el-dialog>
  </div>
</template>

<script>
import draggable from 'vuedraggable'
import { listProjects, getProject, createProject, saveDraft, validateProject, diffProject, publishProject, rollbackProject, inspectDatabase, previewDdl, exportUrl } from '@/api/tool/lowcode'

const emptyMetadata = () => ({ schemaVersion: 2, preset: 'generic-crud', dataSource: 'mysql', model: { table: '', primaryKey: 'id', fields: [], relations: [] }, pages: [{ code: 'index', type: 'crud', regions: ['query', 'list', 'form', 'detail'] }], rules: [], actions: [], permissions: { dataScope: 'NONE' } })

export default {
  name: 'LowCodeWorkbench', components: { draggable },
  data() { return { projects: [], current: {}, metadata: emptyMetadata(), metadataText: '', activeWorkspace: 'model', validation: {}, createVisible: false, ddlVisible: false, ddlStatements: [], createForm: { projectName: '', appCode: '', preset: 'generic-crud' }, fieldTypes: [{ label: '文本', value: 'text' }, { label: '多行文本', value: 'textarea' }, { label: '数字', value: 'number' }, { label: '开关', value: 'switch' }, { label: '日期时间', value: 'datetime' }, { label: '字典', value: 'dict' }, { label: '实体关联', value: 'entity' }, { label: '远程选项', value: 'remote' }, { label: '文件', value: 'file' }, { label: '图片', value: 'image' }, { label: 'JSON', value: 'json' }, { label: '派生字段', value: 'computed' }], regions: [{ label: '查询区', value: 'query', icon: 'el-icon-search', help: '组合过滤条件' }, { label: '数据列表', value: 'list', icon: 'el-icon-s-grid', help: '分页、排序和批量操作' }, { label: '编辑表单', value: 'form', icon: 'el-icon-edit-outline', help: '新增与修改记录' }, { label: '详情', value: 'detail', icon: 'el-icon-document', help: '只读业务详情' }, { label: '统计卡', value: 'stats', icon: 'el-icon-data-analysis', help: '关键指标摘要' }, { label: '趋势图', value: 'trend', icon: 'el-icon-data-line', help: '绑定 IoTDB 只读连接器' }, { label: '诊断结果', value: 'diagnosis', icon: 'el-icon-cpu', help: '任务状态、模型与证据' }], actionEvents: ['FORM_CHANGE', 'BEFORE_SAVE', 'AFTER_SAVE', 'MANUAL'] } },
  computed: {
    pageRegions() { return this.metadata.pages && this.metadata.pages[0] ? this.metadata.pages[0].regions : [] },
    releaseSteps() { return [{ key: 'draft', label: '草稿', caption: this.current.draft ? `v${this.current.draft.versionNo}` : '未选择', state: this.current.id ? 'done' : '' }, { key: 'validation', label: '校验', caption: this.validation.valid ? '结构与数据库一致' : '等待完整校验', state: this.validation.valid ? 'done' : this.validation.errors ? 'error' : '' }, { key: 'publish', label: '发布', caption: this.current.active ? `线上 v${this.current.active.versionNo}` : '尚未发布', state: this.current.active ? 'done' : '' }] }
  },
  created() { this.refresh() },
  methods: {
    exportUrl,
    async refresh() { const res = await listProjects(); this.projects = res.data || [] },
    async selectProject(id) { const res = await getProject(id); this.current = res.data; this.metadata = JSON.parse(this.current.draft.metadataJson); this.syncText(); this.validation = this.current.draft.validationJson ? JSON.parse(this.current.draft.validationJson) : {} },
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
    escape(text) { return text.replace(/[&<>]/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[char])) }
  }
}
</script>

<style lang="scss" scoped>
$graphite: #18212b; $steel: #334155; $work: #f5f7fa; $signal: #2f80ed; $amber: #d99024; $success: #2e8b57;
.lc-shell { min-height: calc(100vh - 84px); margin: -20px; background: $work; color: $graphite; font-family: "Microsoft YaHei", sans-serif; }
.lc-command-bar { display:flex; justify-content:space-between; align-items:flex-end; padding:28px 34px 24px; background:$graphite; color:#fff; border-bottom:4px solid $signal; h1{margin:4px 0 5px;font-size:30px;letter-spacing:.02em} p{margin:0;color:#bac5d1} }
.lc-eyebrow,.project-code,.release-index,code { font-family:Bahnschrift,Consolas,monospace; letter-spacing:.08em; }.lc-eyebrow{font-size:11px;color:#83b6f4}.lc-header-actions{display:flex;gap:8px}
.release-rail{position:sticky;top:0;z-index:8;display:flex;align-items:center;gap:28px;padding:13px 34px;background:#fff;border-bottom:1px solid #dbe1e8;box-shadow:0 5px 18px rgba(24,33,43,.06)}
.release-node{position:relative;display:flex;align-items:center;gap:10px;min-width:150px;&:not(:last-of-type):after{content:"";position:absolute;right:-20px;width:12px;height:1px;background:#c5ced8}.release-index{display:grid;place-items:center;width:28px;height:28px;border-radius:50%;background:#e8edf3;color:#667585}.done .release-index{background:$success;color:#fff}.error .release-index{background:#c84b4b;color:#fff} b,small{display:block}small{margin-top:2px;color:#7a8795;font-size:11px}}.release-actions{margin-left:auto;display:flex;gap:7px}
.lc-grid{display:grid;grid-template-columns:238px minmax(0,1fr);min-height:calc(100vh - 222px)}.project-rail{padding:22px 14px;background:$steel;color:#fff}.rail-title{display:flex;justify-content:space-between;padding:0 8px 12px;font-weight:700;em{font-style:normal;color:#9fb1c5}}.project-card{width:100%;padding:14px;margin-bottom:8px;text-align:left;color:#d8e0e8;background:transparent;border:1px solid transparent;border-radius:6px;cursor:pointer;transition:.16s}.project-card strong,.project-card small{display:block}.project-code{font-size:10px;color:#8da3b8}.project-card strong{margin:5px 0;font-size:14px}.project-card small{color:#9fb1c5}.project-card:hover,.project-card.active{background:#263647;border-color:#4b6680}.project-card.active{box-shadow:inset 3px 0 $signal}.rail-empty{padding:24px 10px;color:#aebbc8;line-height:1.7}
.designer-stage{padding:24px 28px;overflow:hidden}.workspace-tabs::v-deep .el-tabs__header{margin-bottom:20px}.workspace-tabs::v-deep .el-tabs__item{font-weight:700}.stage-toolbar{display:flex;justify-content:space-between;align-items:center;margin-bottom:18px;h2{margin:0 0 4px;font-size:21px}p{margin:0;color:#718092}}.model-basics{display:grid;grid-template-columns:2fr 1fr 1fr;gap:14px}.field-board{display:flex;flex-direction:column;gap:8px}.field-row{display:grid;grid-template-columns:24px 1.1fr 1.2fr 150px 2fr 48px;gap:10px;align-items:center;padding:12px;background:#fff;border:1px solid #dce3ea;border-radius:6px}.field-grip{color:#8b9aaa;cursor:move}.field-flags{display:flex;flex-wrap:wrap;gap:0 10px}.danger-link{color:#c45656}.add-field{width:100%;margin-top:10px;border-style:dashed}
.region-board{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:13px}.region-card{position:relative;display:grid;grid-template-columns:26px 42px 1fr;grid-template-rows:auto auto;align-items:center;padding:18px;background:#fff;border:1px solid #dce3ea;border-radius:7px;cursor:pointer;&.enabled{border-color:$signal;box-shadow:inset 0 0 0 1px $signal}.el-checkbox{grid-row:1/3}.region-card>i{grid-row:1/3;font-size:25px;color:$signal}.region-card span{font-size:12px;color:#748292}}
.config-card{padding:16px;margin-bottom:12px;background:#fff;border-left:3px solid $amber;box-shadow:0 5px 15px rgba(24,33,43,.05)}.config-head,.action-card{display:grid;grid-template-columns:1fr 180px 60px;gap:10px;margin-bottom:10px}.action-card{grid-template-columns:1fr 170px 220px 1fr 1fr 50px;border-left-color:$signal}.permission-form{max-width:760px}.validation-list article{display:grid;grid-template-columns:170px 1fr 220px;gap:10px;padding:10px 12px;margin-bottom:7px;background:#fff3f2;border-left:3px solid #c84b4b}.advanced-json{margin-top:18px}.ddl-output{padding:18px;background:#101820;color:#d8e7f5;white-space:pre-wrap;max-height:500px;overflow:auto}.stage-empty{display:grid;place-items:center;align-content:center;min-height:480px;color:#657586;text-align:center}.sensor-mark{display:flex;gap:8px;margin-bottom:20px;i{display:block;width:10px;height:46px;background:$signal;border-radius:10px;animation:pulse 1.8s infinite ease-in-out}i:nth-child(2){height:70px;background:$amber;animation-delay:.18s}i:nth-child(3){height:34px;background:$success;animation-delay:.36s}}@keyframes pulse{50%{transform:scaleY(.72);opacity:.72}}
@media(max-width:1100px){.lc-grid{grid-template-columns:200px minmax(0,1fr)}.field-row{grid-template-columns:24px 1fr 1fr 130px}.field-flags{grid-column:2/5}.region-board{grid-template-columns:repeat(2,1fr)}.release-node{min-width:auto}.release-node small{display:none}}
@media(prefers-reduced-motion:reduce){.sensor-mark i{animation:none}}
</style>
