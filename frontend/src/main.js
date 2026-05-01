import { createApp } from 'vue'
import App from './App.vue'
import './style.css'

const app = createApp(App)

app.config.errorHandler = (err, instance, info) => {
  console.error('Vue Error:', err)
  const el = document.getElementById('app')
  if (el) {
    el.innerHTML = `<div style="display:flex;align-items:center;justify-content:center;height:100vh;flex-direction:column;gap:16px;color:#c44e3d;font-family:sans-serif;background:#0a0a0c;padding:40px;"><div style="font-size:18px;font-weight:bold;">应用加载失败</div><div style="font-size:14px;color:#8b8578;max-width:500px;text-align:center;word-break:break-all;">${err.message || err}</div></div>`
  }
}

app.mount('#app')
