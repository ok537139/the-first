import { Compass, LayoutDashboard, NotebookPen, Sparkles, UserRound } from 'lucide-react'
import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../auth'

const links = [
  { to: '/', label: '发现', icon: Compass },
  { to: '/community', label: '社区', icon: Sparkles },
  { to: '/publish', label: '发布', icon: NotebookPen },
  { to: '/profile', label: '我的', icon: UserRound },
  { to: '/studio', label: '管理台', icon: LayoutDashboard },
]

export function Layout() {
  const { user } = useAuth()
  return <div className="app-shell">
    <header className="topbar">
      <NavLink className="brand" to="/" aria-label="好味杭州首页">
        <span className="brand-mark">好</span>
        <span><strong>好味杭州</strong><small>LOCAL TASTE GUIDE</small></span>
      </NavLink>
      <nav aria-label="主导航">
        {links.map(({ to, label, icon: Icon }) => <NavLink key={to} to={to} end={to === '/'} className={({ isActive }) => isActive ? 'active' : ''}><Icon size={18} /><span>{label}</span></NavLink>)}
      </nav>
      <NavLink className="user-chip" to="/profile">
        <span className="avatar">{user?.nickName?.slice(0, 1) || '客'}</span>
        <span>{user?.nickName || '登录'}</span>
      </NavLink>
    </header>
    <main><Outlet /></main>
    <footer><span>好味杭州 · 本地生活指南</span><span>数据来自 hm-dianping 后端服务</span></footer>
  </div>
}
