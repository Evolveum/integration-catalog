// src/app/lib/api.js
export const API_BASE_URL = '/api';

export async function apiGet(path) {
  const res = await fetch(`${API_BASE_URL}${path}`, {
    headers: { 'Accept': 'application/json' }
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`GET ${path} failed: ${res.status} ${text}`);
  }
  return res.json();
}
