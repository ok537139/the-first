import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BadgeCheck, Database, Edit3, PlusCircle, RefreshCw, Store, TicketPercent, Zap } from 'lucide-react'
import { adminApi, discoveryApi } from '../api'
import type { ShopPayload, VoucherPayload } from '../types'

const emptyShop: ShopPayload = { name: '', typeId: 1, images: '', area: '', address: '', x: 120.15, y: 30.32, avgPrice: 80, sold: 0, comments: 0, score: 45, openHours: '10:00-22:00' }
const emptyVoucher: VoucherPayload = { shopId: 1, title: '', subTitle: '', rules: '', payValue: 1990, actualValue: 5000, type: 0, status: 1, stock: 50, beginTime: localDateTime(-1), endTime: localDateTime(30) }

export function StudioPage() {
  const [tab, setTab] = useState<'shop' | 'voucher' | 'coverage'>('shop')
  const [shop, setShop] = useState<ShopPayload>(emptyShop)
  const [voucher, setVoucher] = useState<VoucherPayload>(emptyVoucher)
  const [lookupId, setLookupId] = useState(1)
  const [voucherShopId, setVoucherShopId] = useState(1)
  const [notice, setNotice] = useState('')
  const queryClient = useQueryClient()
  const types = useQuery({ queryKey: ['shop-types'], queryFn: discoveryApi.types })
  const vouchers = useQuery({ queryKey: ['vouchers', voucherShopId], queryFn: () => discoveryApi.vouchers(voucherShopId) })
  const saveShop = useMutation({
    mutationFn: async (payload: ShopPayload) => {
      if (payload.id) {
        await adminApi.updateShop(payload)
        return payload.id
      }
      return adminApi.createShop(payload)
    },
    onSuccess: (id) => { setNotice(shop.id ? `商户 #${shop.id} 已更新` : `商户已创建，编号 ${id}`); void queryClient.invalidateQueries({ queryKey: ['shops'] }) },
    onError: (error) => setNotice(error.message),
  })
  const saveVoucher = useMutation({
    mutationFn: (payload: VoucherPayload) => payload.type === 1 ? adminApi.addSeckillVoucher(payload) : adminApi.addVoucher(payload),
    onSuccess: (id) => { setNotice(`优惠券已创建，编号 ${id}`); setVoucherShopId(voucher.shopId); void queryClient.invalidateQueries({ queryKey: ['vouchers'] }) },
    onError: (error) => setNotice(error.message),
  })
  const seckill = useMutation({ mutationFn: adminApi.seckill, onSuccess: (id) => setNotice(`秒杀请求成功，订单号 ${id}`), onError: (error) => setNotice(error.message) })

  async function loadShop() {
    try { setShop(await discoveryApi.shop(lookupId)); setNotice(`已载入商户 #${lookupId}，保存后将执行更新接口`) } catch (error) { setNotice(error instanceof Error ? error.message : '加载失败') }
  }

  function submitShop(event: FormEvent) { event.preventDefault(); saveShop.mutate(shop) }
  function submitVoucher(event: FormEvent) { event.preventDefault(); saveVoucher.mutate(voucher) }

  return <section className="page-section studio-page">
    <div className="page-title"><div><p className="eyebrow">OPERATIONS STUDIO</p><h1>商户与优惠，<br />在这里统一管理。</h1></div><p>面向本地演示环境的完整接口工作台。<br />所有操作会直接写入 MySQL 与 Redis。</p></div>
    <div className="workspace-bar"><div className="segmented"><button className={tab === 'shop' ? 'active' : ''} onClick={() => setTab('shop')}><Store size={16} />商户管理</button><button className={tab === 'voucher' ? 'active' : ''} onClick={() => setTab('voucher')}><TicketPercent size={16} />优惠券</button><button className={tab === 'coverage' ? 'active' : ''} onClick={() => setTab('coverage')}><Database size={16} />接口覆盖</button></div></div>
    {notice && <div className="notice info" onClick={() => setNotice('')}>{notice}</div>}

    {tab === 'shop' && <div className="studio-grid">
      <form className="panel form-panel" onSubmit={submitShop}><div className="subheading"><Edit3 /><div><h3>{shop.id ? `编辑商户 #${shop.id}` : '新增商户'}</h3><p>填写完整信息后保存到数据库</p></div></div><div className="form-grid"><Field label="商户名称"><input required value={shop.name} onChange={(event) => setShop({ ...shop, name: event.target.value })} /></Field><Field label="商户分类"><select value={shop.typeId} onChange={(event) => setShop({ ...shop, typeId: Number(event.target.value) })}>{types.data?.map((type) => <option value={type.id} key={type.id}>{type.name}</option>)}</select></Field><Field label="商圈"><input value={shop.area} onChange={(event) => setShop({ ...shop, area: event.target.value })} /></Field><Field label="人均价格"><input type="number" value={shop.avgPrice} onChange={(event) => setShop({ ...shop, avgPrice: Number(event.target.value) })} /></Field><Field label="详细地址" wide><input required value={shop.address} onChange={(event) => setShop({ ...shop, address: event.target.value })} /></Field><Field label="图片 URL（多个用逗号分隔）" wide><textarea rows={3} value={shop.images} onChange={(event) => setShop({ ...shop, images: event.target.value })} /></Field><Field label="经度 X"><input type="number" step="0.000001" value={shop.x} onChange={(event) => setShop({ ...shop, x: Number(event.target.value) })} /></Field><Field label="纬度 Y"><input type="number" step="0.000001" value={shop.y} onChange={(event) => setShop({ ...shop, y: Number(event.target.value) })} /></Field><Field label="评分（×10）"><input type="number" min="10" max="50" value={shop.score} onChange={(event) => setShop({ ...shop, score: Number(event.target.value) })} /></Field><Field label="营业时间"><input value={shop.openHours} onChange={(event) => setShop({ ...shop, openHours: event.target.value })} /></Field></div><div className="form-actions"><button type="button" className="outline-button" onClick={() => setShop(emptyShop)}><PlusCircle />切换为新增</button><button className="primary-button" disabled={saveShop.isPending}>{shop.id ? '保存更新' : '创建商户'}</button></div></form>
      <aside className="panel lookup-panel"><div className="subheading"><RefreshCw /><div><h3>载入已有商户</h3><p>调用详情接口后进入编辑模式</p></div></div><label className="field"><span>商户 ID</span><input type="number" min="1" value={lookupId} onChange={(event) => setLookupId(Number(event.target.value))} /></label><button className="outline-button full" onClick={() => void loadShop()}>载入商户</button><div className="studio-note"><BadgeCheck /><p><b>缓存一致性</b><br />更新商户时，后端会同步清理 Redis 店铺缓存。</p></div></aside>
    </div>}

    {tab === 'voucher' && <div className="studio-grid">
      <form className="panel form-panel" onSubmit={submitVoucher}><div className="subheading"><TicketPercent /><div><h3>新建{voucher.type === 1 ? '秒杀' : '普通'}优惠券</h3><p>金额单位为分，页面同时显示元</p></div></div><div className="form-grid"><Field label="所属商户 ID"><input type="number" min="1" value={voucher.shopId} onChange={(event) => setVoucher({ ...voucher, shopId: Number(event.target.value) })} /></Field><Field label="优惠类型"><select value={voucher.type} onChange={(event) => setVoucher({ ...voucher, type: Number(event.target.value) })}><option value={0}>普通券</option><option value={1}>秒杀券</option></select></Field><Field label="标题" wide><input required value={voucher.title} onChange={(event) => setVoucher({ ...voucher, title: event.target.value })} placeholder="例如：限时 50 元代金券" /></Field><Field label="副标题" wide><input value={voucher.subTitle} onChange={(event) => setVoucher({ ...voucher, subTitle: event.target.value })} /></Field><Field label="支付金额（分）"><input type="number" min="1" value={voucher.payValue} onChange={(event) => setVoucher({ ...voucher, payValue: Number(event.target.value) })} /></Field><Field label="抵扣金额（分）"><input type="number" min="1" value={voucher.actualValue} onChange={(event) => setVoucher({ ...voucher, actualValue: Number(event.target.value) })} /></Field><Field label="使用规则" wide><textarea rows={3} value={voucher.rules} onChange={(event) => setVoucher({ ...voucher, rules: event.target.value })} /></Field>{voucher.type === 1 && <><Field label="秒杀库存"><input type="number" min="1" value={voucher.stock} onChange={(event) => setVoucher({ ...voucher, stock: Number(event.target.value) })} /></Field><Field label="开始时间"><input type="datetime-local" value={voucher.beginTime?.slice(0, 16)} onChange={(event) => setVoucher({ ...voucher, beginTime: event.target.value })} /></Field><Field label="结束时间"><input type="datetime-local" value={voucher.endTime?.slice(0, 16)} onChange={(event) => setVoucher({ ...voucher, endTime: event.target.value })} /></Field></>}</div><div className="form-actions"><span className="price-preview">支付 ¥{(voucher.payValue / 100).toFixed(2)}，抵扣 ¥{(voucher.actualValue / 100).toFixed(2)}</span><button className="primary-button" disabled={saveVoucher.isPending}>创建优惠券</button></div></form>
      <aside className="panel lookup-panel"><div className="subheading"><Zap /><div><h3>优惠券查询与秒杀</h3><p>输入商户 ID 查看已上架优惠</p></div></div><label className="field"><span>商户 ID</span><input type="number" min="1" value={voucherShopId} onChange={(event) => setVoucherShopId(Number(event.target.value))} /></label><div className="mini-voucher-list">{vouchers.data?.map((item) => <article key={item.id}><div><strong>{item.title}</strong><small>#{item.id} · ¥{(item.payValue / 100).toFixed(2)}</small></div>{item.type === 1 && <button onClick={() => seckill.mutate(item.id)}>秒杀</button>}</article>)}{!vouchers.isFetching && vouchers.data?.length === 0 && <div className="empty compact">暂无优惠券</div>}</div></aside>
    </div>}

    {tab === 'coverage' && <EndpointCoverage />}
  </section>
}

function Field({ label, children, wide = false }: { label: string; children: React.ReactNode; wide?: boolean }) { return <label className={`field ${wide ? 'wide-field' : ''}`}><span>{label}</span>{children}</label> }

function EndpointCoverage() {
  const groups = [
    ['用户', '验证码、登录、登出、当前用户、用户/资料查询、签到、连续签到', '个人中心'],
    ['商户', '分类、按类型/距离查询、名称搜索、详情、新增、更新', '发现 / 管理台'],
    ['博客', '热门、详情、发布、点赞、点赞榜、我的、按用户、关注流', '社区 / 发布'],
    ['评论', '评论分页、发表、两级回复、点赞/取消、作者删除', '社区详情'],
    ['关注', '关注/取关、状态、共同关注', '社区 / 个人中心'],
    ['优惠券', '店铺券列表、新增普通券、新增秒杀券、秒杀下单、下单结果查询', '发现 / 管理台'],
    ['上传', '博客图片上传与删除', '发布'],
  ]
  return <div className="coverage-grid">{groups.map(([name, endpoints, page]) => <article className="panel" key={name}><BadgeCheck /><div><h3>{name}</h3><p>{endpoints}</p><small>入口：{page}</small></div></article>)}<div className="coverage-summary panel"><strong>36 / 36</strong><span>现有可调用接口均有前端入口</span><small>评论已经支持查询、发表、回复、点赞和删除。</small></div></div>
}

function localDateTime(offsetDays: number) { const date = new Date(Date.now() + offsetDays * 86400000); date.setMinutes(date.getMinutes() - date.getTimezoneOffset()); return date.toISOString().slice(0, 16) }
