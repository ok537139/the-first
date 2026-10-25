import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight, Clock3, LocateFixed, MapPin, Search, ShoppingBag, Star, Store, TicketPercent } from 'lucide-react'
import { adminApi, discoveryApi, firstImage } from '../api'
import { Modal } from '../components/Modal'
import type { SeckillResult, Shop } from '../types'

export function DiscoverPage() {
  const [typeId, setTypeId] = useState(1)
  const [search, setSearch] = useState('')
  const [current, setCurrent] = useState(1)
  const [selectedId, setSelectedId] = useState<number>()
  const [coordinates, setCoordinates] = useState<{ x: number; y: number }>()
  const [notice, setNotice] = useState('')
  const queryClient = useQueryClient()
  const types = useQuery({ queryKey: ['shop-types'], queryFn: discoveryApi.types })
  const shops = useQuery({
    queryKey: ['shops', typeId, search, current, coordinates],
    queryFn: () => search.trim() ? discoveryApi.shopsByName(search.trim(), current) : discoveryApi.shopsByType(typeId, current, coordinates),
  })
  const detail = useQuery({ queryKey: ['shop', selectedId], queryFn: () => discoveryApi.shop(selectedId!), enabled: Boolean(selectedId) })
  const vouchers = useQuery({ queryKey: ['vouchers', selectedId], queryFn: () => discoveryApi.vouchers(selectedId!), enabled: Boolean(selectedId) })
  const activeType = useMemo(() => types.data?.find((item) => item.id === typeId), [types.data, typeId])
  const seckill = useMutation({
    mutationFn: async (voucherId: number) => {
      // 下单接口只代表抢到资格，订单由后端异步创建，轮询几次拿到最终结果
      await adminApi.seckill(voucherId)
      for (let attempt = 0; attempt < 10; attempt++) {
        await new Promise((resolve) => setTimeout(resolve, 500))
        const result = await adminApi.seckillResult(voucherId)
        if (result.status !== 'PROCESSING') return result
      }
      return { status: 'PROCESSING' } as SeckillResult
    },
    onSuccess: (result) => { setNotice(seckillMessage(result)); void queryClient.invalidateQueries({ queryKey: ['vouchers', selectedId] }) },
    onError: (error) => setNotice(error.message),
  })

  function useLocation() {
    if (!navigator.geolocation) return setNotice('当前浏览器不支持定位')
    navigator.geolocation.getCurrentPosition(
      (position) => { setCoordinates({ x: position.coords.longitude, y: position.coords.latitude }); setSearch(''); setCurrent(1); setNotice('已按当前位置显示 5 公里内商户') },
      () => setNotice('未能获取位置，请检查浏览器定位权限'),
    )
  }

  return <>
    <section className="welcome" id="discover">
      <div>
        <p className="eyebrow"><MapPin size={16} /> 杭州 · 拱墅</p>
        <h1>今天，去哪里<br />吃点好的？</h1>
        <p className="intro">从街角茶餐厅到运河边的深夜食堂，真实口碑帮你做决定。</p>
      </div>
      <div className="search-panel">
        <label htmlFor="shop-search">搜索商户</label>
        <div className="search-box"><Search size={21} /><input id="shop-search" value={search} onChange={(event) => { setSearch(event.target.value); setCurrent(1); setCoordinates(undefined) }} placeholder="输入店名关键词" /></div>
        <button className={`location-button ${coordinates ? 'active' : ''}`} onClick={useLocation}><LocateFixed size={17} />{coordinates ? '已开启附近优先' : '按我的位置查找附近好店'}</button>
        <div className="quick-stats"><span><b>22</b> 精选商户</span><span><b>10</b> 生活分类</span><span><b>4.7</b> 平均口碑</span></div>
      </div>
    </section>

    <section className="content-section">
      <div className="section-heading"><div><p className="eyebrow">EXPLORE BY MOOD</p><h2>按心情探索</h2></div><span>{search ? `“${search}”` : activeType?.name || '全部'} · 第 {current} 页</span></div>
      <div className="type-row" role="tablist" aria-label="商户分类">
        {types.isLoading && <span className="muted">正在加载分类…</span>}
        {types.data?.map((type) => <button key={type.id} className={type.id === typeId && !search ? 'selected' : ''} onClick={() => { setTypeId(type.id); setSearch(''); setCoordinates(undefined); setCurrent(1) }}><Store size={18} />{type.name}</button>)}
      </div>
      {notice && <div className="notice info" onClick={() => setNotice('')}>{notice}</div>}
      {shops.isError && <div className="notice error">{shops.error.message}</div>}
      <div className="shop-grid">
        {shops.isLoading && Array.from({ length: 4 }).map((_, index) => <div className="shop-card skeleton" key={index} />)}
        {shops.data?.map((shop) => <ShopCard key={shop.id} shop={shop} typeName={types.data?.find((type) => type.id === shop.typeId)?.name} onOpen={() => setSelectedId(shop.id)} />)}
      </div>
      {!shops.isLoading && shops.data?.length === 0 && <div className="empty"><Search size={28} /><h3>还没有找到合适的店</h3><p>换个关键词、分类或页码试试看。</p></div>}
      <div className="pager"><button disabled={current === 1} onClick={() => setCurrent((page) => Math.max(1, page - 1))}><ChevronLeft size={18} />上一页</button><span>第 {current} 页</span><button disabled={(shops.data?.length || 0) < (search ? 10 : 5)} onClick={() => setCurrent((page) => page + 1)}>下一页<ChevronRight size={18} /></button></div>
    </section>

    {selectedId && <Modal title="商户详情" wide onClose={() => setSelectedId(undefined)}>
      {detail.isLoading ? <div className="empty">正在加载商户详情…</div> : detail.data && <div className="detail-layout">
        <div className="detail-cover">{firstImage(detail.data.images) ? <img src={firstImage(detail.data.images)} alt={detail.data.name} /> : <Store />}</div>
        <div className="detail-copy"><p className="eyebrow">LOCAL PICK · #{detail.data.id}</p><h3>{detail.data.name}</h3><p className="detail-address"><MapPin size={17} />{detail.data.address}</p><div className="metric-row"><span><Star fill="currentColor" />{(detail.data.score / 10).toFixed(1)} 分</span><span><ShoppingBag />已售 {detail.data.sold}</span><span><Clock3 />{detail.data.openHours}</span></div><p>人均消费 <b>¥{detail.data.avgPrice}</b> · {detail.data.area}</p></div>
      </div>}
      <div className="voucher-section"><div className="subheading"><TicketPercent /><div><h3>本店优惠</h3><p>普通券与限时秒杀券</p></div></div>
        {vouchers.isLoading && <p className="muted">优惠加载中…</p>}
        <div className="voucher-list">{vouchers.data?.map((voucher) => <article className="voucher-card" key={voucher.id}><div className="voucher-price"><small>¥</small>{(voucher.payValue / 100).toFixed(0)}<span>抵 ¥{(voucher.actualValue / 100).toFixed(0)}</span></div><div><h4>{voucher.title}</h4><p>{voucher.subTitle}</p><small>{voucher.rules}</small></div><button disabled={voucher.type !== 1 || seckill.isPending} onClick={() => seckill.mutate(voucher.id)}>{voucher.type === 1 ? '立即抢购' : '到店使用'}</button></article>)}</div>
        {!vouchers.isLoading && vouchers.data?.length === 0 && <div className="empty compact">本店暂时没有优惠券</div>}
      </div>
    </Modal>}
  </>
}

function seckillMessage(result: SeckillResult) {
  if (result.status === 'SUCCESS') return `抢购成功，订单号 ${result.orderId}`
  if (result.status === 'PROCESSING') return '已抢到资格，订单还在处理中，请稍后再查看'
  return '下单失败，资格已退回，可以重新抢购'
}

function ShopCard({ shop, typeName, onOpen }: { shop: Shop; typeName?: string; onOpen: () => void }) {
  const image = firstImage(shop.images)
  return <article className="shop-card clickable" onClick={onOpen}>
    <div className="shop-image">{image ? <img src={image} alt="" /> : <Store size={42} />}<span>{shop.distance != null ? `${shop.distance.toFixed(0)} m` : shop.area}</span></div>
    <div className="shop-body"><p className="shop-kicker">{typeName || '本地好店'}</p><h3>{shop.name}</h3><p className="address"><MapPin size={15} />{shop.address}</p><div className="shop-meta"><span className="rating"><Star size={15} fill="currentColor" /> {(shop.score / 10).toFixed(1)}</span><span>人均 ¥{shop.avgPrice}</span><span>已售 {shop.sold}</span></div></div>
  </article>
}
