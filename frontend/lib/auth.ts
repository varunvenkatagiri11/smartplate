/**
 * lib/auth.ts — token storage and current user utilities
 *
 * We store the JWT in localStorage so it persists across page refreshes.
 * On every API call we read it and send it as a Bearer token.
 *
 * The token itself contains userId and email — we decode it client-side
 * without hitting the server (JWTs are self-contained).
 */

const TOKEN_KEY = "smartplate_token"
const USER_KEY  = "smartplate_user"

export interface AuthUser {
  userId: number
  email: string
  token: string
}

/** Save token and user info after login/register */
export function saveAuth(user: AuthUser): void {
  if (typeof window === "undefined") return
  localStorage.setItem(TOKEN_KEY, user.token)
  localStorage.setItem(USER_KEY, JSON.stringify({ userId: user.userId, email: user.email }))
}

/** Get the stored JWT token */
export function getToken(): string | null {
  if (typeof window === "undefined") return null
  return localStorage.getItem(TOKEN_KEY)
}

/** Get the current logged-in user */
export function getUser(): Omit<AuthUser, "token"> | null {
  if (typeof window === "undefined") return null
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

/** Get userId — falls back to 1 if not logged in (for dev) */
export function getUserId(): number {
  return getUser()?.userId ?? 1
}

/** Check if user is logged in */
export function isLoggedIn(): boolean {
  return getToken() !== null
}

/** Clear auth on logout */
export function logout(): void {
  if (typeof window === "undefined") return
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}
