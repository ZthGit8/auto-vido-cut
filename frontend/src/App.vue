<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { createProject, uploadMaterial, startGenerate } from './api/index.js'
import { useWebSocket } from './composables/useWebSocket.js'

const { progress, connected, connect: wsConnect, disconnect: wsDisconnect } = useWebSocket()

const step = ref(1)
const loading = ref(false)
const error = ref('')

const project = ref(null)
const materials = ref([])
const uploadingFiles = ref(new Set())

const form = ref({ name: '', promotionGoal: '' })
const dragOver = ref(false)

const stepLabels = ['创建项目', '上传素材', '生成视频']

async function handleCreate() {
  if (!form.value.name.trim()) return
  loading.value = true
  error.value = ''
  try {
    project.value = await createProject(form.value.name.trim(), form.value.promotionGoal.trim())
    step.value = 2
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function onDragOver(e) {
  e.preventDefault()
  dragOver.value = true
}
function onDragLeave() {
  dragOver.value = false
}
function onDrop(e) {
  e.preventDefault()
  dragOver.value = false
  handleFiles(e.dataTransfer.files)
}
function onFileInput(e) {
  handleFiles(e.target.files)
  e.target.value = ''
}

async function handleFiles(files) {
  if (!project.value) return
  error.value = ''
  for (const f of files) {
    uploadingFiles.value.add(f.name)
    try {
      const result = await uploadMaterial(project.value.id, f)
      materials.value.push({ ...result, originalName: f.name })
    } catch (e) {
      error.value = `${f.name}: ${e.message}`
    } finally {
      uploadingFiles.value.delete(f.name)
    }
  }
}

function removeMaterial(index) {
  materials.value.splice(index, 1)
}

const canGenerate = computed(() => materials.value.length > 0)

async function handleGenerate() {
  if (!project.value || !canGenerate.value) return
  loading.value = true
  error.value = ''
  step.value = 3
  wsConnect(project.value.id)
  try {
    await startGenerate(project.value.id)
    await nextTick()
  } catch (e) {
    error.value = e.message
    loading.value = false
  }
}

const progressPercent = computed(() => progress.value?.percent ?? 0)
const progressLabel = computed(() => {
  const map = { analyze: '素材分析', script: '脚本生成', composite: '视频合成', done: '完成', error: '失败' }
  return map[progress.value?.phase] || progress.value?.phase || ''
})
const isDone = computed(() => progress.value?.phase === 'done')
const isFailed = computed(() => progress.value?.phase === 'error')

function resetAll() {
  wsDisconnect()
  project.value = null
  materials.value = []
  step.value = 1
  loading.value = false
  error.value = ''
}

watch(isDone, val => { if (val) loading.value = false })
watch(isFailed, val => { if (val) loading.value = false })
</script>

<template>
  <div class="app-shell">
    <!-- Background grain overlay -->
    <div class="grain"></div>

    <!-- Header -->
    <header class="header">
      <div class="logo">
        <span class="logo-mark">◈</span>
        <h1>AutoVido<span class="logo-highlight">Cut</span></h1>
      </div>
      <p class="tagline">AI 驱动的智能视频剪辑引擎</p>
    </header>

    <!-- Step indicator -->
    <nav class="steps" aria-label="工作流程">
      <button
        v-for="(label, i) in stepLabels"
        :key="i"
        :class="['step-dot', { active: step === i + 1, done: step > i + 1 }]"
        @click="i + 1 < step ? step = i + 1 : null"
      >
        <span class="step-num">{{ i + 1 }}</span>
        <span class="step-label">{{ label }}</span>
      </button>
    </nav>

    <!-- Main content -->
    <main class="main">

      <!-- Step 1: Create Project -->
      <section v-if="step === 1" class="card slide-in">
        <div class="card-header">
          <h2>创建剪辑项目</h2>
          <p>设定项目名称与推广目标，AI 将据此生成脚本和分镜。</p>
        </div>
        <div class="card-body">
          <label class="field">
            <span class="field-label">项目名称</span>
            <input
              v-model="form.name"
              type="text"
              placeholder="例如：夏季新品推广"
              maxlength="100"
              @keyup.enter="handleCreate"
            />
          </label>
          <label class="field">
            <span class="field-label">推广目标</span>
            <textarea
              v-model="form.promotionGoal"
              rows="3"
              placeholder="描述你想推广的产品或核心卖点…"
              maxlength="2000"
            ></textarea>
          </label>
          <button
            class="btn-primary"
            :disabled="!form.name.trim() || loading"
            @click="handleCreate"
          >
            <span v-if="loading" class="spinner"></span>
            {{ loading ? '创建中…' : '创建项目 →' }}
          </button>
          <p v-if="error" class="msg-error">{{ error }}</p>
        </div>
      </section>

      <!-- Step 2: Upload Materials -->
      <section v-if="step === 2" class="card slide-in">
        <div class="card-header">
          <h2>上传素材</h2>
          <p>
            项目 <strong>{{ project?.name }}</strong> &nbsp;|&nbsp;
            <button class="link" @click="step = 1">返回编辑</button>
          </p>
        </div>
        <div class="card-body">
          <!-- Drop zone -->
          <div
            :class="['dropzone', { over: dragOver }]"
            @dragover="onDragOver"
            @dragleave="onDragLeave"
            @drop="onDrop"
          >
            <div class="dropzone-inner">
              <span class="dropzone-icon">↗</span>
              <p>拖拽视频或图片到此处</p>
              <span class="dropzone-divider">或</span>
              <label class="btn-outline">
                选择文件
                <input type="file" multiple accept="video/*,image/*" hidden @change="onFileInput" />
              </label>
            </div>
          </div>

          <!-- Uploading indicator -->
          <div v-if="uploadingFiles.size" class="uploading-hint">
            正在上传 {{ uploadingFiles.size }} 个文件…
          </div>

          <!-- Material list -->
          <div v-if="materials.length" class="material-list">
            <div v-for="(m, i) in materials" :key="m.materialId" class="material-row">
              <span class="mat-type" :title="m.mediaType">{{ m.mediaType === 'VIDEO' ? '🎬' : '🖼' }}</span>
              <span class="mat-name">{{ m.originalName }}</span>
              <span class="mat-meta" v-if="m.duration">{{ m.duration.toFixed(1) }}s</span>
              <span class="mat-meta" v-if="m.width">{{ m.width }}×{{ m.height }}</span>
              <button class="mat-remove" @click="removeMaterial(i)" title="移除">×</button>
            </div>
          </div>

          <div v-if="materials.length" class="actions">
            <button class="btn-outline" @click="() => { step = 1; materials = []; project = null }">
              重新开始
            </button>
            <button class="btn-primary" :disabled="!canGenerate || loading" @click="handleGenerate">
              开始生成视频 →
            </button>
          </div>
          <p v-if="error" class="msg-error">{{ error }}</p>
        </div>
      </section>

      <!-- Step 3: Generate Video -->
      <section v-if="step === 3" class="card slide-in">
        <div class="card-header">
          <h2>视频生成中</h2>
          <p>项目 <strong>{{ project?.name }}</strong></p>
        </div>
        <div class="card-body generate-view">
          <!-- Progress display -->
          <div class="progress-section">
            <!-- Film strip progress bar -->
            <div class="film-progress">
              <div class="film-track">
                <div
                  :class="['film-fill', { error: isFailed, done: isDone }]"
                  :style="{ width: progressPercent + '%' }"
                ></div>
                <div class="film-perforations">
                  <span v-for="n in 20" :key="n"></span>
                </div>
              </div>
              <span class="film-pct">{{ Math.round(progressPercent) }}%</span>
            </div>

            <!-- Status message -->
            <div :class="['status-badge', progress?.phase]">
              <span v-if="!isDone && !isFailed" class="spinner sm"></span>
              <span v-else-if="isDone" class="status-icon">✓</span>
              <span v-else class="status-icon">✗</span>
              {{ progressLabel }}
            </div>
            <p class="status-msg">{{ progress?.message || '等待服务器响应…' }}</p>
            <p v-if="isFailed" class="status-msg detail">{{ progress?.message }}</p>

            <!-- Connection indicator -->
            <p class="ws-status" :class="{ on: connected }">
              WebSocket {{ connected ? '已连接' : '连接中…' }}
            </p>
          </div>

          <!-- Actions after completion -->
          <div v-if="isDone || isFailed" class="actions">
            <button class="btn-primary" @click="resetAll">开始新项目</button>
          </div>
        </div>
      </section>

    </main>

    <!-- Footer -->
    <footer class="footer">
      <span>AutoVidoCut</span>
      <span class="footer-divider">·</span>
      <span>AI Video Editing Engine</span>
    </footer>
  </div>
</template>

<style scoped>
.app-shell {
  max-width: 680px;
  margin: 0 auto;
  padding: 40px 24px 80px;
  position: relative;
}

/* Grain texture */
.grain {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  opacity: 0.03;
  background: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E");
}

/* Header */
.header {
  text-align: center;
  padding: 40px 0 32px;
  position: relative;
}
.logo {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
}
.logo-mark {
  font-size: 24px;
  color: var(--accent-amber);
  animation: glow 3s ease-in-out infinite;
}
@keyframes glow {
  0%, 100% { text-shadow: 0 0 6px rgba(212,168,83,0.3); }
  50% { text-shadow: 0 0 20px rgba(240,192,96,0.6); }
}
.logo h1 {
  font-family: var(--font-display);
  font-size: 32px;
  font-weight: 900;
  letter-spacing: -0.02em;
  color: var(--text-primary);
}
.logo-highlight {
  color: var(--accent-amber);
  font-style: italic;
}
.tagline {
  margin-top: 6px;
  font-size: 13px;
  color: var(--text-muted);
  letter-spacing: 0.15em;
  text-transform: uppercase;
}

/* Step indicator */
.steps {
  display: flex;
  justify-content: center;
  gap: 8px;
  margin-bottom: 32px;
}
.step-dot {
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--bg-panel);
  border: 1px solid var(--border-subtle);
  border-radius: 100px;
  padding: 8px 18px 8px 8px;
  font-size: 13px;
  color: var(--text-muted);
  transition: all 0.4s var(--transition-smooth);
}
.step-dot.active {
  border-color: var(--accent-amber);
  color: var(--accent-gold);
  background: var(--bg-elevated);
  box-shadow: 0 0 12px rgba(212,168,83,0.1);
}
.step-dot.done {
  border-color: var(--accent-success);
  color: var(--accent-success);
}
.step-num {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--border-subtle);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  font-family: var(--font-mono);
  flex-shrink: 0;
  transition: background 0.4s var(--transition-smooth);
}
.step-dot.active .step-num {
  background: var(--accent-amber);
  color: var(--bg-deep);
}
.step-dot.done .step-num {
  background: var(--accent-success);
  color: var(--bg-deep);
}

/* Card */
.card {
  background: var(--bg-panel);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-cinematic);
  overflow: hidden;
}
.slide-in {
  animation: slideIn 0.5s var(--transition-smooth);
}
@keyframes slideIn {
  from { opacity: 0; transform: translateY(16px); }
  to { opacity: 1; transform: translateY(0); }
}
.card-header {
  padding: 28px 32px 0;
}
.card-header h2 {
  font-family: var(--font-display);
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
}
.card-header p {
  margin-top: 4px;
  font-size: 13px;
  color: var(--text-secondary);
}
.card-header strong {
  color: var(--text-primary);
}
.card-body {
  padding: 24px 32px 32px;
}
.link {
  background: none;
  color: var(--accent-amber);
  font-size: 13px;
  text-decoration: underline;
  text-underline-offset: 3px;
}
.link:hover { color: var(--accent-gold); }

/* Fields */
.field {
  display: block;
  margin-bottom: 20px;
}
.field-label {
  display: block;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  margin-bottom: 8px;
}
.field input, .field textarea {
  width: 100%;
  resize: vertical;
}

/* Buttons */
.btn-primary {
  background: var(--accent-amber);
  color: var(--bg-deep);
  font-weight: 600;
  font-size: 14px;
  padding: 12px 28px;
  border-radius: var(--radius-md);
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.btn-primary:hover:not(:disabled) {
  background: var(--accent-gold);
  box-shadow: 0 4px 20px rgba(212,168,83,0.3);
}
.btn-outline {
  display: inline-flex;
  align-items: center;
  padding: 10px 22px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  color: var(--text-secondary);
  font-size: 13px;
  background: transparent;
}
.btn-outline:hover {
  border-color: var(--accent-amber);
  color: var(--accent-amber);
}

/* Dropzone */
.dropzone {
  border: 2px dashed var(--border-subtle);
  border-radius: var(--radius-md);
  padding: 40px 20px;
  text-align: center;
  transition: all 0.3s var(--transition-smooth);
}
.dropzone.over {
  border-color: var(--accent-amber);
  background: rgba(212,168,83,0.04);
}
.dropzone-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}
.dropzone-icon {
  font-size: 36px;
  color: var(--text-muted);
  transform: rotate(-45deg);
}
.dropzone p {
  color: var(--text-secondary);
  font-size: 14px;
}
.dropzone-divider {
  font-size: 12px;
  color: var(--text-muted);
  text-transform: uppercase;
}

.uploading-hint {
  text-align: center;
  margin-top: 12px;
  font-size: 13px;
  color: var(--accent-amber);
}

/* Material list */
.material-list {
  margin-top: 20px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.material-row {
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
  font-size: 13px;
}
.mat-type { font-size: 16px; flex-shrink: 0; }
.mat-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-primary);
}
.mat-meta {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-muted);
  white-space: nowrap;
}
.mat-remove {
  background: none;
  color: var(--text-muted);
  font-size: 18px;
  padding: 0 4px;
  line-height: 1;
}
.mat-remove:hover { color: var(--accent-error); }

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
}

/* Progress */
.generate-view {
  display: flex;
  flex-direction: column;
  align-items: center;
}
.progress-section {
  width: 100%;
  text-align: center;
  padding: 20px 0;
}

/* Film strip progress */
.film-progress {
  display: flex;
  align-items: center;
  gap: 16px;
  width: 100%;
}
.film-track {
  flex: 1;
  height: 8px;
  background: var(--bg-deep);
  border-radius: 4px;
  position: relative;
  overflow: hidden;
}
.film-fill {
  height: 100%;
  border-radius: 4px;
  background: linear-gradient(90deg, var(--accent-burn), var(--accent-amber), var(--accent-gold));
  transition: width 0.6s var(--transition-smooth);
  position: relative;
}
.film-fill.error {
  background: var(--accent-error);
}
.film-fill.done {
  background: linear-gradient(90deg, var(--accent-success), #7cc98e);
}
.film-perforations {
  position: absolute;
  inset: 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 4px;
}
.film-perforations span {
  width: 4px;
  height: 4px;
  background: var(--bg-panel);
  border-radius: 50%;
}
.film-pct {
  font-family: var(--font-mono);
  font-size: 14px;
  color: var(--accent-amber);
  min-width: 40px;
  text-align: right;
}

.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-top: 20px;
  font-size: 14px;
  color: var(--text-secondary);
  background: var(--bg-elevated);
  padding: 8px 18px;
  border-radius: 100px;
  border: 1px solid var(--border-subtle);
}
.status-badge.done {
  border-color: var(--accent-success);
  color: var(--accent-success);
}
.status-badge.error {
  border-color: var(--accent-error);
  color: var(--accent-error);
}
.status-icon { font-size: 14px; }
.status-msg {
  margin-top: 10px;
  font-size: 13px;
  color: var(--text-muted);
}
.status-msg.detail {
  color: var(--accent-error);
  margin-top: 4px;
}
.ws-status {
  margin-top: 16px;
  font-size: 11px;
  color: var(--text-muted);
  font-family: var(--font-mono);
}
.ws-status.on { color: var(--accent-success); }
.ws-status::before {
  content: '';
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-muted);
  margin-right: 6px;
  vertical-align: middle;
}
.ws-status.on::before { background: var(--accent-success); }

/* Spinner */
.spinner {
  width: 18px;
  height: 18px;
  border: 2px solid rgba(212,168,83,0.2);
  border-top-color: var(--accent-amber);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  display: inline-block;
}
.spinner.sm { width: 14px; height: 14px; }
@keyframes spin { to { transform: rotate(360deg); } }

.msg-error {
  margin-top: 12px;
  font-size: 13px;
  color: var(--accent-error);
}

/* Footer */
.footer {
  text-align: center;
  padding: 48px 0 24px;
  font-size: 11px;
  color: var(--text-muted);
  font-family: var(--font-mono);
  letter-spacing: 0.05em;
}
.footer-divider { margin: 0 8px; color: var(--border-subtle); }

/* Responsive */
@media (max-width: 520px) {
  .app-shell { padding: 20px 16px 60px; }
  .card-header, .card-body { padding-left: 20px; padding-right: 20px; }
  .logo h1 { font-size: 26px; }
  .steps { gap: 4px; }
  .step-dot { padding: 6px 12px 6px 6px; font-size: 11px; gap: 4px; }
  .step-label { display: none; }
  .actions { flex-direction: column; }
}
</style>
