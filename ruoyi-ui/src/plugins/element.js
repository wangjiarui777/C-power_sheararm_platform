import Vue from 'vue'
import Cookies from 'js-cookie'
import Breadcrumb from 'element-ui/lib/breadcrumb'
import BreadcrumbItem from 'element-ui/lib/breadcrumb-item'
import Button from 'element-ui/lib/button'
import Checkbox from 'element-ui/lib/checkbox'
import Dropdown from 'element-ui/lib/dropdown'
import DropdownItem from 'element-ui/lib/dropdown-item'
import DropdownMenu from 'element-ui/lib/dropdown-menu'
import Form from 'element-ui/lib/form'
import FormItem from 'element-ui/lib/form-item'
import Input from 'element-ui/lib/input'
import Menu from 'element-ui/lib/menu'
import MenuItem from 'element-ui/lib/menu-item'
import Scrollbar from 'element-ui/lib/scrollbar'
import Submenu from 'element-ui/lib/submenu'
import Tooltip from 'element-ui/lib/tooltip'
import Loading from 'element-ui/lib/loading'
import Message from 'element-ui/lib/message'
import MessageBox from 'element-ui/lib/message-box'
import Notification from 'element-ui/lib/notification'

const components = [
  Breadcrumb, BreadcrumbItem, Button, Checkbox, Dropdown, DropdownItem,
  DropdownMenu, Form, FormItem, Input, Menu, MenuItem, Scrollbar, Submenu,
  Tooltip
]

components.forEach(component => Vue.use(component))

const asyncComponents = {
  ElAlert: () => import(/* webpackChunkName: "element-async-feedback" */ 'element-ui/lib/alert'),
  ElBadge: () => import(/* webpackChunkName: "element-async-feedback" */ 'element-ui/lib/badge'),
  ElCard: () => import(/* webpackChunkName: "element-async-layout" */ 'element-ui/lib/card'),
  ElCol: () => import(/* webpackChunkName: "element-async-layout" */ 'element-ui/lib/col'),
  ElColorPicker: () => import(/* webpackChunkName: "element-async-form" */ 'element-ui/lib/color-picker'),
  ElDatePicker: () => import(/* webpackChunkName: "element-async-form" */ 'element-ui/lib/date-picker'),
  ElDescriptions: () => import(/* webpackChunkName: "element-async-data" */ 'element-ui/lib/descriptions'),
  ElDescriptionsItem: () => import(/* webpackChunkName: "element-async-data" */ 'element-ui/lib/descriptions-item'),
  ElDialog: () => import(/* webpackChunkName: "element-async-overlay" */ 'element-ui/lib/dialog'),
  ElDivider: () => import(/* webpackChunkName: "element-async-layout" */ 'element-ui/lib/divider'),
  ElDrawer: () => import(/* webpackChunkName: "element-async-overlay" */ 'element-ui/lib/drawer'),
  ElEmpty: () => import(/* webpackChunkName: "element-async-feedback" */ 'element-ui/lib/empty'),
  ElImage: () => import(/* webpackChunkName: "element-async-media" */ 'element-ui/lib/image'),
  ElInputNumber: () => import(/* webpackChunkName: "element-async-form" */ 'element-ui/lib/input-number'),
  ElLink: () => import(/* webpackChunkName: "element-async-feedback" */ 'element-ui/lib/link'),
  ElOption: () => import(/* webpackChunkName: "element-async-form" */ 'element-ui/lib/option'),
  ElPagination: () => import(/* webpackChunkName: "element-async-data" */ 'element-ui/lib/pagination'),
  ElPopover: () => import(/* webpackChunkName: "element-async-overlay" */ 'element-ui/lib/popover'),
  ElProgress: () => import(/* webpackChunkName: "element-async-feedback" */ 'element-ui/lib/progress'),
  ElRadio: () => import(/* webpackChunkName: "element-async-form" */ 'element-ui/lib/radio'),
  ElRadioButton: () => import(/* webpackChunkName: "element-async-form" */ 'element-ui/lib/radio-button'),
  ElRadioGroup: () => import(/* webpackChunkName: "element-async-form" */ 'element-ui/lib/radio-group'),
  ElRow: () => import(/* webpackChunkName: "element-async-layout" */ 'element-ui/lib/row'),
  ElSelect: () => import(/* webpackChunkName: "element-async-form" */ 'element-ui/lib/select'),
  ElSwitch: () => import(/* webpackChunkName: "element-async-form" */ 'element-ui/lib/switch'),
  ElTabPane: () => import(/* webpackChunkName: "element-async-data" */ 'element-ui/lib/tab-pane'),
  ElTable: () => import(/* webpackChunkName: "element-async-table" */ 'element-ui/lib/table'),
  ElTableColumn: () => import(/* webpackChunkName: "element-async-table" */ 'element-ui/lib/table-column'),
  ElTabs: () => import(/* webpackChunkName: "element-async-data" */ 'element-ui/lib/tabs'),
  ElTag: () => import(/* webpackChunkName: "element-async-feedback" */ 'element-ui/lib/tag'),
  ElTimeline: () => import(/* webpackChunkName: "element-async-data" */ 'element-ui/lib/timeline'),
  ElTimelineItem: () => import(/* webpackChunkName: "element-async-data" */ 'element-ui/lib/timeline-item'),
  ElTransfer: () => import(/* webpackChunkName: "element-async-data" */ 'element-ui/lib/transfer'),
  ElTree: () => import(/* webpackChunkName: "element-async-data" */ 'element-ui/lib/tree'),
  ElUpload: () => import(/* webpackChunkName: "element-async-media" */ 'element-ui/lib/upload')
}

Object.entries(asyncComponents).forEach(([name, factory]) => Vue.component(name, factory))
Vue.use(Loading.directive)

Vue.prototype.$ELEMENT = { size: Cookies.get('size') || 'medium' }
Vue.prototype.$loading = Loading.service
Vue.prototype.$message = Message
Vue.prototype.$msgbox = MessageBox
Vue.prototype.$alert = MessageBox.alert
Vue.prototype.$confirm = MessageBox.confirm
Vue.prototype.$prompt = MessageBox.prompt
Vue.prototype.$notify = Notification
