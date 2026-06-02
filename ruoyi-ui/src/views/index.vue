<template>
  <div class="app-container home">
    <el-row :gutter="20">
      <el-col :sm="24" :lg="12" style="padding-left: 20px">
        <h2>振动温度监测平台</h2>
        <p>
          本平台用于振动与温度数据的实时监测、趋势分析与告警管理，支持采集仪上报、历史查询和实时展示。
        </p>
      </el-col>

      <el-col :sm="24" :lg="12" style="padding-left: 50px">
        <el-row>
          <el-col :span="12">
            <h2>技术选型</h2>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="6">
            <h4>后端技术</h4>
            <ul>
              <li>SpringBoot</li>
              <li>Spring Security</li>
              <li>JWT</li>
              <li>MyBatis</li>
              <li>Druid</li>
              <li>Fastjson</li>
              <li>...</li>
            </ul>
          </el-col>
          <el-col :span="6">
            <h4>前端技术</h4>
            <ul>
              <li>Vue</li>
              <li>Vuex</li>
              <li>Element-ui</li>
              <li>Axios</li>
              <li>Sass</li>
              <li>Quill</li>
              <li>...</li>
            </ul>
          </el-col>
        </el-row>
      </el-col>
    </el-row>

    <el-divider />

    <el-row :gutter="20">
      <el-col :xs="24" :sm="24" :md="12" :lg="8">
        <el-card class="update-log">
          <div slot="header" class="clearfix">
            <span>平台说明</span>
          </div>
          <div class="body">
            <p>本平台聚焦振动与温度数据的实时监测、趋势分析、历史查询和告警展示。</p>
            <p>支持采集仪数据上报、设备联动与阈值高亮，适合工业监测场景。</p>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="24" :md="12" :lg="8">
        <el-card class="update-log">
          <div slot="header" class="clearfix">
            <span>功能简介</span>
          </div>
          <div class="body">
            <ol>
              <li>振动数据实时显示</li>
              <li>温度数据实时显示</li>
              <li>趋势图自动刷新</li>
              <li>告警阈值设置与高亮</li>
              <li>采集仪实时上传支持</li>
            </ol>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script>
export default {
  name: 'Index',
  data() {
    return {
      version: '1.0.0'
    }
  }
}
</script>
