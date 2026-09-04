import { useEffect, useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CalendarCheck, Flame, LogOut, Search, ShieldCheck, UserCheck, UserPlus, Users } from 'lucide-react'
import { authApi, followApi, mediaUrl } from '../api'
import { useAuth } from '../auth'

export function ProfilePage() {
  const auth = useAuth()
  const [phone, setPhone] = useState('13686869696')
  const [code, setCode] = useState('')
  const [targetId, setTargetId] = useState(2)
  const [notice, setNotice] = useState('')
  const queryClient = useQueryClient()
  const info = useQuery({ queryKey: ['user-info', auth.user?.id], queryFn: () => authApi.info(auth.user!.id), enabled: Boolean(auth.user) })
  const targetUser = useQuery({ queryKey: ['target-user', targetId], queryFn: () => authApi.user(targetId), enabled: Boolean(auth.user && targetId) })
  const targetInfo = useQuery({ queryKey: ['target-info', targetId], queryFn: () => authApi.info(targetId), enabled: Boolean(auth.user && targetId) })
  const followStatus = useQuery({ queryKey: ['follow-status', targetId], queryFn: () => followApi.status(targetId), enabled: Boolean(auth.user && targetId && targetId !== auth.user.id) })
  const common = useQuery({ queryKey: ['common-follows', targetId], queryFn: () => followApi.commons(targetId), enabled: false })
  const signCount = useQuery({ queryKey: ['sign-count', auth.user?.id], queryFn: authApi.signCount, enabled: Boolean(auth.user) })
  const sendCode = useMutation({
    mutationFn: authApi.sendCode,
    onSuccess: (demoCode) => { if (demoCode) setCode(demoCode); setNotice(demoCode ? `验证码已生成并自动填写：${demoCode}` : '验证码已生成，请查看后端日志') },
    onError: (error) => setNotice(error.message),
  })
  const sign = useMutation({
    mutationFn: authApi.sign,
    onSuccess: () => { setNotice('今日签到成功'); void queryClient.invalidateQueries({ queryKey: ['sign-count'] }) },
    onError: (error) => setNotice(error.message),
  })
  const follow = useMutation({
    mutationFn: (isFollow: boolean) => followApi.set(targetId, isFollow),
    onSuccess: () => { setNotice(followStatus.data ? '已取消关注' : '关注成功'); void queryClient.invalidateQueries({ queryKey: ['follow-status', targetId] }) },
    onError: (error) => setNotice(error.message),
  })

  useEffect(() => { if (auth.user && targetId === auth.user.id) setTargetId(auth.user.id === 1 ? 2 : 1) }, [auth.user, targetId])

  async function submitLogin(event: FormEvent) {
    event.preventDefault()
    try { await auth.login(phone, code); setNotice('登录成功，正在加载个人资料') } catch (error) { setNotice(error instanceof Error ? error.message : '登录失败') }
  }

  if (!auth.user) return <section className="page-section login-page">
    <div className="login-story"><p className="eyebrow">WELCOME BACK</p><h1>登录，继续收藏<br />这座城市的烟火。</h1><p>验证码会保存在 Redis；本地演示环境会自动回填，方便直接体验所有登录后功能。</p><div className="trust-row"><span><ShieldCheck />Token 安全登录</span><span><Flame />连续签到</span><span><Users />关注好友</span></div></div>
    <form className="login-card" onSubmit={(event) => void submitLogin(event)}><h2>手机号登录</h2><p>新手机号首次验证后会自动注册</p><label className="field"><span>手机号</span><input inputMode="tel" maxLength={11} value={phone} onChange={(event) => setPhone(event.target.value)} /></label><label className="field"><span>验证码</span><div className="code-row"><input inputMode="numeric" maxLength={6} value={code} onChange={(event) => setCode(event.target.value)} placeholder="6 位验证码" /><button type="button" onClick={() => sendCode.mutate(phone)} disabled={sendCode.isPending}>获取验证码</button></div></label>{notice && <div className="notice info">{notice}</div>}<button className="primary-button full" type="submit">登录 / 注册</button><small>推荐体验账号：13686869696</small></form>
  </section>

  return <section className="page-section profile-page">
    <div className="profile-header">
      <span className="profile-avatar">{auth.user.icon ? <img src={mediaUrl(auth.user.icon)} alt="" /> : auth.user.nickName.slice(0, 1)}</span>
      <div><p className="eyebrow">MEMBER #{auth.user.id}</p><h1>{auth.user.nickName}</h1><p>{info.data?.introduce || '还没有填写个人介绍'}</p></div>
      <button className="outline-button" onClick={() => void auth.logout()}><LogOut size={17} />退出登录</button>
    </div>
    {notice && <div className="notice info" onClick={() => setNotice('')}>{notice}</div>}
    <div className="profile-grid">
      <div className="profile-main">
        <div className="stats-grid"><article><small>城市</small><strong>{info.data?.city || '杭州'}</strong></article><article><small>粉丝</small><strong>{info.data?.fans || 0}</strong></article><article><small>关注</small><strong>{info.data?.followee || 0}</strong></article><article><small>积分</small><strong>{info.data?.credits || 0}</strong></article></div>
        <div className="panel social-lab"><div className="subheading"><Search /><div><h3>用户与关注实验室</h3><p>查询用户资料、关注状态和共同关注</p></div></div><div className="target-search"><label>用户 ID<input type="number" min="1" value={targetId} onChange={(event) => setTargetId(Number(event.target.value))} /></label><button className="outline-button" onClick={() => void common.refetch()}><Users size={16} />查询共同关注</button></div>{targetUser.data ? <div className="target-user"><span className="avatar">{targetUser.data.nickName.slice(0, 1)}</span><div><strong>{targetUser.data.nickName}</strong><p>{targetInfo.data?.introduce || `用户 #${targetUser.data.id}`}</p></div><button className="primary-button small" onClick={() => follow.mutate(!followStatus.data)}><UserPlus size={16} />{followStatus.data ? '取消关注' : '关注'}</button></div> : targetUser.isFetched && <div className="empty compact">没有找到该用户</div>}{common.data && <div className="common-list"><h4>共同关注</h4>{common.data.length ? common.data.map((item) => <span key={item.id}><UserCheck />{item.nickName}</span>) : <small>暂时没有共同关注</small>}</div>}</div>
      </div>
      <aside className="profile-side"><div className="sign-card"><CalendarCheck /><p>本月连续签到</p><strong>{signCount.data || 0}<small> 天</small></strong><button onClick={() => sign.mutate()} disabled={sign.isPending}>今日签到</button></div><div className="panel profile-details"><h3>个人资料</h3><dl><div><dt>生日</dt><dd>{info.data?.birthday || '未填写'}</dd></div><div><dt>等级</dt><dd>Lv.{info.data?.level ? 1 : 0}</dd></div><div><dt>性别</dt><dd>{info.data?.gender ? '女' : '男'}</dd></div></dl></div></aside>
    </div>
  </section>
}
