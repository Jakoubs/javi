import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const tournamentUrl = (env.TOURNAMENT_URL || 'https://tournament.staging.maichess.berger-software.com').replace(/\/$/, '')

  return {
    plugins: [vue()],
    server: {
      port: 3000,
      proxy: {
        '/tournament-api/api': {
          target: `${tournamentUrl}/api`,
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/tournament-api\/api/, '')
        }
      }
    }
  }
})
