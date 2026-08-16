<template>
  <div class="runtime-page" v-loading="loading">
    <header class="runtime-head">
      <div><span class="runtime-code">{{ appCode }} · v{{ schema.versionNo || '-' }}</span><h1>{{ schema.projectName || '低代码应用' }}</h1></div>
      <div><el-tag :type="schema.writeEnabled ? 'success' : 'info'">{{ schema.writeEnabled ? '可写运行时' : '只读运行时' }}</el-tag><el-button icon="el-icon-refresh" @click="loadRecords">刷新</el-button></div>
    </header>

    <el-form v-if="hasRegion('query')" :inline="true" class="query-panel" @submit.native.prevent>
      <el-form-item v-for="field in queryFields" :key="field.name" :label="field.label || field.name">
        <el-input v-model="query[field.name]" clearable :placeholder="`筛选${field.label || field.name}`" @keyup.enter.native="loadRecords" />
      </el-form-item>
      <el-form-item><el-button type="primary" @click="loadRecords">查询</el-button><el-button @click="resetQuery">重置</el-button></el-form-item>
    </el-form>

    <section v-if="hasRegion('list')" class="data-panel">
      <div class="panel-title"><div><b>数据记录</b><span>{{ total }} 条</span></div><el-button v-if="schema.writeEnabled" v-hasPermi="['lowcode:runtime:add']" type="primary" icon="el-icon-plus" @click="openCreate">新增记录</el-button></div>
      <el-table :data="rows" stripe>
        <el-table-column v-for="field in listFields" :key="field.name" :prop="field.name" :label="field.label || field.name" :min-width="field.width || 130" show-overflow-tooltip>
          <template slot-scope="scope"><el-switch v-if="field.type === 'switch'" :value="Boolean(scope.row[field.name])" disabled /><span v-else>{{ displayValue(scope.row[field.name]) }}</span></template>
        </el-table-column>
        <el-table-column v-if="schema.writeEnabled || manualActions.length" label="操作" width="230" fixed="right">
          <template slot-scope="scope"><el-button v-if="schema.writeEnabled" v-hasPermi="['lowcode:runtime:edit']" type="text" @click="openEdit(scope.row)">编辑</el-button><el-button v-for="action in manualActions" v-hasPermi="['lowcode:runtime:action']" :key="action.code" type="text" @click="runAction(action, scope.row)">{{ action.label || action.code }}</el-button><el-button v-if="schema.writeEnabled" v-hasPermi="['lowcode:runtime:remove']" type="text" class="danger" @click="remove(scope.row)">删除</el-button></template>
        </el-table-column>
      </el-table>
      <pagination v-show="total > 0" :total="total" :page.sync="query.pageNum" :limit.sync="query.pageSize" @pagination="loadRecords" />
    </section>

    <el-dialog :title="editingId ? '编辑记录' : '新增记录'" :visible.sync="formVisible" width="720px">
      <el-form ref="recordForm" :model="form" label-position="top" class="record-form">
        <el-form-item v-for="field in editableFields" v-show="visible(field)" :key="field.name" :label="field.label || field.name" :required="field.required">
          <el-input v-if="['text','textarea','json'].includes(field.type)" v-model="form[field.name]" :type="field.type === 'text' ? 'text' : 'textarea'" :rows="field.type === 'json' ? 6 : 3" :disabled="readOnly(field)" />
          <el-input-number v-else-if="['number','long','decimal'].includes(field.type)" v-model="form[field.name]" :disabled="readOnly(field)" controls-position="right" />
          <el-switch v-else-if="field.type === 'switch'" v-model="form[field.name]" :disabled="readOnly(field)" />
          <el-date-picker v-else-if="field.type === 'date' || field.type === 'datetime'" v-model="form[field.name]" :type="field.type" value-format="yyyy-MM-dd HH:mm:ss" :disabled="readOnly(field)" />
          <el-select v-else-if="field.type === 'dict'" v-model="form[field.name]" filterable clearable :disabled="readOnly(field)"><el-option v-for="option in field.options || []" :key="option.value" :label="option.label" :value="option.value" /></el-select>
          <el-input v-else v-model="form[field.name]" :disabled="readOnly(field)" />
        </el-form-item>
      </el-form>
      <span slot="footer"><el-button @click="formVisible = false">取消</el-button><el-button v-hasPermi="['lowcode:runtime:add', 'lowcode:runtime:edit']" type="primary" @click="submit">保存</el-button></span>
    </el-dialog>
  </div>
</template>

<script>
import { getRuntimeSchema, listRuntimeRecords, addRuntimeRecord, updateRuntimeRecord, removeRuntimeRecord, runRuntimeAction } from '@/api/tool/lowcode'

export default {
  name: 'LowCodeRuntime',
  data() { return { loading: false, schema: {}, metadata: { model: { fields: [] }, pages: [], rules: [], actions: [] }, rows: [], total: 0, query: { pageNum: 1, pageSize: 20 }, form: {}, editingId: null, formVisible: false } },
  computed: {
    appCode() { return this.$route.params.appCode },
    fields() { return this.metadata.model.fields || [] },
    listFields() { return this.fields.filter(field => field.list !== false) },
    queryFields() { return this.fields.filter(field => field.query) },
    editableFields() { return this.fields.filter(field => !field.readOnly && field.type !== 'computed') },
    primaryKey() { return this.metadata.model.primaryKey },
    manualActions() { return (this.metadata.actions || []).filter(action => action.event === 'MANUAL') }
  },
  watch: { appCode() { this.initialize() } },
  created() { this.initialize() },
  methods: {
    async initialize() { this.loading = true; try { const res = await getRuntimeSchema(this.appCode); this.schema = res.data; this.metadata = res.data.metadata; this.resetQuery(); await this.loadRecords() } finally { this.loading = false } },
    async loadRecords() { this.loading = true; try { const res = await listRuntimeRecords(this.appCode, this.query); this.rows = res.data.rows || []; this.total = res.data.total || 0 } finally { this.loading = false } },
    resetQuery() { const paging = { pageNum: 1, pageSize: this.query.pageSize || 20 }; this.queryFields.forEach(field => { paging[field.name] = '' }); this.query = paging },
    hasRegion(region) { const page = this.metadata.pages && this.metadata.pages[0]; return !page || !page.regions || page.regions.includes(region) },
    openCreate() { this.editingId = null; this.form = {}; this.editableFields.forEach(field => { if (field.defaultValue !== undefined) this.$set(this.form, field.name, field.defaultValue) }); this.formVisible = true },
    openEdit(row) { this.editingId = row[this.primaryKey]; this.form = {}; this.editableFields.forEach(field => { this.$set(this.form, field.name, row[field.name]) }); this.formVisible = true },
    async submit() { if (this.editingId) await updateRuntimeRecord(this.appCode, this.editingId, this.form); else await addRuntimeRecord(this.appCode, this.form); this.$modal.msgSuccess('记录已保存'); this.formVisible = false; await this.loadRecords() },
    async remove(row) { await this.$modal.confirm('确认删除该记录？'); await removeRuntimeRecord(this.appCode, row[this.primaryKey]); this.$modal.msgSuccess('记录已删除'); await this.loadRecords() },
    async runAction(action, row) { let payload = { ...row }; if (action.inputFields && action.inputFields.length) { const prompted = await this.$prompt(`请输入附加参数 JSON，例如 ${JSON.stringify(Object.fromEntries(action.inputFields.map(key => [key, ''])))}`, action.label || action.code, { inputValue: JSON.stringify(Object.fromEntries(action.inputFields.map(key => [key, '']))) }); try { payload = { ...payload, ...JSON.parse(prompted.value) } } catch (e) { return this.$modal.msgError('动作参数不是合法 JSON') } } const res = await runRuntimeAction(this.appCode, action.code, payload); this.$alert(`<pre style="max-height:420px;overflow:auto">${this.escape(JSON.stringify(res.data, null, 2))}</pre>`, action.label || action.code, { dangerouslyUseHTMLString: true }) },
    visible(field) { return !this.effectMatches('VISIBLE', field.name, false) },
    readOnly(field) { return field.readOnly || this.effectMatches('READ_ONLY', field.name, true) },
    effectMatches(effect, target, fallback) { const rules = (this.metadata.rules || []).filter(rule => rule.effect === effect && rule.target === target); if (!rules.length) return false; try { return rules.some(rule => this.evaluate(rule.condition)) } catch (e) { return fallback } },
    evaluate(node) { if (!node || typeof node !== 'object') return node; if (Object.prototype.hasOwnProperty.call(node, 'field')) return this.form[node.field]; if (Object.prototype.hasOwnProperty.call(node, 'value')) return node.value; const args = (node.args || []).map(item => this.evaluate(item)); switch (String(node.op).toLowerCase()) { case 'eq': return args[0] === args[1]; case 'ne': return args[0] !== args[1]; case 'gt': return Number(args[0]) > Number(args[1]); case 'gte': return Number(args[0]) >= Number(args[1]); case 'lt': return Number(args[0]) < Number(args[1]); case 'lte': return Number(args[0]) <= Number(args[1]); case 'and': return args.every(Boolean); case 'or': return args.some(Boolean); case 'not': return !args[0]; case 'empty': return args[0] === null || args[0] === undefined || args[0] === ''; default: return false } },
    displayValue(value) { if (value === null || value === undefined) return '—'; if (typeof value === 'object') return JSON.stringify(value); return value },
    escape(text) { return text.replace(/[&<>]/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[char])) }
  }
}
</script>

<style lang="scss" scoped>
.runtime-page{min-height:calc(100vh - 84px);padding:24px;background:#f5f7fa;color:#18212b}.runtime-head{display:flex;justify-content:space-between;align-items:flex-end;margin-bottom:18px;padding:22px 26px;background:#18212b;color:#fff;border-left:5px solid #2f80ed;h1{margin:5px 0 0;font-size:27px}.runtime-head>div:last-child{display:flex;align-items:center;gap:10px}}.runtime-code{font-family:Bahnschrift,Consolas,monospace;font-size:11px;letter-spacing:.08em;color:#8db9ed}.query-panel,.data-panel{padding:18px 20px;margin-bottom:16px;background:#fff;border:1px solid #dce3ea}.query-panel::v-deep .el-form-item{margin-bottom:8px}.panel-title{display:flex;justify-content:space-between;align-items:center;margin-bottom:14px;b{font-size:18px}span{margin-left:9px;color:#7b8896;font-family:Bahnschrift,monospace}}.danger{color:#c45656}.record-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 18px}.record-form::v-deep .el-input-number,.record-form::v-deep .el-select,.record-form::v-deep .el-date-editor{width:100%}@media(max-width:800px){.runtime-head{align-items:flex-start;gap:18px;flex-direction:column}.record-form{grid-template-columns:1fr}}
</style>
