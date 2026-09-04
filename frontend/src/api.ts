import type {
  ApiResult,
  Blog,
  BlogComment,
  ScrollResult,
  Shop,
  ShopPayload,
  ShopType,
  User,
  UserInfo,
  Voucher,
  VoucherPayload,
} from './types'

const API_PREFIX = import.meta.env.VITE_API_PREFIX || '/api'

export async function apiRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const token = localStorage.getItem('hmdp-token')
  const headers = new Headers(init?.headers)
  if (token) headers.set('authorization', token)
  if (init?.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  const response = await fetch(`${API_PREFIX}${path}`, { ...init, headers })
  if (response.status === 401) throw new Error('请先登录后再操作')
  const result = (await response.json()) as ApiResult<T>
  if (!result.success) throw new Error(result.errorMsg || '操作失败')
  return result.data
}

export const discoveryApi = {
  types: () => apiRequest<ShopType[]>('/shop-type/list'),
  shopsByType: (typeId: number, current = 1, coordinates?: { x: number; y: number }) => {
    const location = coordinates ? `&x=${coordinates.x}&y=${coordinates.y}` : ''
    return apiRequest<Shop[]>(`/shop/of/type?typeId=${typeId}&current=${current}${location}`)
  },
  shopsByName: (name: string, current = 1) =>
    apiRequest<Shop[]>(`/shop/of/name?name=${encodeURIComponent(name)}&current=${current}`),
  shop: (id: number) => apiRequest<Shop>(`/shop/${id}`),
  vouchers: (shopId: number) => apiRequest<Voucher[]>(`/voucher/list/${shopId}`),
}

export const authApi = {
  sendCode: (phone: string) => apiRequest<string | undefined>(`/user/code?phone=${encodeURIComponent(phone)}`, { method: 'POST' }),
  login: (phone: string, code: string) => apiRequest<string>('/user/login', { method: 'POST', body: JSON.stringify({ phone, code }) }),
  logout: () => apiRequest<void>('/user/logout', { method: 'POST' }),
  me: () => apiRequest<User>('/user/me'),
  user: (id: number) => apiRequest<User | undefined>(`/user/${id}`),
  info: (id: number) => apiRequest<UserInfo | undefined>(`/user/info/${id}`),
  sign: () => apiRequest<void>('/user/sign', { method: 'POST' }),
  signCount: () => apiRequest<number>('/user/sign/count'),
}

export const blogApi = {
  hot: (current = 1) => apiRequest<Blog[]>(`/blog/hot?current=${current}`),
  detail: (id: number) => apiRequest<Blog>(`/blog/${id}`),
  likes: (id: number) => apiRequest<User[]>(`/blog/likes/${id}`),
  mine: (current = 1) => apiRequest<Blog[]>(`/blog/of/me?current=${current}`),
  byUser: (id: number, current = 1) => apiRequest<Blog[]>(`/blog/of/user?id=${id}&current=${current}`),
  feed: (lastId: number, offset = 0) => apiRequest<ScrollResult<Blog> | undefined>(`/blog/of/follow?lastId=${lastId}&offset=${offset}`),
  create: (blog: Partial<Blog>) => apiRequest<number>('/blog', { method: 'POST', body: JSON.stringify(blog) }),
  like: (id: number) => apiRequest<void>(`/blog/like/${id}`, { method: 'PUT' }),
}

export const commentApi = {
  list: (blogId: number, current = 1) => apiRequest<BlogComment[]>(`/blog-comments/list?blogId=${blogId}&current=${current}`),
  create: (payload: { blogId: number; content: string; parentId?: number; answerId?: number }) =>
    apiRequest<number>('/blog-comments', { method: 'POST', body: JSON.stringify(payload) }),
  like: (id: number) => apiRequest<void>(`/blog-comments/like/${id}`, { method: 'PUT' }),
  remove: (id: number) => apiRequest<void>(`/blog-comments/${id}`, { method: 'DELETE' }),
}

export const followApi = {
  set: (id: number, isFollow: boolean) => apiRequest<void>(`/follow/${id}/${isFollow}`, { method: 'PUT' }),
  status: (id: number) => apiRequest<boolean>(`/follow/or/not/${id}`),
  commons: (id: number) => apiRequest<User[]>(`/follow/common/${id}`),
}

export const uploadApi = {
  image: async (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return apiRequest<string>('/upload/blog', { method: 'POST', body: form })
  },
  remove: (name: string) => apiRequest<void>(`/upload/blog/delete?name=${encodeURIComponent(name)}`),
}

export const adminApi = {
  createShop: (shop: ShopPayload) => apiRequest<number>('/shop', { method: 'POST', body: JSON.stringify(shop) }),
  updateShop: (shop: ShopPayload) => apiRequest<void>('/shop', { method: 'PUT', body: JSON.stringify(shop) }),
  addVoucher: (voucher: VoucherPayload) => apiRequest<number>('/voucher', { method: 'POST', body: JSON.stringify(voucher) }),
  addSeckillVoucher: (voucher: VoucherPayload) => apiRequest<number>('/voucher/seckill', { method: 'POST', body: JSON.stringify(voucher) }),
  seckill: (voucherId: number) => apiRequest<number>(`/voucher-order/seckill/${voucherId}`, { method: 'POST' }),
}

export function mediaUrl(path?: string) {
  if (!path) return ''
  if (/^https?:\/\//.test(path)) return path
  if (path.startsWith('/imgs/')) return `${API_PREFIX}${path}`
  if (path.startsWith('/blogs/')) return `${API_PREFIX}/imgs${path}`
  return path
}

export function firstImage(images?: string) {
  const path = images?.split(',').find(Boolean)
  return mediaUrl(path)
}
