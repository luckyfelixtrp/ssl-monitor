<template>
  <div class="max-w-7xl mx-auto px-4 py-6">
    <header class="flex items-center justify-between mb-6">
      <div>
        <h1 class="text-2xl font-bold text-gray-800">SSL 证书监控</h1>
        <p class="text-sm text-gray-500 mt-1">监控域名 HTTPS 证书的到期时间、安全配置与可用性</p>
      </div>
      <div class="flex gap-2">
        <button @click="loadDomains" class="px-3 py-2 bg-white border border-gray-300 rounded text-sm hover:bg-gray-100">刷新列表</button>
        <button @click="checkAll" class="px-3 py-2 bg-blue-600 text-white rounded text-sm hover:bg-blue-700">扫描全部</button>
        <button @click="openAdd" class="px-3 py-2 bg-green-600 text-white rounded text-sm hover:bg-green-700">+ 添加域名</button>
      </div>
    </header>

    <!-- Summary -->
    <div class="grid grid-cols-2 md:grid-cols-5 gap-3 mb-6">
      <div v-for="c in summary" :key="c.label" class="bg-white rounded shadow-sm p-3">
        <div class="text-xs text-gray-500">{{ c.label }}</div>
        <div class="text-2xl font-bold" :class="c.color">{{ c.value }}</div>
      </div>
    </div>

    <!-- Filter -->
    <div class="bg-white rounded shadow-sm p-3 mb-3 flex flex-wrap items-center gap-3">
      <input v-model="filterKw" placeholder="搜索域名 / 备注..." class="flex-1 min-w-[200px] px-3 py-1.5 border border-gray-300 rounded text-sm" />
      <select v-model="filterStatus" class="px-3 py-1.5 border border-gray-300 rounded text-sm">
        <option value="">全部状态</option>
        <option value="expired">已过期</option>
        <option value="warning">即将过期 (≤30天)</option>
        <option value="ok">正常</option>
        <option value="error">检测失败</option>
      </select>
    </div>

    <!-- Table -->
    <div class="bg-white rounded shadow-sm overflow-hidden">
      <div class="overflow-x-auto">
        <table class="w-full text-sm">
          <thead class="bg-gray-100 text-gray-700 text-left">
            <tr>
              <th class="px-3 py-2 font-medium">域名</th>
              <th class="px-3 py-2 font-medium">备注</th>
              <th class="px-3 py-2 font-medium">剩余天数</th>
              <th class="px-3 py-2 font-medium">到期日期</th>
              <th class="px-3 py-2 font-medium">评级</th>
              <th class="px-3 py-2 font-medium">TLS</th>
              <th class="px-3 py-2 font-medium">可用性</th>
              <th class="px-3 py-2 font-medium">最后检测</th>
              <th class="px-3 py-2 font-medium text-right">操作</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-gray-100">
            <tr v-for="d in filteredDomains" :key="d.id" :class="[rowClass(d), 'hover:bg-gray-50']">
              <td class="px-3 py-2 font-medium text-gray-900">
                {{ d.host }}{{ d.port === 443 ? '' : ':' + d.port }}
                <div v-if="!d.ok && d.error" class="text-xs text-red-500 mt-1 truncate" :title="d.error">{{ d.error }}</div>
              </td>
              <td class="px-3 py-2 text-gray-500">{{ d.remark || '' }}</td>
              <td class="px-3 py-2" v-html="daysBadge(d.days_left, d.ok)"></td>
              <td class="px-3 py-2 text-gray-600">{{ d.not_after ? new Date(d.not_after).toLocaleDateString('zh-CN') : '-' }}</td>
              <td class="px-3 py-2" v-html="gradeBadge(d.grade) + (d.score !== null ? `<span class='text-xs text-gray-400 ml-1'>${d.score}</span>` : '')"></td>
              <td class="px-3 py-2 text-gray-600">{{ d.tls_version || '-' }}</td>
              <td class="px-3 py-2" v-html="reachHtml(d)"></td>
              <td class="px-3 py-2 text-xs text-gray-500">{{ fmtDate(d.checked_at) }}</td>
              <td class="px-3 py-2 text-right whitespace-nowrap">
                <button class="text-blue-600 hover:underline mr-2" @click="showDetail(d.id, d.host)">详情</button>
                <button class="text-blue-600 hover:underline mr-2" @click="checkOne(d.id, $event)">检测</button>
                <button class="text-gray-600 hover:underline mr-2" @click="openEdit(d)">编辑</button>
                <button class="text-red-500 hover:underline" @click="deleteDomain(d.id, d.host)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="filteredDomains.length === 0" class="text-center text-gray-400 py-12">暂无域名，点击右上角「+ 添加域名」开始</div>
    </div>

    <!-- Add/Edit Modal -->
    <div v-if="showEditModal" class="fixed inset-0 modal-bg flex items-center justify-center z-50">
      <div class="bg-white rounded-lg shadow-xl p-6 w-full max-w-md">
        <h3 class="text-lg font-semibold mb-4">{{ editId ? '编辑域名' : '添加域名' }}</h3>
        <form @submit.prevent="submitEdit" class="space-y-3">
          <input type="hidden" v-model="editId" />
          <div>
            <label class="block text-sm text-gray-600 mb-1">域名 *</label>
            <input v-model="editHost" required placeholder="example.com 或 mail.example.com:8443" class="w-full px-3 py-2 border border-gray-300 rounded" />
            <div class="text-xs text-gray-400 mt-1">只填主机名，不要带 https:// 或路径。可在主机名后用冒号附加端口。</div>
          </div>
          <div>
            <label class="block text-sm text-gray-600 mb-1">端口</label>
            <input v-model.number="editPort" type="number" min="1" max="65535" class="w-full px-3 py-2 border border-gray-300 rounded" />
          </div>
          <div>
            <label class="block text-sm text-gray-600 mb-1">备注</label>
            <input v-model="editRemark" placeholder="生产环境主站" class="w-full px-3 py-2 border border-gray-300 rounded" />
          </div>
          <div class="flex justify-end gap-2 pt-2">
            <button type="button" @click="showEditModal = false" class="px-4 py-2 border border-gray-300 rounded">取消</button>
            <button type="submit" class="px-4 py-2 bg-blue-600 text-white rounded">保存</button>
          </div>
        </form>
      </div>
    </div>

    <!-- Detail Modal -->
    <div v-if="showDetailModal" class="fixed inset-0 modal-bg flex items-center justify-center z-50">
      <div class="bg-white rounded-lg shadow-xl w-full max-w-3xl max-h-[90vh] overflow-y-auto">
        <div class="flex justify-between items-center px-6 py-4 border-b sticky top-0 bg-white">
          <h3 class="text-lg font-semibold">{{ detailHost }} - 证书详情</h3>
          <button @click="showDetailModal = false" class="text-gray-400 hover:text-gray-700 text-2xl leading-none">&times;</button>
        </div>
        <div class="p-6">
          <div v-if="detailLoading" class="text-center py-8"><span class="spinner"></span> 加载中...</div>
          <div v-else-if="detailError" class="text-red-500">加载失败: {{ detailError }}</div>
          <div v-else v-html="detailHtml"></div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { api, cleanHostInput, fmtDate, gradeBadge, daysBadge, rowClass, renderDetail } from './api.js'

export default {
  name: 'App',
  data() {
    return {
      domains: [],
      filterKw: '',
      filterStatus: '',
      showEditModal: false,
      editId: null,
      editHost: '',
      editPort: 443,
      editRemark: '',
      showDetailModal: false,
      detailHost: '',
      detailHtml: '',
      detailLoading: false,
      detailError: '',
      refreshTimer: null,
    }
  },
  computed: {
    summary() {
      const total = this.domains.length;
      const expired = this.domains.filter(d => d.days_left !== null && d.days_left < 0).length;
      const warning = this.domains.filter(d => d.days_left !== null && d.days_left >= 0 && d.days_left <= 30).length;
      const errored = this.domains.filter(d => d.ok === 0 || d.ok === false).length;
      const reachable = this.domains.filter(d => d.reachable === 1 || d.reachable === true).length;
      return [
        { label: '总域名', value: total, color: 'text-gray-700' },
        { label: '已过期', value: expired, color: 'text-red-600' },
        { label: '30天内到期', value: warning, color: 'text-orange-600' },
        { label: '检测异常', value: errored, color: 'text-red-500' },
        { label: '可访问', value: reachable, color: 'text-green-600' },
      ];
    },
    filteredDomains() {
      const kw = (this.filterKw || '').toLowerCase().trim();
      const status = this.filterStatus;
      return this.domains.filter(d => {
        if (kw && !`${d.host} ${d.remark || ''}`.toLowerCase().includes(kw)) return false;
        if (status === 'expired' && !(d.days_left !== null && d.days_left < 0)) return false;
        if (status === 'warning' && !(d.days_left !== null && d.days_left >= 0 && d.days_left <= 30)) return false;
        if (status === 'ok' && !(d.days_left !== null && d.days_left > 30 && d.ok)) return false;
        if (status === 'error' && (d.ok === 1 || d.ok === true)) return false;
        return true;
      });
    },
  },
  methods: {
    fmtDate,
    gradeBadge,
    daysBadge,
    rowClass,
    reachHtml(d) {
      if (d.reachable === 1 || d.reachable === true) {
        return `<span class="text-green-600">${d.status_code || 'OK'}</span><span class="text-gray-400 text-xs ml-1">${d.response_ms || 0}ms</span>`;
      }
      return d.checked_at ? '<span class="text-red-500">不可达</span>' : '<span class="text-gray-400">-</span>';
    },
    async loadDomains() {
      try {
        this.domains = await api('/api/domains');
      } catch (e) {
        alert('加载失败: ' + e.message);
      }
    },
    async checkAll() {
      if (!confirm('将对所有域名重新检测，可能需要数十秒，确认?')) return;
      await api('/api/check-all', { method: 'POST' });
      alert('已开始扫描，稍后可点击 [刷新列表] 查看结果');
    },
    async checkOne(id, event) {
      const btn = event.target;
      const orig = btn.innerHTML;
      btn.innerHTML = '<span class="spinner"></span>';
      try {
        await api(`/api/domains/${id}/check`, { method: 'POST' });
        await this.loadDomains();
      } catch (err) {
        alert('检测失败: ' + err.message);
      } finally {
        btn.innerHTML = orig;
      }
    },
    openAdd() {
      this.editId = null;
      this.editHost = '';
      this.editPort = 443;
      this.editRemark = '';
      this.showEditModal = true;
    },
    openEdit(d) {
      this.editId = d.id;
      this.editHost = d.host;
      this.editPort = d.port;
      this.editRemark = d.remark || '';
      this.showEditModal = true;
    },
    async submitEdit() {
      let host = cleanHostInput(this.editHost);
      let port = this.editPort;
      if (host.includes(':')) {
        const [h, p] = host.split(':');
        host = h;
        const parsed = parseInt(p, 10);
        if (!isNaN(parsed)) port = parsed;
      }
      const body = JSON.stringify({ host, port, remark: this.editRemark || '' });
      try {
        if (this.editId) {
          await api(`/api/domains/${this.editId}`, { method: 'PUT', body });
        } else {
          await api('/api/domains', { method: 'POST', body });
        }
        this.showEditModal = false;
        await this.loadDomains();
      } catch (err) {
        alert('操作失败: ' + err.message);
      }
    },
    async deleteDomain(id, host) {
      if (!confirm(`确认删除 ${host} ?`)) return;
      await api(`/api/domains/${id}`, { method: 'DELETE' });
      await this.loadDomains();
    },
    async showDetail(id, host) {
      this.detailHost = host;
      this.detailHtml = '';
      this.detailError = '';
      this.detailLoading = true;
      this.showDetailModal = true;
      try {
        const data = await api(`/api/domains/${id}/detail`);
        this.detailHtml = renderDetail(data);
      } catch (e) {
        this.detailError = e.message;
      } finally {
        this.detailLoading = false;
      }
    },
  },
  mounted() {
    this.loadDomains();
    this.refreshTimer = setInterval(() => this.loadDomains(), 60000);
  },
  beforeUnmount() {
    if (this.refreshTimer) clearInterval(this.refreshTimer);
  },
}
</script>
