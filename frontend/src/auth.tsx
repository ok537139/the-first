import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { authApi } from './api'
import type { User } from './types'

interface AuthState {
  user?: User
  token?: string
  loading: boolean
  login: (phone: string, code: string) => Promise<void>
  logout: () => Promise<void>
  refresh: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | undefined>(() => localStorage.getItem('hmdp-token') || undefined)
  const [user, setUser] = useState<User>()
  const [loading, setLoading] = useState(Boolean(token))

  const refresh = useCallback(async () => {
    if (!localStorage.getItem('hmdp-token')) {
      setUser(undefined)
      setLoading(false)
      return
    }
    try {
      setUser(await authApi.me())
    } catch {
      localStorage.removeItem('hmdp-token')
      setToken(undefined)
      setUser(undefined)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void refresh() }, [refresh, token])

  const login = useCallback(async (phone: string, code: string) => {
    const nextToken = await authApi.login(phone, code)
    localStorage.setItem('hmdp-token', nextToken)
    setToken(nextToken)
  }, [])

  const logout = useCallback(async () => {
    try { await authApi.logout() } finally {
      localStorage.removeItem('hmdp-token')
      setToken(undefined)
      setUser(undefined)
    }
  }, [])

  const value = useMemo(() => ({ user, token, loading, login, logout, refresh }), [user, token, loading, login, logout, refresh])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth 必须在 AuthProvider 中使用')
  return context
}
