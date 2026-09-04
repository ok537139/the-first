import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BookOpenText, Heart, MessageCircle, Plus, Reply, Search, Send, Trash2, UserPlus, Users } from 'lucide-react'
import { Link } from 'react-router-dom'
import { blogApi, commentApi, firstImage, followApi, mediaUrl } from '../api'
import { useAuth } from '../auth'
import { Modal } from '../components/Modal'
import type { Blog, BlogComment } from '../types'

type Mode = 'hot' | 'feed' | 'mine' | 'user'

export function CommunityPage() {
  const { user } = useAuth()
  const [mode, setMode] = useState<Mode>('hot')
  const [current, setCurrent] = useState(1)
  const [userId, setUserId] = useState(2)
  const [selectedId, setSelectedId] = useState<number>()
  const [notice, setNotice] = useState('')
  const queryClient = useQueryClient()
  const blogs = useQuery({
    queryKey: ['blogs', mode, current, userId, user?.id],
    queryFn: async () => {
      if (mode === 'hot') return blogApi.hot(current)
      if (mode === 'mine') return blogApi.mine(current)
      if (mode === 'user') return blogApi.byUser(userId, current)
      const result = await blogApi.feed(Date.now(), 0)
      return result?.list || []
    },
    enabled: mode === 'hot' || mode === 'user' || Boolean(user),
  })
  const detail = useQuery({ queryKey: ['blog', selectedId], queryFn: () => blogApi.detail(selectedId!), enabled: Boolean(selectedId) })
  const likes = useQuery({ queryKey: ['blog-likes', selectedId], queryFn: () => blogApi.likes(selectedId!), enabled: Boolean(selectedId) })
  const followStatus = useQuery({ queryKey: ['follow-status', detail.data?.userId], queryFn: () => followApi.status(detail.data!.userId), enabled: Boolean(user && detail.data?.userId && user.id !== detail.data.userId) })
  const like = useMutation({
    mutationFn: blogApi.like,
    onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ['blogs'] }); void queryClient.invalidateQueries({ queryKey: ['blog', selectedId] }); void queryClient.invalidateQueries({ queryKey: ['blog-likes', selectedId] }) },
    onError: (error) => setNotice(error.message),
  })
  const follow = useMutation({
    mutationFn: ({ id, isFollow }: { id: number; isFollow: boolean }) => followApi.set(id, isFollow),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['follow-status'] }),
    onError: (error) => setNotice(error.message),
  })
  const title = useMemo(() => ({ hot: '城中热帖', feed: '关注动态', mine: '我的笔记', user: `用户 #${userId} 的笔记` })[mode], [mode, userId])

  function changeMode(next: Mode) {
    if ((next === 'feed' || next === 'mine') && !user) setNotice('登录后即可查看个人内容')
    setMode(next)
    setCurrent(1)
  }

  return <section className="page-section community-page">
    <div className="page-hero compact-hero"><div><p className="eyebrow">CITY STORIES</p><h1>这座城，<br />被认真记录着。</h1><p>看看大家最近发现了哪些值得去的地方。</p></div><Link className="primary-button light" to="/publish"><Plus size={18} />写一篇探店笔记</Link></div>
    <div className="workspace-bar">
      <div className="segmented">{([['hot', '热门'], ['feed', '关注'], ['mine', '我的'], ['user', '按用户']] as const).map(([value, label]) => <button key={value} className={mode === value ? 'active' : ''} onClick={() => changeMode(value)}>{label}</button>)}</div>
      {mode === 'user' && <label className="inline-search"><Search size={16} /><span>用户 ID</span><input type="number" min="1" value={userId} onChange={(event) => setUserId(Number(event.target.value))} /></label>}
    </div>
    {notice && <div className="notice info" onClick={() => setNotice('')}>{notice}</div>}
    <div className="section-heading"><div><p className="eyebrow">NOTEBOOK</p><h2>{title}</h2></div><span>第 {current} 页</span></div>
    {!blogs.isFetching && !blogs.data && mode !== 'hot' && mode !== 'user' && <LoginPrompt />}
    {blogs.isError && <div className="notice error">{blogs.error.message}</div>}
    <div className="blog-grid">
      {blogs.isLoading && Array.from({ length: 4 }).map((_, index) => <div className="blog-card skeleton" key={index} />)}
      {blogs.data?.map((blog) => <BlogCard blog={blog} key={blog.id} onOpen={() => setSelectedId(blog.id)} onLike={() => like.mutate(blog.id)} />)}
    </div>
    {!blogs.isLoading && blogs.data?.length === 0 && <div className="empty"><BookOpenText /><h3>这里还没有笔记</h3><p>关注一些用户，或者发布第一篇内容吧。</p></div>}
    {(mode === 'hot' || mode === 'mine' || mode === 'user') && <div className="pager"><button disabled={current === 1} onClick={() => setCurrent((value) => Math.max(1, value - 1))}>上一页</button><span>第 {current} 页</span><button disabled={(blogs.data?.length || 0) < 10} onClick={() => setCurrent((value) => value + 1)}>下一页</button></div>}

    {selectedId && <Modal title="探店笔记" wide onClose={() => setSelectedId(undefined)}>
      {detail.isLoading ? <div className="empty">笔记加载中…</div> : detail.data && <article className="blog-detail">
        <div className="blog-detail-image">{firstImage(detail.data.images) ? <img src={firstImage(detail.data.images)} alt="" /> : <BookOpenText />}</div>
        <div className="blog-detail-copy">
          <div className="author-row"><Avatar name={detail.data.name} icon={detail.data.icon} /><div><strong>{detail.data.name || `用户 ${detail.data.userId}`}</strong><small>作者 ID · {detail.data.userId}</small></div>{user && user.id !== detail.data.userId && <button className="outline-button" onClick={() => follow.mutate({ id: detail.data!.userId, isFollow: !followStatus.data })}><UserPlus size={16} />{followStatus.data ? '取消关注' : '关注'}</button>}</div>
          <p className="eyebrow">SHOP NOTE · #{detail.data.shopId}</p><h2>{detail.data.title}</h2><p className="blog-content">{detail.data.content}</p>
          <div className="detail-actions"><button className={detail.data.isLike ? 'liked' : ''} onClick={() => like.mutate(detail.data!.id)}><Heart fill={detail.data.isLike ? 'currentColor' : 'none'} />{detail.data.liked || 0} 人喜欢</button><span><MessageCircle />{detail.data.comments || 0} 条评论</span></div>
          <div className="liked-by"><h4><Users size={17} />最早点赞</h4><div>{likes.data?.length ? likes.data.map((item) => <span key={item.id}><Avatar name={item.nickName} icon={item.icon} />{item.nickName}</span>) : <small>还没有人点赞，成为第一个吧</small>}</div></div>
        </div>
      </article>}
      {detail.data && <CommentSection blogId={detail.data.id} />}
      {detail.isError && <div className="empty"><Users /><h3>登录后查看完整笔记</h3><p>详情、点赞榜和关注操作需要登录身份。</p><Link className="primary-button" to="/profile">去登录</Link></div>}
    </Modal>}
  </section>
}

function CommentSection({ blogId }: { blogId: number }) {
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const [content, setContent] = useState('')
  const [current, setCurrent] = useState(1)
  const [replying, setReplying] = useState<{ parentId: number; answerId: number; name: string }>()
  const [notice, setNotice] = useState('')
  const comments = useQuery({ queryKey: ['comments', blogId, current], queryFn: () => commentApi.list(blogId, current) })
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['comments', blogId] })
    void queryClient.invalidateQueries({ queryKey: ['blog', blogId] })
    void queryClient.invalidateQueries({ queryKey: ['blogs'] })
  }
  const create = useMutation({
    mutationFn: () => commentApi.create({ blogId, content, parentId: replying?.parentId, answerId: replying?.answerId }),
    onSuccess: () => { setContent(''); setReplying(undefined); setNotice('评论已发布'); refresh() },
    onError: (error) => setNotice(error.message),
  })
  const like = useMutation({ mutationFn: commentApi.like, onSuccess: refresh, onError: (error) => setNotice(error.message) })
  const remove = useMutation({ mutationFn: commentApi.remove, onSuccess: () => { setNotice('评论已删除'); refresh() }, onError: (error) => setNotice(error.message) })

  function submitComment() {
    if (!content.trim()) return setNotice('请先输入评论内容')
    create.mutate()
  }

  function requestDelete(id: number) {
    if (window.confirm('确定删除这条评论吗？一级评论下的回复也会一起删除。')) remove.mutate(id)
  }

  return <section className="comments-section">
    <div className="subheading"><MessageCircle /><div><h3>评论与回复</h3><p>说说你的真实体验</p></div></div>
    {user ? <div className="comment-composer">{replying && <div className="replying-bar">正在回复 <b>{replying.name}</b><button onClick={() => setReplying(undefined)}>取消</button></div>}<textarea rows={3} maxLength={255} value={content} onChange={(event) => setContent(event.target.value)} placeholder={replying ? `回复 ${replying.name}…` : '友善交流，分享有用的信息…'} /><div><small>{content.length}/255</small><button className="primary-button small" onClick={submitComment} disabled={create.isPending}><Send size={15} />发布</button></div></div> : <div className="empty compact">登录后可以发表评论</div>}
    {notice && <div className="comment-notice" onClick={() => setNotice('')}>{notice}</div>}
    <div className="comment-list">
      {comments.isLoading && <p className="muted">评论加载中…</p>}
      {comments.data?.map((comment) => <CommentItem key={comment.id} comment={comment} rootId={comment.id} currentUserId={user?.id} onReply={(item, rootId) => setReplying({ parentId: rootId, answerId: item.id, name: item.userName || `用户 ${item.userId}` })} onLike={(id) => like.mutate(id)} onDelete={requestDelete} />)}
      {!comments.isLoading && comments.data?.length === 0 && <div className="empty compact">还没有评论，来坐第一排吧</div>}
    </div>
    <div className="pager compact-pager"><button disabled={current === 1} onClick={() => setCurrent((value) => value - 1)}>上一页</button><span>第 {current} 页</span><button disabled={(comments.data?.length || 0) < 5} onClick={() => setCurrent((value) => value + 1)}>下一页</button></div>
  </section>
}

function CommentItem({ comment, rootId, currentUserId, onReply, onLike, onDelete }: { comment: BlogComment; rootId: number; currentUserId?: number; onReply: (comment: BlogComment, rootId: number) => void; onLike: (id: number) => void; onDelete: (id: number) => void }) {
  return <article className={`comment-item ${comment.parentId ? 'reply-item' : ''}`}>
    <Avatar name={comment.userName} icon={comment.userIcon} />
    <div className="comment-copy"><div className="comment-author"><strong>{comment.userName || `用户 ${comment.userId}`}</strong>{comment.answerName && <span>回复 @{comment.answerName}</span>}<time>{formatTime(comment.createTime)}</time></div><p>{comment.content}</p><div className="comment-actions"><button className={comment.isLike ? 'liked' : ''} onClick={() => onLike(comment.id)}><Heart size={14} fill={comment.isLike ? 'currentColor' : 'none'} />{comment.liked || 0}</button><button onClick={() => onReply(comment, rootId)}><Reply size={14} />回复</button>{currentUserId === comment.userId && <button onClick={() => onDelete(comment.id)}><Trash2 size={14} />删除</button>}</div>{comment.replies?.length ? <div className="reply-list">{comment.replies.map((reply) => <CommentItem key={reply.id} comment={reply} rootId={rootId} currentUserId={currentUserId} onReply={onReply} onLike={onLike} onDelete={onDelete} />)}</div> : null}</div>
  </article>
}

function formatTime(value?: string) {
  if (!value) return ''
  return new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

function BlogCard({ blog, onOpen, onLike }: { blog: Blog; onOpen: () => void; onLike: () => void }) {
  return <article className="blog-card">
    <button className="blog-cover" onClick={onOpen}>{firstImage(blog.images) ? <img src={firstImage(blog.images)} alt="" /> : <BookOpenText />}</button>
    <div className="blog-card-body"><button className="text-button" onClick={onOpen}><h3>{blog.title}</h3></button><p>{blog.content}</p><div className="blog-card-footer"><span><Avatar name={blog.name} icon={blog.icon} />{blog.name || `用户 ${blog.userId}`}</span><button className={blog.isLike ? 'liked' : ''} onClick={onLike}><Heart size={17} fill={blog.isLike ? 'currentColor' : 'none'} />{blog.liked || 0}</button></div></div>
  </article>
}

function Avatar({ name, icon }: { name?: string; icon?: string }) {
  return <span className="avatar small">{icon ? <img src={mediaUrl(icon)} alt="" onError={(event) => { event.currentTarget.style.display = 'none' }} /> : name?.slice(0, 1) || '友'}</span>
}

function LoginPrompt() {
  return <div className="empty"><Users /><h3>登录后解锁你的社区</h3><p>查看关注动态、自己的笔记，并与其他用户互动。</p><Link className="primary-button" to="/profile">去登录</Link></div>
}
