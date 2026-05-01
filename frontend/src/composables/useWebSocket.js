import { ref, onUnmounted } from 'vue'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'

export function useWebSocket() {
  const progress = ref(null)
  const connected = ref(false)
  let client = null

  function connect(projectId) {
    disconnect()

    client = new Client({
      webSocketFactory: () => new SockJS('/ws'),
      reconnectDelay: 3000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      onConnect: () => {
        connected.value = true
        client.subscribe(`/topic/progress/${projectId}`, msg => {
          try {
            progress.value = JSON.parse(msg.body)
          } catch {
            // ignore malformed messages
          }
        })
      },
      onDisconnect: () => {
        connected.value = false
      },
      onStompError: frame => {
        console.error('STOMP error:', frame.headers.message)
        connected.value = false
      },
    })

    client.activate()
  }

  function disconnect() {
    if (client) {
      try { client.deactivate() } catch {}
      client = null
    }
    connected.value = false
    progress.value = null
  }

  onUnmounted(disconnect)

  return { progress, connected, connect, disconnect }
}
