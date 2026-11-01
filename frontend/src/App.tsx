import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider } from './auth'
import { Layout } from './components/Layout'
import { CommunityPage } from './pages/CommunityPage'
import { DiscoverPage } from './pages/DiscoverPage'
import { ProfilePage } from './pages/ProfilePage'
import { PublishPage } from './pages/PublishPage'
import { StudioPage } from './pages/StudioPage'

function App() {
  return <BrowserRouter>
    <AuthProvider>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<DiscoverPage />} />
          <Route path="community" element={<CommunityPage />} />
          <Route path="publish" element={<PublishPage />} />
          <Route path="profile" element={<ProfilePage />} />
          <Route path="studio" element={<StudioPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </AuthProvider>
  </BrowserRouter>
}

export default App
