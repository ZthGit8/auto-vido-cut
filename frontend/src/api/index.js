const BASE = '/api'

async function request(url, options = {}) {
  const res = await fetch(`${BASE}${url}`, {
    headers: { 'Content-Type': 'application/json', ...options.headers },
    ...options,
  })
  if (!res.ok) {
    const body = await res.text()
    throw new Error(body || `请求失败 (${res.status})`)
  }
  return res.json()
}

export function createProject(name, promotionGoal) {
  return request('/projects', {
    method: 'POST',
    body: JSON.stringify({ name, promotionGoal }),
  })
}

export function uploadMaterial(projectId, file) {
  const form = new FormData()
  form.append('file', file)
  return fetch(`${BASE}/projects/${projectId}/materials`, {
    method: 'POST',
    body: form,
  }).then(res => {
    if (!res.ok) throw new Error(`上传失败 (${res.status})`)
    return res.json()
  })
}

export function startGenerate(projectId) {
  return request(`/generate/${projectId}`, { method: 'POST' })
}
