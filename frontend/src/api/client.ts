import axios from 'axios';

// Default to a relative base URL so every `/api/...` call is same-origin and flows through the
// Vite dev proxy (vite.config.ts) in development and the nginx proxy (nginx.conf) in production.
// This avoids cross-origin/CORS issues. Set VITE_API_URL only when the API lives on another origin.
const client = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '',
});

client.interceptors.request.use((config) => {
  const token = localStorage.getItem('jwt_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

client.interceptors.response.use(
  (r) => r,
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('jwt_token');
      window.location.href = '/login';
    }
    return Promise.reject(err);
  }
);

export default client;
